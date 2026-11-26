package com.hizeric.logincore;

import com.hizeric.logincore.storage.Storage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    void classificationColumnIsAddedIdempotently() throws Exception {
        Path database = temporaryDirectory.resolve("idempotent.db");

        try (Storage first = new Storage(database)) {
            assertTrue(hasClassificationColumn(database));
        }
        try (Storage second = new Storage(database)) {
            assertTrue(hasClassificationColumn(database));
        }
    }

    @Test
    void classificationCanBeSetOnceFromLegacyNull() throws Exception {
        Path database = temporaryDirectory.resolve("legacy.db");
        try (Storage storage = new Storage(database)) {
            storage.putLink("Legacy", UUID, "Legacy", "AUTO").get();
        }

        try (Connection connection = open(database); Statement statement = connection.createStatement()) {
            assertNull(classification(connection, "legacy"));
            statement.executeUpdate("UPDATE links SET classification = 'OFFLINE' WHERE name_lower = 'legacy'");
            assertEquals("OFFLINE", classification(connection, "legacy"));
        }
    }

    @Test
    void classificationCannotBeChanged() throws Exception {
        Path database = temporaryDirectory.resolve("immutable.db");
        try (Storage storage = new Storage(database)) {
            storage.putLink("Notch", UUID, "Notch", "AUTO").get();
        }

        try (Connection connection = open(database); Statement statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE links SET classification = 'PREMIUM' WHERE name_lower = 'notch'");
            assertThrows(SQLException.class, () ->
                statement.executeUpdate("UPDATE links SET classification = 'OFFLINE' WHERE name_lower = 'notch'"));
            assertEquals("PREMIUM", classification(connection, "notch"));
        }
    }

    @Test
    void classificationCannotBeCleared() throws Exception {
        Path database = temporaryDirectory.resolve("clear.db");
        try (Storage storage = new Storage(database)) {
            storage.putLink("Notch", UUID, "Notch", "AUTO").get();
        }

        try (Connection connection = open(database); Statement statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE links SET classification = 'PREMIUM' WHERE name_lower = 'notch'");
            assertThrows(SQLException.class, () ->
                statement.executeUpdate("UPDATE links SET classification = NULL WHERE name_lower = 'notch'"));
            assertEquals("PREMIUM", classification(connection, "notch"));
        }
    }

    @Test
    void reverificationDoesNotTouchClassification() throws Exception {
        Path database = temporaryDirectory.resolve("reverify.db");
        try (Storage storage = new Storage(database);
             Connection connection = open(database);
             Statement statement = connection.createStatement()) {
            storage.putLink("Notch", UUID, "Notch", "AUTO").get();
            statement.executeUpdate("UPDATE links SET classification = 'PREMIUM' WHERE name_lower = 'notch'");

            storage.putLink("Notch", UUID, "Notch", "AUTO").get();

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

    private static boolean hasClassificationColumn(Path database) throws Exception {
        try (Connection connection = open(database);
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA table_info(links)")) {
            while (result.next()) {
                if ("classification".equals(result.getString("name"))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String classification(Connection connection, String name) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT classification FROM links WHERE name_lower = ?")) {
            statement.setString(1, name);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getString(1) : null;
            }
        }
    }
}
