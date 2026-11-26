package com.hizeric.logincore;

import com.hizeric.logincore.storage.Storage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StorageTest {

    private static final String UUID = "069a79f4-44e9-4726-a5be-fca90e38a0e1";

    @TempDir
    Path temporaryDirectory;

    @Test
    void persistsNormalizedLinksAndExemptNames() throws Exception {
        try (Storage storage = new Storage(temporaryDirectory.resolve("accounts.db"))) {
            storage.putLink("NoTcH", UUID, "Notch", "AUTO").get();
            storage.addExempt("SomeName", "legacy account", "operator").get();

            Storage.AccountState account = storage.account("NOTCH").get();
            Storage.Summary summary = storage.summary().get();

            assertTrue(account.link().isPresent());
            assertEquals("notch", account.link().orElseThrow().name());
            assertEquals("AUTO", account.link().orElseThrow().source());
            assertFalse(account.exempt());
            assertTrue(storage.account("somename").get().exempt());
            assertEquals(1, summary.links());
            assertEquals(1, summary.exemptNames());
            assertTrue(storage.unlink("NOTCH").get());
            assertTrue(storage.removeExempt("SOMENAME").get());
            assertEquals(0, storage.summary().get().links());
        }
    }

    @Test
    void startupEnablesDurabilityPragmas() throws Exception {
        Path database = temporaryDirectory.resolve("pragmas.db");
        try (Storage storage = new Storage(database);
             Statement statement = storageConnection(storage).createStatement()) {
            // busy_timeout and synchronous are per-connection, so read them on Storage's own connection.
            assertEquals("wal", pragma(statement, "journal_mode"));
            assertEquals("5000", pragma(statement, "busy_timeout"));
            assertEquals("1", pragma(statement, "synchronous"));
        }
    }

    @Test
    void classificationIsWrittenOnce() throws Exception {
        Path database = temporaryDirectory.resolve("write-once.db");
        try (Storage storage = new Storage(database); Connection connection = open(database)) {
            assertEquals(Storage.ClassificationResult.SET,
                storage.setClassification("Notch", "OFFLINE").get());
            assertEquals(Storage.ClassificationResult.ALREADY_DIFFERENT,
                storage.setClassification("notch", "PREMIUM").get());
            assertEquals("OFFLINE", classification(connection, "notch"));
        }
    }

    @Test
    void settingTheSameClassificationIsIdempotent() throws Exception {
        Path database = temporaryDirectory.resolve("idempotent.db");
        try (Storage storage = new Storage(database)) {
            assertEquals(Storage.ClassificationResult.SET,
                storage.setClassification(" Notch ", "premium").get());
            assertEquals(Storage.ClassificationResult.ALREADY_SAME,
                storage.setClassification("notch", "PREMIUM").get());
            assertEquals("PREMIUM", storage.classification("NOTCH").get().orElseThrow());
        }
    }

    @Test
    void classificationSurvivesWithoutALinkRow() throws Exception {
        Path database = temporaryDirectory.resolve("no-link.db");
        try (Storage storage = new Storage(database); Connection connection = open(database)) {
            assertEquals(Storage.ClassificationResult.SET,
                storage.setClassification("OfflineGuy", "OFFLINE").get());
            assertTrue(storage.link("OfflineGuy").get().isEmpty());
            assertEquals("OFFLINE", classification(connection, "offlineguy"));
            assertEquals("OFFLINE", storage.classification("OFFLINEGUY").get().orElseThrow());
            assertEquals(1, storage.summary().get().classifications());
        }
    }

    @Test
    void invalidClassificationValueIsRejected() throws Exception {
        Path database = temporaryDirectory.resolve("invalid.db");
        try (Storage storage = new Storage(database)) {
            assertEquals(Storage.ClassificationResult.SET,
                storage.setClassification("Notch", "premium").get());
            assertThrows(IllegalArgumentException.class, () -> storage.setClassification("Notch", "MAYBE"));
            assertEquals("PREMIUM", storage.classification("notch").get().orElseThrow());
        }
    }

    @Test
    void linksClassificationColumnIsGone() throws Exception {
        Path database = temporaryDirectory.resolve("schema.db");
        try (Storage first = new Storage(database)) {
            assertFalse(hasColumn(database, "links", "classification"));
            assertTrue(hasColumn(database, "account_classification", "classification"));
        }
        try (Storage second = new Storage(database)) {
            assertFalse(hasColumn(database, "links", "classification"));
            assertTrue(hasColumn(database, "account_classification", "classification"));
        }
    }

    @Test
    void legacyLinksClassificationIsMigrated() throws Exception {
        Path database = temporaryDirectory.resolve("legacy.db");
        try (Connection connection = open(database); Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                CREATE TABLE links (
                    name_lower TEXT PRIMARY KEY,
                    mojang_uuid TEXT NOT NULL,
                    canonical_name TEXT,
                    linked_at INTEGER NOT NULL,
                    last_verified_at INTEGER NOT NULL,
                    source TEXT NOT NULL,
                    classification TEXT
                )
                """);
            statement.executeUpdate("""
                CREATE TRIGGER links_classification_immutable
                BEFORE UPDATE ON links
                WHEN OLD.classification IS NOT NULL
                  AND (NEW.classification IS NULL OR OLD.classification <> NEW.classification)
                BEGIN
                    SELECT RAISE(ABORT, 'classification is immutable');
                END
                """);
        }

        try (Storage storage = new Storage(database)) {
            assertFalse(hasColumn(database, "links", "classification"));
            assertTrue(hasColumn(database, "account_classification", "classification"));
        }
    }

    @Test
    void storedClassificationCannotBeChangedDirectly() throws Exception {
        Path database = temporaryDirectory.resolve("immutable.db");
        try (Storage storage = new Storage(database);
             Connection connection = open(database);
             Statement statement = connection.createStatement()) {
            storage.setClassification("Notch", "PREMIUM").get();
            assertThrows(SQLException.class, () -> statement.executeUpdate(
                "UPDATE account_classification SET classification = 'OFFLINE' WHERE name_lower = 'notch'"));
            assertEquals("PREMIUM", classification(connection, "notch"));
        }
    }

    @Test
    void duplicatePremiumUuidIsRejected() throws Exception {
        Path legacyDatabase = temporaryDirectory.resolve("duplicates.db");
        try (Connection connection = open(legacyDatabase); Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                CREATE TABLE links (
                    name_lower TEXT PRIMARY KEY,
                    mojang_uuid TEXT NOT NULL,
                    canonical_name TEXT,
                    linked_at INTEGER NOT NULL,
                    last_verified_at INTEGER NOT NULL,
                    source TEXT NOT NULL
                )
                """);
            for (String name : new String[]{"alpha", "beta"}) {
                statement.executeUpdate("INSERT INTO links (name_lower, mojang_uuid, canonical_name, linked_at, last_verified_at, source) "
                    + "VALUES ('" + name + "', '" + UUID + "', 'x', 0, 0, 'AUTO')");
            }
        }

        SQLException failure = assertThrows(SQLException.class, () -> new Storage(legacyDatabase));
        StringBuilder messages = new StringBuilder();
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            messages.append(cause.getMessage()).append('\n');
        }
        assertTrue(messages.toString().contains(UUID));

        Path cleanDatabase = temporaryDirectory.resolve("clean.db");
        try (Storage storage = new Storage(cleanDatabase)) {
            storage.putLink("Alpha", UUID, "Alpha", "AUTO").get();
            assertThrows(Exception.class, () -> storage.putLink("Beta", UUID, "Beta", "AUTO").get());
            assertEquals(1, storage.summary().get().links());
        }
    }

    private static Connection open(Path database) throws Exception {
        Class.forName("org.sqlite.JDBC");
        return DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
    }

    private static boolean hasColumn(Path database, String table, String column) throws Exception {
        try (Connection connection = open(database);
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (result.next()) {
                if (column.equals(result.getString("name"))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Connection storageConnection(Storage storage) throws Exception {
        Field field = Storage.class.getDeclaredField("connection");
        field.setAccessible(true);
        return (Connection) field.get(storage);
    }

    private static String pragma(Statement statement, String name) throws SQLException {
        try (ResultSet result = statement.executeQuery("PRAGMA " + name)) {
            return result.next() ? result.getString(1) : null;
        }
    }

    private static String classification(Connection connection, String normalizedName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT classification FROM account_classification WHERE name_lower = ?")) {
            statement.setString(1, normalizedName);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getString(1) : null;
            }
        }
    }
}
