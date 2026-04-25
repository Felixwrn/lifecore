package de.felix.lifeplugin.storage;

import java.sql.*;
import java.util.UUID;

public class MySQLStorage implements Storage {

    private Connection connection;

    public MySQLStorage(String host, int port, String db, String user, String pass) {
        try {
            connection = DriverManager.getConnection(
                    "jdbc:mysql://" + host + ":" + port + "/" + db,
                    user,
                    pass
            );

            PreparedStatement ps = connection.prepareStatement(
                    "CREATE TABLE IF NOT EXISTS lives (uuid VARCHAR(36) PRIMARY KEY, lives INT)"
            );
            ps.execute();

        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    @Override
    public int getLives(UUID uuid) {
        try {
            PreparedStatement ps = connection.prepareStatement(
                    "SELECT lives FROM lives WHERE uuid=?"
            );
            ps.setString(1, uuid.toString());

            ResultSet rs = ps.executeQuery();

            if (rs.next()) {
                return rs.getInt("lives");
            }

        } catch (SQLException e) {
            e.printStackTrace();
        }

        return 10;
    }

    @Override
    public void setLives(UUID uuid, int lives) {
        try {
            PreparedStatement ps = connection.prepareStatement(
                    "REPLACE INTO lives (uuid, lives) VALUES (?, ?)"
            );
            ps.setString(1, uuid.toString());
            ps.setInt(2, lives);
            ps.execute();

        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void save(UUID uuid) {
        // nichts nötig
    }
}
