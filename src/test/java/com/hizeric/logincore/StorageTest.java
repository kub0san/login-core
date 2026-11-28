package com.hizeric.logincore;

import com.hizeric.logincore.storage.Storage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StorageTest {

    private static final String UUID = "069a79f4-44e9-4726-a5be-fca90e38a0e1";

    @TempDir
    Path temporaryDirectory;

    @Test
    void persistsNormalizedLinks() throws Exception {
        Path database = temporaryDirectory.resolve("accounts.db");
        try (Storage storage = new Storage(database)) {
            storage.putLink("NoTcH", UUID, "Notch", "AUTO").get();

            Storage.AccountState account = storage.account("NOTCH").get();
            Storage.Summary summary = storage.summary().get();

            assertTrue(account.link().isPresent());
            assertEquals("notch", account.link().orElseThrow().name());
            assertEquals("AUTO", account.link().orElseThrow().source());
            assertEquals(1, summary.links());
            assertTrue(storage.unlink("NOTCH").get());
            assertEquals(0, storage.summary().get().links());
        }
        assertFalse(hasTable(database, "exempt"), "the exempt table must not survive opening storage");
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
    void authoritativeNotFoundClassifiesOffline() throws Exception {
        Path database = temporaryDirectory.resolve("offline.db");
        try (Storage storage = new Storage(database); Connection connection = open(database)) {
            storage.setClassification("OfflineGuy", "OFFLINE").get();

            assertEquals("OFFLINE", storage.classification("OFFLINEGUY").get().orElseThrow());
            assertEquals("OFFLINE", classification(connection, "offlineguy"),
                "the persisted row, not just the setter call, must read OFFLINE");
            assertTrue(storage.link("OfflineGuy").get().isEmpty(),
                "OFFLINE is recorded without a premium link row");
            assertEquals(1, storage.summary().get().classifications());
        }
    }

    @Test
    void offlineClassificationIsWriteOnce() throws Exception {
        try (Storage storage = new Storage(temporaryDirectory.resolve("offline-lock.db"))) {
            assertEquals(Storage.ClassificationResult.SET,
                storage.setClassification("OfflineGuy", "OFFLINE").get());
            assertEquals(Storage.ClassificationResult.ALREADY_DIFFERENT,
                storage.setClassification("OFFLINEGUY", "PREMIUM").get());
            assertFalse(storage.commitPremiumIdentity("OfflineGuy", UUID, "OfflineGuy", "AUTO").get());

            assertEquals("OFFLINE", storage.classification("offlineguy").get().orElseThrow());
            assertTrue(storage.link("OfflineGuy").get().isEmpty(),
                "a later PREMIUM attempt must not add a link to an OFFLINE account");
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
    void newerSchemaVersionIsRejected() throws Exception {
        Path database = temporaryDirectory.resolve("newer-schema.db");
        try (Connection connection = open(database); Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA user_version = 4");
        }

        SQLException failure = assertThrows(SQLException.class, () -> new Storage(database));
        StringBuilder messages = new StringBuilder();
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            messages.append(cause.getMessage()).append('\n');
        }
        assertTrue(messages.toString().contains("user_version 4"));
        assertTrue(messages.toString().contains("newer login-core"));
    }

    @Test
    void startupCreatesABackupBeforeDdl() throws Exception {
        Path database = temporaryDirectory.resolve("startup-backup.db");
        createLegacyDatabase(database, "PREMIUM");

        try (Storage storage = new Storage(database)) {
            assertEquals("PREMIUM", storage.classification("notch").get().orElseThrow());
            assertFalse(hasColumn(database, "links", "classification"));
            assertEquals(3, userVersion(database));
            assertEquals(1, backups(database).size(), "startup takes exactly one backup before the migration DDL");
        }
    }

    @Test
    void freshInstallSkipsBackup() throws Exception {
        Path database = temporaryDirectory.resolve("fresh-no-backup.db");
        try (Storage storage = new Storage(database)) {
            assertEquals(0, storage.summary().get().links());
        }
        assertTrue(backups(database).isEmpty(), "no pre-existing file means nothing to back up");
        assertEquals(3, userVersion(database));
    }

    @Test
    void secondStartupIsIdempotent() throws Exception {
        Path database = temporaryDirectory.resolve("second-startup.db");
        createLegacyDatabase(database, "PREMIUM");

        try (Storage first = new Storage(database)) {
            assertEquals("PREMIUM", first.classification("notch").get().orElseThrow());
        }
        List<Path> afterFirst = backups(database);
        assertEquals(1, afterFirst.size());

        try (Storage second = new Storage(database)) {
            assertEquals("PREMIUM", second.classification("notch").get().orElseThrow());
        }
        assertEquals(afterFirst, backups(database), "a second startup must not take another backup");
        assertEquals(3, userVersion(database));
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

    @Test
    void putLinkNeverOverwritesAnExistingUuid() throws Exception {
        String otherUuid = "00000000-0000-0000-0000-000000000001";
        try (Storage storage = new Storage(temporaryDirectory.resolve("uuid-write-once.db"))) {
            assertTrue(storage.putLink("NoTcH", UUID, "Notch", "AUTO").get());
            assertFalse(storage.putLink("notch", otherUuid, "Notch", "AUTO").get(),
                "a different UUID for an existing name must be reported as a conflict");
            assertEquals(UUID, storage.link("Notch").get().orElseThrow().mojangUuid(),
                "the stored UUID must not change");
        }
    }

    @Test
    void concurrentPremiumCommitsKeepOneWriteOnceLink() throws Exception {
        String uuidA = "00000000-0000-0000-0000-0000000000a1";
        String uuidB = "00000000-0000-0000-0000-0000000000b2";
        try (Storage storage = new Storage(temporaryDirectory.resolve("concurrent-commits.db"))) {
            CompletableFuture<Boolean> first =
                storage.commitPremiumIdentity("Notch", uuidA, "Notch", "AUTO");
            CompletableFuture<Boolean> second =
                storage.commitPremiumIdentity("Notch", uuidB, "Notch", "AUTO");

            boolean firstCommitted = first.join();
            boolean secondCommitted = second.join();

            assertTrue(firstCommitted ^ secondCommitted,
                "exactly one concurrent commit for the same name may win");
            String winner = firstCommitted ? uuidA : uuidB;
            String loser = firstCommitted ? uuidB : uuidA;
            assertEquals("PREMIUM", storage.classification("NOTCH").get().orElseThrow());
            assertEquals(winner, storage.link("Notch").get().orElseThrow().mojangUuid());
            assertNotEquals(loser, storage.link("Notch").get().orElseThrow().mojangUuid());
            assertEquals(1, storage.summary().get().links());
            assertEquals(1, storage.summary().get().classifications());
        }
    }

    @Test
    void onePremiumUuidCannotBeAdoptedByTwoNames() throws Exception {
        String uuid = "00000000-0000-0000-0000-0000000000c3";
        try (Storage storage = new Storage(temporaryDirectory.resolve("one-uuid-two-names.db"))) {
            CompletableFuture<Boolean> alpha =
                storage.commitPremiumIdentity("Alpha", uuid, "Alpha", "AUTO");
            CompletableFuture<Boolean> beta =
                storage.commitPremiumIdentity("Beta", uuid, "Beta", "AUTO");

            boolean alphaCommitted = committed(alpha);
            boolean betaCommitted = committed(beta);

            assertTrue(alphaCommitted ^ betaCommitted,
                "a single premium UUID may be linked to exactly one name");
            String winner = alphaCommitted ? "Alpha" : "Beta";
            String loser = alphaCommitted ? "Beta" : "Alpha";
            assertEquals(uuid, storage.link(winner).get().orElseThrow().mojangUuid());
            assertTrue(storage.link(loser).get().isEmpty(), "the losing name must not adopt the UUID");
            assertTrue(storage.classification(loser).get().isEmpty(),
                "the rolled-back commit must not leave a classification lockout");
            assertEquals(1, storage.summary().get().links());
            assertEquals(1, storage.summary().get().classifications());
        }
    }

    private static boolean committed(CompletableFuture<Boolean> future) {
        try {
            return future.join();
        } catch (CompletionException conflict) {
            return false; // the unique UUID index rejects the second name; its transaction rolls back
        }
    }

    @Test
    void linkByUuidIsAReadOnlyNameLookup() throws Exception {
        try (Storage storage = new Storage(temporaryDirectory.resolve("by-uuid.db"))) {
            assertTrue(storage.linkByUuid(UUID).get().isEmpty());
            storage.putLink("NoTcH", UUID, "Notch", "AUTO").get();

            Storage.Link found = storage.linkByUuid(UUID).get().orElseThrow();
            assertEquals("notch", found.name());
            assertEquals(UUID, found.mojangUuid());
            assertEquals(1, storage.summary().get().links());
        }
    }

    @Test
    void renameReleasesOldNameEvictsSquatterAndWritesPremium() throws Exception {
        Path database = temporaryDirectory.resolve("premium-rename.db");
        try (Storage storage = new Storage(database); Connection connection = open(database)) {
            storage.putLink("OldName", UUID, "OldName", "AUTO").get();
            storage.setClassification("OldName", "PREMIUM").get();
            insertAuthme(connection, "OldName", "premium-hash", 11L, "OldName", "old-token");
            insertAuthme(connection, "NewName", "squatter-hash", 77L, "NewName", "squatter-token");
            storage.setClassification("NewName", "OFFLINE").get();

            assertEquals(Storage.RenameMigrationResult.COMMITTED,
                storage.migratePremiumRename("OldName", "NewName", UUID, "new-token").get());

            assertTrue(storage.link("OldName").get().isEmpty(), "the old premium link must be released");
            assertTrue(storage.classification("OldName").get().isEmpty(), "the old name must be declassified");
            assertFalse(storage.isRegistered("OldName").get(), "the old authme row must be gone");
            assertEquals(UUID, storage.link("NewName").get().orElseThrow().mojangUuid());
            assertEquals("PREMIUM", storage.classification("NewName").get().orElseThrow());
            assertTrue(storage.isRegistered("NewName").get());
            assertEquals("new-token", authmeString(connection, "newname", "account_token"));
            assertEquals(UUID, authmeString(connection, "newname", "playerUUID"));
            assertNotEquals(77L, authmeRegdate(connection, "newname"), "the premium row is new, not the squat");

            Storage.EvictedAccount evicted = storage.findEvicted("squatter-token").get().orElseThrow();
            assertEquals("squatter-token", evicted.token());
            assertEquals("newname", evicted.oldUsername());
            assertEquals("squatter-hash", evicted.password());
            assertEquals(77L, evicted.regdate());
            assertEquals("NewName", evicted.realname());
        }
    }

    @Test
    void renameRollsBackEverythingOnEvictedTokenConflict() throws Exception {
        Path database = temporaryDirectory.resolve("rename-rollback.db");
        try (Storage storage = new Storage(database);
             Connection connection = open(database);
             Statement statement = connection.createStatement()) {
            storage.putLink("OldName", UUID, "OldName", "AUTO").get();
            storage.setClassification("OldName", "PREMIUM").get();
            insertAuthme(connection, "OldName", "premium-hash", 11L, "OldName", "old-token");
            insertAuthme(connection, "NewName", "squatter-hash", 77L, "NewName", "squatter-token");
            storage.setClassification("NewName", "OFFLINE").get();
            // The token is already archived elsewhere, so the eviction INSERT hits the PK.
            statement.executeUpdate("INSERT INTO evicted_accounts "
                + "(token, old_username, password, regdate, realname, evicted_at, reason) "
                + "VALUES ('squatter-token', 'Elsewhere', 'x', 1, 'Elsewhere', 0, 'premium-rename')");

            assertThrows(Exception.class,
                () -> storage.migratePremiumRename("OldName", "NewName", UUID, "new-token").get());

            assertEquals(UUID, storage.link("OldName").get().orElseThrow().mojangUuid());
            assertEquals("PREMIUM", storage.classification("OldName").get().orElseThrow());
            assertTrue(storage.isRegistered("OldName").get());
            assertEquals("squatter-hash", authmeString(connection, "newname", "password"));
            assertEquals("squatter-token", authmeString(connection, "newname", "account_token"));
            assertEquals("OFFLINE", storage.classification("NewName").get().orElseThrow());
            assertTrue(storage.link("NewName").get().isEmpty());
            assertEquals(1, countEvicted(connection),
                "the forced failure must not add to the archive");
        }
    }

    @Test
    void restoreEvictedBringsBackPasswordAndRegdate() throws Exception {
        Path database = temporaryDirectory.resolve("restore.db");
        try (Storage storage = new Storage(database); Connection connection = open(database)) {
            insertAuthme(connection, "NewName", "squatter-hash", 77L, "NewName", "squatter-token");
            storage.setClassification("NewName", "OFFLINE").get();
            assertEquals(Storage.RenameMigrationResult.COMMITTED,
                storage.migratePremiumRename("OldName", "NewName", UUID, "new-token").get());

            assertEquals(Storage.RestoreResult.RESTORED,
                storage.restoreEvicted("squatter-token", "Returning").get());

            assertEquals("squatter-hash", authmeString(connection, "returning", "password"));
            assertEquals(77L, authmeRegdate(connection, "returning"));
            assertEquals("squatter-token", authmeString(connection, "returning", "account_token"));
            assertEquals(offlineUuid("Returning"), authmeString(connection, "returning", "playerUUID"));
            assertEquals("OFFLINE", storage.classification("Returning").get().orElseThrow());
            assertTrue(storage.findEvicted("squatter-token").get().isEmpty(), "the archive row must be consumed");
        }
    }

    @Test
    void restoreEvictedFailsClosedOnCollisionAndUnknownToken() throws Exception {
        Path database = temporaryDirectory.resolve("restore-collision.db");
        try (Storage storage = new Storage(database); Connection connection = open(database)) {
            insertAuthme(connection, "NewName", "squatter-hash", 77L, "NewName", "squatter-token");
            insertAuthme(connection, "Taken", "taken-hash", 12L, "Taken", "taken-token");
            storage.setClassification("NewName", "OFFLINE").get();
            storage.setClassification("PremiumName", "PREMIUM").get();
            assertEquals(Storage.RenameMigrationResult.COMMITTED,
                storage.migratePremiumRename("OldName", "NewName", UUID, "new-token").get());

            assertEquals(Storage.RestoreResult.COLLISION,
                storage.restoreEvicted("squatter-token", "Taken").get());
            assertEquals(Storage.RestoreResult.COLLISION,
                storage.restoreEvicted("squatter-token", "PremiumName").get());
            assertTrue(storage.findEvicted("squatter-token").get().isPresent(),
                "a collision must keep the archived account intact");
            assertEquals("taken-hash", authmeString(connection, "taken", "password"));

            assertEquals(Storage.RestoreResult.NOT_FOUND,
                storage.restoreEvicted("unknown-token", "FreshName").get());
            assertFalse(storage.isRegistered("FreshName").get());
        }
    }

    @Test
    void registerPlayerAssignsAnAccountToken() throws Exception {
        Path database = temporaryDirectory.resolve("register-token.db");
        try (Storage storage = new Storage(database); Connection connection = open(database)) {
            assertTrue(storage.registerPlayer("Fresh", "hunter2").get());
            String token = authmeString(connection, "fresh", "account_token");
            assertEquals(token, java.util.UUID.fromString(token).toString(),
                "new accounts need a token so a later eviction can be restored");
        }
    }

    @Test
    void upgradeBackfillsAccountTokenAndCreatesArchiveTable() throws Exception {
        Path database = temporaryDirectory.resolve("v3-upgrade.db");
        createLegacyDatabase(database, "PREMIUM");
        try (Connection connection = open(database); Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                CREATE TABLE authme (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    username VARCHAR(255) NOT NULL UNIQUE,
                    password VARCHAR(255) NOT NULL,
                    regdate BIGINT NOT NULL,
                    realname VARCHAR(255) NOT NULL DEFAULT 'Player'
                )
                """);
            statement.executeUpdate("INSERT INTO authme (username, password, regdate, realname) "
                + "VALUES ('oldguy', 'hash', 42, 'OldGuy')");
        }

        String token;
        try (Storage storage = new Storage(database); Connection connection = open(database)) {
            assertTrue(hasColumn(database, "authme", "account_token"));
            assertTrue(hasTable(database, "evicted_accounts"));
            token = authmeString(connection, "oldguy", "account_token");
            assertTrue(token != null && !token.isBlank());
            assertEquals(token, java.util.UUID.fromString(token).toString(),
                "the backfilled token must be a random UUID");
            assertEquals(3, userVersion(database));
        }
        try (Storage second = new Storage(database); Connection connection = open(database)) {
            assertEquals(token, authmeString(connection, "oldguy", "account_token"),
                "a second startup must not overwrite the token");
        }
        assertEquals(1, backups(database).size(), "a second startup must not take another backup");
    }

    private static void insertAuthme(Connection connection, String name, String password, long regdate,
                                     String realname, String token) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO authme (username, password, regdate, realname, playerUUID, account_token)
            VALUES (?, ?, ?, ?, ?, ?)
            """)) {
            statement.setString(1, name.toLowerCase(java.util.Locale.ROOT));
            statement.setString(2, password);
            statement.setLong(3, regdate);
            statement.setString(4, realname);
            statement.setString(5, offlineUuid(name));
            statement.setString(6, token);
            statement.executeUpdate();
        }
    }

    private static String offlineUuid(String name) {
        return java.util.UUID.nameUUIDFromBytes(
            ("OfflinePlayer:" + name).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    }

    private static String authmeString(Connection connection, String name, String column) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT " + column + " FROM authme WHERE username = ? COLLATE NOCASE")) {
            statement.setString(1, name);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getString(1) : null;
            }
        }
    }

    private static long authmeRegdate(Connection connection, String name) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT regdate FROM authme WHERE username = ? COLLATE NOCASE")) {
            statement.setString(1, name);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getLong(1) : -1L;
            }
        }
    }

    private static int countEvicted(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM evicted_accounts")) {
            return result.next() ? result.getInt(1) : 0;
        }
    }

    private static void createLegacyDatabase(Path database, String classification) throws Exception {
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
        }
        try (Connection connection = open(database);
             PreparedStatement insert = connection.prepareStatement("""
                 INSERT INTO links (name_lower, mojang_uuid, canonical_name, linked_at, last_verified_at, source, classification)
                 VALUES (?, ?, ?, ?, ?, ?, ?)
                 """)) {
            insert.setString(1, "notch");
            insert.setString(2, UUID);
            insert.setString(3, "Notch");
            insert.setLong(4, 0);
            insert.setLong(5, 0);
            insert.setString(6, "AUTO");
            insert.setString(7, classification);
            insert.executeUpdate();
        }
    }

    private static List<Path> backups(Path database) throws IOException {
        List<Path> found = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(database.getParent(),
            database.getFileName() + ".bak-*")) {
            for (Path entry : entries) {
                found.add(entry);
            }
        }
        found.sort(null);
        return found;
    }

    private static int userVersion(Path database) throws Exception {
        try (Connection connection = open(database); Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA user_version")) {
            return result.next() ? result.getInt(1) : 0;
        }
    }

    private static Connection open(Path database) throws Exception {
        Class.forName("org.sqlite.JDBC");
        return DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
    }

    private static boolean hasTable(Path database, String table) throws Exception {
        try (Connection connection = open(database);
             PreparedStatement statement = connection.prepareStatement(
                 "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?")) {
            statement.setString(1, table);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
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
