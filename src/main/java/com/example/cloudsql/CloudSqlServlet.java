/*
 * Copyright 2016 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.cloudsql;

import com.google.api.core.ApiFuture;

import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.FirestoreOptions;

import com.google.common.base.Stopwatch;

import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.cloud.firestore.QuerySnapshot;
import com.google.cloud.firestore.WriteResult;

import java.io.IOException;
import java.io.PrintWriter;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import javax.servlet.annotation.WebServlet;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.ServletException;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

// [START gae_flex_mysql_app]
@SuppressWarnings("serial")
@WebServlet(name = "cloudsql", value = "")
public class CloudSqlServlet extends HttpServlet {
  Connection conn;
  private Firestore db;

  @Override
  public void doGet(HttpServletRequest req, HttpServletResponse resp)
          throws IOException, ServletException {
    //saveIpsToMySql(req, resp);

    JSONArray jsonListObj = readFromFireDb(req, resp);
    PrintWriter writer = resp.getWriter();
    resp.setContentType("application/json");
    writer.print(jsonListObj);
    writer.flush();
  }

  @Override
  public void doPost(HttpServletRequest req, HttpServletResponse resp)
          throws IOException, ServletException {
    saveIpsToMySql(req, resp);
    writeToFireBase(req, resp);
  }

  private JSONArray readFromFireDb(HttpServletRequest req, HttpServletResponse resp)
  {
    String username = req.getParameter("username");
    // [START fs_add_query]
    // asynchronously query
    ApiFuture<QuerySnapshot> query = db.collection("users").whereEqualTo("username", username).get();
    // ...
    // query.get() blocks on response
    QuerySnapshot querySnapshot = null;
    try {
      querySnapshot = query.get();
    } catch (InterruptedException e) {
      e.printStackTrace();
    } catch (ExecutionException e) {
      e.printStackTrace();
    }

    JSONArray jsonListObj = new JSONArray();

    List<QueryDocumentSnapshot> documents = querySnapshot.getDocuments();
    for (QueryDocumentSnapshot document : documents) {
      JSONObject json = new JSONObject();
      json.put("User", document.getId());
      json.put("First", document.getString("firstName"));

      System.out.println("First: " + document.getString("firstName"));
      if (document.contains("last")) {
        json.put("First", document.getString("firstName"));
      }
      json.put("Age", document.getString("age"));

      jsonListObj.add(json);
    }
    // [END fs_add_query]

    return jsonListObj;
  }


  private void writeToFireBase(HttpServletRequest req, HttpServletResponse resp)
  {
    String username = req.getParameter("username");
    String firstName = req.getParameter("firstName");
    String ageStr = req.getParameter("age");
    int age = Integer.parseInt(ageStr);

    DocumentReference docRef = db.collection("users").document(username);
    Map<String, Object> data = new HashMap<>();
    data.put("FirstName", firstName);
    data.put("Age", age);
    //asynchronously write data
    ApiFuture<WriteResult> result = docRef.set(data);
  }


  private void saveIpsToMySql(HttpServletRequest req, HttpServletResponse resp)
          throws IOException, ServletException {
    final String createTableSql = "CREATE TABLE IF NOT EXISTS visits ( visit_id INT NOT NULL "
            + "AUTO_INCREMENT, user_ip VARCHAR(46) NOT NULL, timestamp DATETIME NOT NULL, "
            + "PRIMARY KEY (visit_id) )";
    final String createVisitSql = "INSERT INTO visits (user_ip, timestamp) VALUES (?, ?)";
    final String selectSql = "SELECT user_ip, timestamp FROM visits ORDER BY timestamp DESC "
            + "LIMIT 10";

    String path = req.getRequestURI();
    if (path.startsWith("/favicon.ico")) {
      return; // ignore the request for favicon.ico
    }

    PrintWriter out = resp.getWriter();
    resp.setContentType("text/plain");

    // store only the first two octets of a users ip address
    String userIp = req.getRemoteAddr();
    InetAddress address = InetAddress.getByName(userIp);
    if (address instanceof Inet6Address) {
      // nest indexOf calls to find the second occurrence of a character in a string
      // an alternative is to use Apache Commons Lang: StringUtils.ordinalIndexOf()
      userIp = userIp.substring(0, userIp.indexOf(":", userIp.indexOf(":") + 1)) + ":*:*:*:*:*:*";
    } else if (address instanceof Inet4Address) {
      userIp = userIp.substring(0, userIp.indexOf(".", userIp.indexOf(".") + 1)) + ".*.*";
    }

    Stopwatch stopwatch = Stopwatch.createStarted();
    try (PreparedStatement statementCreateVisit = conn.prepareStatement(createVisitSql)) {
      conn.createStatement().executeUpdate(createTableSql);
      statementCreateVisit.setString(1, userIp);
      statementCreateVisit.setTimestamp(2, new Timestamp(new Date().getTime()));
      statementCreateVisit.executeUpdate();

      try (ResultSet rs = conn.prepareStatement(selectSql).executeQuery()) {
        stopwatch.stop();
        out.print("Last 10 visits:\n");
        while (rs.next()) {
          String savedIp = rs.getString("user_ip");
          String timeStamp = rs.getString("timestamp");
          out.print("Time: " + timeStamp + " Addr: " + savedIp + "\n");
        }
        out.println("Elapsed: " + stopwatch.elapsed(TimeUnit.MILLISECONDS));
      }
    } catch (SQLException e) {
      throw new ServletException("SQL error", e);
    }
  }

  @Override
  public void init() throws ServletException {
    try {
      String url;

      Properties properties = new Properties();
      try {
        properties.load(
            getServletContext().getResourceAsStream("/WEB-INF/classes/config.properties"));
        url = properties.getProperty("sqlUrl");
      } catch (IOException e) {
        log("no property", e);  // Servlet Init should never fail.
        return;
      }

      log("connecting to: " + url);
      try {
        Class.forName("com.mysql.jdbc.Driver");
        conn = DriverManager.getConnection(url);

        Firestore db = FirestoreOptions.getDefaultInstance().getService();
        // [END fs_initialize]
        this.db = db;

      } catch (ClassNotFoundException e) {
        throw new ServletException("Error loading JDBC Driver", e);
      } catch (SQLException e) {
        throw new ServletException("Unable to connect to PostGre", e);
      }

    } finally {
      // Nothing really to do here.
    }
  }
}
// [END gae_flex_mysql_app]
