package com.hizeric.logincore;

import com.hizeric.logincore.authme.AuthMePasswords;
import com.hizeric.logincore.storage.Storage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthMeCompatTest {

    // Upstream AuthMeReloaded 56e9a66b BCryptTest / BCrypt2yTest vectors for "password".
    private static final String BCRYPT_A = "$2a$10$6iATmYgwJVc3YONhVcZFve3Cfb5GnwvKhJ20r.hMjmcNkIT9.Uh9K";
    private static final String BCRYPT_2Y = "$2y$10$da641e404b982edf1c7c0uTU9BcKzfA2vWKV05q6r.dCvm/93wqVK";
    private static final String UUID = "069a79f4-44e9-4726-a5be-fca90e38a0e1";

    @TempDir
    Path temporaryDirectory;

    @Test
    void bcryptHashFromUpstreamVectorVerifies() {
        assertTrue(AuthMePasswords.isSupported(BCRYPT_A));
        assertTrue(AuthMePasswords.verify("password", BCRYPT_A));
        assertFalse(AuthMePasswords.verify("wrong-password", BCRYPT_A));

        assertTrue(AuthMePasswords.isSupported(BCRYPT_2Y));
        assertTrue(AuthMePasswords.verify("password", BCRYPT_2Y));
        assertTrue(AuthMePasswords.isSupported("$2b$10$0123456789012345678901234567890123456789012345678901"));
    }

    @Test
    void argon2HashIsRejectedAsUnsupported() {
        String argon2id = "$argon2id$v=19$m=65536,t=3,p=4$c29tZXNhbHRzb21lc2FsdA$bm90LWEtcmVhbC1oYXNo";
        assertFalse(AuthMePasswords.isSupported(argon2id));
        assertFalse(AuthMePasswords.verify("password", argon2id));
        assertFalse(AuthMePasswords.isSupported("$argon2i$v=19$m=65536,t=3,p=4$c2FsdA$hYXNo"));
        assertFalse(AuthMePasswords.isSupported("$argon2$v=19$m=65536,t=3,p=4$c2FsdA$hYXNo"));
        assertFalse(AuthMePasswords.verify("password", "$argon2id$anything"));
    }

    @Test
    void authmeTableIsCreatedIdempotently() throws Exception {
        Path database = temporaryDirectory.resolve("compat.db");
        try (Storage first = new Storage(database);
             Storage second = new Storage(database);
             Connection connection = open(database);
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                 "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'authme'")) {
            assertTrue(result.next());
            assertEquals(1, result.getInt(1));
            assertTrue(first.authmePassword("UnknownPlayer").get().isEmpty());
            assertTrue(second.authmePassword("UnknownPlayer").get().isEmpty());
        }
    }

    @Test
    void existingAuthMeRowIsReadable() throws Exception {
        Path database = temporaryDirectory.resolve("existing-row.db");
        try (Storage storage = new Storage(database); Connection connection = open(database)) {
            try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO authme (username, password, regdate, isLogged, lastlogin)
                VALUES (?, ?, 0, 0, 0)
                """)) {
                insert.setString(1, "Notch");
                insert.setString(2, BCRYPT_A);
                insert.executeUpdate();
            }

            assertEquals(Optional.of(BCRYPT_A), storage.authmePassword("NOTCH").get());
            assertTrue(storage.authmePassword("someone-else").get().isEmpty());

            storage.markLoggedIn("nOtCh").get();
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery(
                     "SELECT isLogged, lastlogin FROM authme WHERE username = 'notch' COLLATE NOCASE")) {
                assertTrue(result.next());
                assertEquals(1, result.getInt("isLogged"));
                assertTrue(result.getLong("lastlogin") > 0);
            }
        }
    }

    @Test
    void migrationRunsTwiceOnAnAuthMeShapedDb() throws Exception {
        Path database = temporaryDirectory.resolve("shaped.db");
        try (Connection connection = open(database); Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                CREATE TABLE authme (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    username VARCHAR(255) NOT NULL UNIQUE,
                    password VARCHAR(255) NOT NULL,
                    totp VARCHAR(16),
                    ip VARCHAR(40),
                    lastlogin BIGINT,
                    regdate BIGINT NOT NULL,
                    regip VARCHAR(40),
                    x DOUBLE NOT NULL DEFAULT '0.0',
                    y DOUBLE NOT NULL DEFAULT '0.0',
                    z DOUBLE NOT NULL DEFAULT '0.0',
                    world VARCHAR(255) NOT NULL DEFAULT 'world',
                    yaw FLOAT,
                    pitch FLOAT,
                    email VARCHAR(255),
                    isLogged INT DEFAULT '0',
                    realname VARCHAR(255) NOT NULL DEFAULT 'Player',
                    salt varchar(255),
                    hasSession INT NOT NULL DEFAULT '0',
                    premiumUUID VARCHAR(36),
                    playerUUID VARCHAR(36)
                )
                """);
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
            statement.executeUpdate("""
                INSERT INTO links (name_lower, mojang_uuid, canonical_name, linked_at, last_verified_at, source)
                VALUES ('notch', '" + UUID + "', 'Notch', 0, 0, 'TEST')
                """);
            statement.executeUpdate("INSERT INTO authme (username, password, regdate, isLogged) VALUES ('Notch', '"
                + BCRYPT_A + "', 0, 0)");
        }

        try (Storage storage = new Storage(database); Connection connection = open(database)) {
            Storage.MigrationReport first = storage.migrateLegacyClassification().get();
            Storage.MigrationReport second = storage.migrateLegacyClassification().get();

            assertEquals(1, first.classifiedPremium());
            assertEquals(0, second.classifiedPremium());
            assertEquals("PREMIUM", storage.classification("notch").get().orElseThrow());
            assertEquals(Optional.of(BCRYPT_A), storage.authmePassword("NOTCH").get());
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM authme")) {
                assertTrue(result.next());
                assertEquals(1, result.getInt(1), "migration must not duplicate authme rows");
            }
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery(
                     "SELECT isLogged, lastlogin FROM authme WHERE username = 'notch' COLLATE NOCASE")) {
                assertTrue(result.next());
                assertEquals(0, result.getInt("isLogged"), "migration must not mark a login");
                assertEquals(0, result.getLong("lastlogin"));
            }
        }
    }

    @Test
    void startupImportsPremiumUuidAsPremium() throws Exception {
        Path database = temporaryDirectory.resolve("premium-import.db");
        String premiumUuid = "11223344-5566-7788-99aa-bbccddeeff00";
        try (Connection connection = open(database)) {
            createAuthMeSchema(connection);
            try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO authme (username, password, regdate, premiumUUID) VALUES (?, ?, 0, ?)")) {
                insert.setString(1, "Legacy");
                insert.setString(2, BCRYPT_A);
                insert.setString(3, premiumUuid);
                insert.executeUpdate();
            }
        }

        try (Storage storage = new Storage(database); Connection connection = open(database)) {
            assertEquals("PREMIUM", storage.classification("legacy").get().orElseThrow());
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery(
                     "SELECT mojang_uuid FROM links WHERE name_lower = 'legacy'")) {
                assertTrue(result.next());
                assertEquals(premiumUuid, result.getString(1));
            }
            assertEquals(1, storage.premiumUuidImportReport().imported());
            assertEquals(0, storage.premiumUuidImportReport().skipped());
        }
    }

    @Test
    void premiumUuidConflictIsSkippedAndReported() throws Exception {
        Path database = temporaryDirectory.resolve("premium-conflict.db");
        String heldUuid = "11223344-5566-7788-99aa-bbccddeeff00";
        String freeUuid = "aabbccdd-eeff-0011-2233-445566778899";
        try (Connection connection = open(database)) {
            createAuthMeSchema(connection);
            try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO links (name_lower, mojang_uuid, canonical_name, linked_at, last_verified_at, source)
                VALUES (?, ?, ?, 0, 0, 'TEST')
                """)) {
                insert.setString(1, "holder");
                insert.setString(2, heldUuid);
                insert.setString(3, "Holder");
                insert.executeUpdate();
            }
            try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO authme (username, password, regdate, premiumUUID) VALUES (?, ?, 0, ?)")) {
                insert.setString(1, "Good");
                insert.setString(2, BCRYPT_A);
                insert.setString(3, freeUuid);
                insert.executeUpdate();
                insert.setString(1, "Bad");
                insert.setString(3, heldUuid);
                insert.executeUpdate();
            }
        }

        try (Storage storage = new Storage(database); Connection connection = open(database)) {
            assertEquals(1, storage.premiumUuidImportReport().imported());
            assertEquals(1, storage.premiumUuidImportReport().skipped());
            assertEquals("PREMIUM", storage.classification("good").get().orElseThrow());
            assertTrue(storage.classification("bad").get().isEmpty());
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery(
                     "SELECT name_lower FROM links WHERE mojang_uuid = '" + heldUuid + "'")) {
                assertTrue(result.next());
                assertEquals("holder", result.getString(1), "the existing link must not be overwritten");
            }
        }
    }

    @Test
    void malformedPremiumUuidIsSkipped() throws Exception {
        Path database = temporaryDirectory.resolve("premium-malformed.db");
        try (Connection connection = open(database)) {
            createAuthMeSchema(connection);
            try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO authme (username, password, regdate, premiumUUID) VALUES (?, ?, 0, ?)")) {
                insert.setString(1, "Broken");
                insert.setString(2, BCRYPT_A);
                insert.setString(3, "not-a-uuid");
                insert.executeUpdate();
            }
        }

        try (Storage storage = new Storage(database)) {
            assertEquals(0, storage.premiumUuidImportReport().imported());
            assertEquals(1, storage.premiumUuidImportReport().skipped());
            assertTrue(storage.classification("broken").get().isEmpty());
            assertTrue(storage.link("broken").get().isEmpty());
        }
    }

    @Test
    void existingClassificationIsNotOverwritten() throws Exception {
        Path database = temporaryDirectory.resolve("premium-classification.db");
        String premiumUuid = "11223344-5566-7788-99aa-bbccddeeff00";
        try (Connection connection = open(database)) {
            createAuthMeSchema(connection);
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("""
                    CREATE TABLE account_classification (
                        name_lower TEXT PRIMARY KEY,
                        classification TEXT NOT NULL CHECK (classification IN ('PREMIUM','OFFLINE')),
                        set_at INTEGER NOT NULL
                    )
                    """);
                statement.executeUpdate("""
                    INSERT INTO account_classification (name_lower, classification, set_at)
                    VALUES ('legacy', 'OFFLINE', 0)
                    """);
            }
            try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO authme (username, password, regdate, premiumUUID) VALUES (?, ?, 0, ?)")) {
                insert.setString(1, "Legacy");
                insert.setString(2, BCRYPT_A);
                insert.setString(3, premiumUuid);
                insert.executeUpdate();
            }
        }

        try (Storage storage = new Storage(database)) {
            assertEquals("OFFLINE", storage.classification("legacy").get().orElseThrow());
            assertTrue(storage.link("legacy").get().isEmpty());
            assertEquals(0, storage.premiumUuidImportReport().imported());
            assertEquals(1, storage.premiumUuidImportReport().skipped());
        }
    }

    private static void createAuthMeSchema(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                CREATE TABLE authme (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    username VARCHAR(255) NOT NULL UNIQUE,
                    password VARCHAR(255) NOT NULL,
                    totp VARCHAR(16),
                    ip VARCHAR(40),
                    lastlogin BIGINT,
                    regdate BIGINT NOT NULL,
                    regip VARCHAR(40),
                    x DOUBLE NOT NULL DEFAULT '0.0',
                    y DOUBLE NOT NULL DEFAULT '0.0',
                    z DOUBLE NOT NULL DEFAULT '0.0',
                    world VARCHAR(255) NOT NULL DEFAULT 'world',
                    yaw FLOAT,
                    pitch FLOAT,
                    email VARCHAR(255),
                    isLogged INT DEFAULT '0',
                    realname VARCHAR(255) NOT NULL DEFAULT 'Player',
                    salt varchar(255),
                    hasSession INT NOT NULL DEFAULT '0',
                    premiumUUID VARCHAR(36),
                    playerUUID VARCHAR(36)
                )
                """);
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
        }
    }

    private static Connection open(Path database) throws Exception {
        Class.forName("org.sqlite.JDBC");
        return DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
    }
}
