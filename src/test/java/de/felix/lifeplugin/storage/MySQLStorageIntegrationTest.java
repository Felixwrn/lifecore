package de.felix.lifeplugin.storage;

import com.zaxxer.hikari.HikariConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Run against an isolated database using LIFECORE_TEST_MYSQL_{URL,USER,PASSWORD}. */
@EnabledIfEnvironmentVariable(named = "LIFECORE_TEST_MYSQL_URL", matches = ".+")
class MySQLStorageIntegrationTest {
    private HikariConfig configuration() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(System.getenv("LIFECORE_TEST_MYSQL_URL"));
        config.setUsername(System.getenv("LIFECORE_TEST_MYSQL_USER"));
        config.setPassword(System.getenv("LIFECORE_TEST_MYSQL_PASSWORD"));
        config.setMaximumPoolSize(3);
        config.setMinimumIdle(0);
        config.setConnectionTimeout(5_000);
        config.setInitializationFailTimeout(5_000);
        return config;
    }
    private Connection direct() throws Exception {
        return DriverManager.getConnection(System.getenv("LIFECORE_TEST_MYSQL_URL"),
                System.getenv("LIFECORE_TEST_MYSQL_USER"), System.getenv("LIFECORE_TEST_MYSQL_PASSWORD"));
    }

    @Test void migratesLegacyPreservesExistingRecordsAndSurvivesPooledRestart() throws Exception {
        UUID migrated = UUID.randomUUID(), existing = UUID.randomUUID();
        PlayerData expected = new PlayerData(9, "de", Map.of("stone", 12), Set.of("stone"));
        try (Connection connection = direct(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("DROP TABLE IF EXISTS lives");
            statement.executeUpdate("DROP TABLE IF EXISTS players");
            statement.executeUpdate("DROP TABLE IF EXISTS lifecore_schema");
        }
        // A partially migrated installation already has new player rows; these always win.
        try (Storage storage = new MySQLStorage(configuration())) { storage.save(existing, expected); }
        try (Connection connection = direct(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE lifecore_schema SET version=0 WHERE id=1");
            statement.executeUpdate("CREATE TABLE lives (uuid VARCHAR(36) PRIMARY KEY,lives INT)");
            statement.executeUpdate("INSERT INTO lives VALUES ('" + migrated + "',4),('" + existing + "',1)");
        }
        try (MySQLStorage storage = new MySQLStorage(configuration())) {
            assertEquals(4, storage.load(migrated).orElseThrow().lives());
            assertEquals(expected, storage.load(existing).orElseThrow());
            assertTrue(storage.load(UUID.randomUUID()).isEmpty());
            // Exercise repeated borrow/release and full snapshot persistence.
            try (PlayerDataService service = new PlayerDataService(storage, 3)) {
                assertEquals(expected, service.load(existing).join());
                service.update(existing, p -> p.withLives(8).withProgress("craft", 5).completeChallenge("craft"));
                service.save(existing).join();
            }
        }
        PlayerData after = expected.withLives(8).withProgress("craft", 5).completeChallenge("craft");
        try (Storage storage = new MySQLStorage(configuration())) {
            assertEquals(after, storage.load(existing).orElseThrow());
            assertEquals(4, storage.load(migrated).orElseThrow().lives());
            for (int i = 0; i < 20; i++) assertEquals(after, storage.load(existing).orElseThrow());
        }
        try (Connection connection = direct(); Statement statement = connection.createStatement()) {
            try (ResultSet result = statement.executeQuery("SELECT version FROM lifecore_schema WHERE id=1")) {
                assertTrue(result.next());
                assertEquals(1, result.getInt(1));
            }
            try (ResultSet result = statement.executeQuery("SELECT lives FROM lives WHERE uuid='" + existing + "'")) {
                assertTrue(result.next());
                assertEquals(1, result.getInt(1), "Legacy table remains untouched");
            }
            // Corruption must produce a failure rather than a default player record.
            statement.executeUpdate("UPDATE players SET challenge_progress='{\"stone\":1.5}' WHERE uuid='" + existing + "'");
        }
        try (Storage storage = new MySQLStorage(configuration())) {
            assertThrows(StorageException.class, () -> storage.load(existing));
        }
        try (Connection connection = direct(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE lifecore_schema SET version=2 WHERE id=1");
        }
        try {
            assertThrows(StorageException.class, () -> new MySQLStorage(configuration()));
        } finally {
            try (Connection connection = direct(); Statement statement = connection.createStatement()) {
                statement.executeUpdate("UPDATE lifecore_schema SET version=1 WHERE id=1");
            }
        }
    }
}
