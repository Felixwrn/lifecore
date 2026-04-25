package de.felix.lifeplugin;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

public class MySQL {

    public Connection connect(String host, String database, String user, String password) {

        try {
            String url = "jdbc:mysql://" + host + ":3306/" + database;

            return DriverManager.getConnection(url, user, password);

        } catch (SQLException e) {
            e.printStackTrace();
        }

        return null;
    }
}
