package de.felix.lifeplugin.storage;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Pooled MySQL storage. Every operation uses its own connection and propagates failures. */
public final class MySQLStorage implements Storage {
    private static final Gson JSON = new Gson();
    private static final int SCHEMA_VERSION = 1;
    private final HikariDataSource pool;

    public MySQLStorage(String host, int port, String database, String user, String password) {
        this(configuration(host, port, database, user, password));
    }

    public MySQLStorage(HikariConfig config) {
        HikariDataSource created = null;
        try {
            created = new HikariDataSource(config);
            initialize(created);
            pool = created;
        } catch (SQLException | RuntimeException e) {
            if (created != null) created.close();
            throw new StorageException("Cannot initialize MySQL player storage", e);
        }
    }

    private static HikariConfig configuration(String host, int port, String database, String user, String password) {
        if (host == null || !host.matches("[a-zA-Z0-9._-]+") || port < 1 || port > 65535
                || database == null || !database.matches("[a-zA-Z0-9_-]+"))
            throw new IllegalArgumentException("Invalid MySQL host, port or database");
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + database);
        config.setUsername(user);
        config.setPassword(password);
        config.addDataSourceProperty("sslMode", "VERIFY_IDENTITY");
        config.addDataSourceProperty("allowPublicKeyRetrieval", "false");
        config.addDataSourceProperty("connectTimeout", "10000");
        config.addDataSourceProperty("socketTimeout", "15000");
        config.setMaximumPoolSize(6);
        config.setMinimumIdle(1);
        config.setConnectionTimeout(10_000);
        config.setValidationTimeout(5_000);
        config.setInitializationFailTimeout(10_000);
        config.setPoolName("LifeCore-MySQL");
        return config;
    }

    private static void initialize(HikariDataSource pool) throws SQLException {
        try (Connection connection = pool.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS lifecore_schema (id INT NOT NULL PRIMARY KEY, version INT NOT NULL)");
            statement.executeUpdate("INSERT IGNORE INTO lifecore_schema (id, version) VALUES (1, 0)");
            int version;
            try (ResultSet result = statement.executeQuery("SELECT version FROM lifecore_schema WHERE id=1")) {
                if (!result.next()) throw new SQLException("Missing LifeCore schema version");
                version = result.getInt(1);
            }
            if (version < 0 || version > SCHEMA_VERSION)
                throw new SQLException("Unsupported LifeCore database schema version");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS players (uuid VARCHAR(36) NOT NULL PRIMARY KEY, "
                    + "lives INT NOT NULL, language VARCHAR(16) NOT NULL, challenge_progress LONGTEXT NOT NULL, "
                    + "completed_challenges LONGTEXT NOT NULL)");
            if (version < 1) {
                connection.setAutoCommit(false);
                try {
                    boolean legacy;
                    try (PreparedStatement query = connection.prepareStatement("SELECT 1 FROM information_schema.tables "
                            + "WHERE table_schema=DATABASE() AND table_name='lives'"); ResultSet result = query.executeQuery()) {
                        legacy = result.next();
                    }
                    if (legacy) statement.executeUpdate("INSERT INTO players (uuid,lives,language,challenge_progress,completed_challenges) "
                            + "SELECT legacy.uuid,legacy.lives,'en','{}','[]' FROM lives legacy "
                            + "LEFT JOIN players current_player ON current_player.uuid=legacy.uuid WHERE current_player.uuid IS NULL");
                    statement.executeUpdate("UPDATE lifecore_schema SET version=1 WHERE id=1");
                    connection.commit();
                } catch (SQLException e) {
                    connection.rollback();
                    throw e;
                } finally { connection.setAutoCommit(true); }
            }
        }
    }

    @Override public Optional<PlayerData> load(UUID uuid) {
        java.util.Objects.requireNonNull(uuid);
        try (Connection connection = pool.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "SELECT lives,language,challenge_progress,completed_challenges FROM players WHERE uuid=?")) {
            statement.setString(1, uuid.toString());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return Optional.empty();
                Map<String, Integer> progress = parseProgress(result.getString("challenge_progress"));
                Set<String> completed = parseCompleted(result.getString("completed_challenges"));
                return Optional.of(new PlayerData(result.getInt("lives"), result.getString("language"), progress, completed));
            }
        } catch (SQLException | RuntimeException e) { throw new StorageException("Cannot load MySQL player data", e); }
    }

    private static Map<String, Integer> parseProgress(String encoded) {
        JsonElement parsed = JsonParser.parseString(encoded);
        if (!parsed.isJsonObject()) throw new IllegalArgumentException("Invalid challenge progress JSON");
        Map<String, Integer> progress = new HashMap<>();
        for (Map.Entry<String, JsonElement> entry : parsed.getAsJsonObject().entrySet()) {
            JsonElement value = entry.getValue();
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
                throw new IllegalArgumentException("Invalid challenge progress value");
            progress.put(entry.getKey(), value.getAsBigDecimal().intValueExact());
        }
        return progress;
    }

    private static Set<String> parseCompleted(String encoded) {
        JsonElement parsed = JsonParser.parseString(encoded);
        if (!parsed.isJsonArray()) throw new IllegalArgumentException("Invalid completed challenges JSON");
        Set<String> completed = new HashSet<>();
        for (JsonElement value : parsed.getAsJsonArray()) {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
                throw new IllegalArgumentException("Invalid completed challenge");
            completed.add(value.getAsString());
        }
        return completed;
    }

    @Override public void save(UUID uuid, PlayerData data) {
        java.util.Objects.requireNonNull(uuid);
        java.util.Objects.requireNonNull(data);
        try (Connection connection = pool.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO players (uuid,lives,language,challenge_progress,completed_challenges) VALUES (?,?,?,?,?) "
                        + "ON DUPLICATE KEY UPDATE lives=VALUES(lives),language=VALUES(language),"
                        + "challenge_progress=VALUES(challenge_progress),completed_challenges=VALUES(completed_challenges)")) {
            statement.setString(1, uuid.toString());
            statement.setInt(2, data.lives());
            statement.setString(3, data.language());
            statement.setString(4, JSON.toJson(data.challengeProgress()));
            statement.setString(5, JSON.toJson(data.completedChallenges()));
            statement.executeUpdate();
        } catch (SQLException e) { throw new StorageException("Cannot save MySQL player data", e); }
    }

    @Override public void close() { pool.close(); }
}
