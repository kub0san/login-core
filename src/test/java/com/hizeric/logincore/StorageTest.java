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
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
            statement.execute("PRAGMA user_version = 2");
        }

        SQLException failure = assertThrows(SQLException.class, () -> new Storage(database));
        StringBuilder messages = new StringBuilder();
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            messages.append(cause.getMessage()).append('\n');
        }
        assertTrue(messages.toString().contains("user_version 2"));
        assertTrue(messages.toString().contains("newer login-core"));
    }

    @Test
    void startupCreatesABackupBeforeDdl() throws Exception {
        Path database = temporaryDirectory.resolve("startup-backup.db");
        createLegacyDatabase(database, "PREMIUM");

        try (Storage storage = new Storage(database)) {
            assertEquals("PREMIUM", storage.classification("notch").get().orElseThrow());
            assertFalse(hasColumn(database, "links", "classification"));
            assertEquals(1, userVersion(database));
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
        assertEquals(1, userVersion(database));
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
        assertEquals(1, userVersion(database));
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
    void legacyPremiumLinkIsClassifiedPremium() throws Exception {
        Path database = temporaryDirectory.resolve("migration-premium.db");
        try (Storage storage = new Storage(database)) {
            storage.putLink("NoTcH", UUID, "Notch", "AUTO").get();

            Storage.MigrationReport report = storage.migrateLegacyClassification().get();

            assertEquals(1, report.classifiedPremium());
            assertEquals(0, report.leftUnclassified());
            assertEquals(0, report.alreadyClassified());
            assertEquals("PREMIUM", storage.classification("notch").get().orElseThrow());
        }
    }

    @Test
    void legacyAccountWithoutLinkStaysUnclassified() throws Exception {
        Path database = temporaryDirectory.resolve("migration-unclassified.db");
        try (Storage storage = new Storage(database); Connection connection = open(database)) {
            storage.addExempt("LegacyGuy", "legacy account", "operator").get();

            Storage.MigrationReport report = storage.migrateLegacyClassification().get();

            assertEquals(0, report.classifiedPremium());
            assertEquals(1, report.leftUnclassified());
            assertTrue(storage.classification("legacyguy").get().isEmpty());
            assertNull(classification(connection, "legacyguy"));
        }
    }

    @Test
    void migrationIsIdempotent() throws Exception {
        Path database = temporaryDirectory.resolve("migration-idempotent.db");
        try (Storage storage = new Storage(database)) {
            storage.putLink("NoTcH", UUID, "Notch", "AUTO").get();

            Storage.MigrationReport first = storage.migrateLegacyClassification().get();
            Storage.MigrationReport second = storage.migrateLegacyClassification().get();

            assertEquals(1, first.classifiedPremium());
            assertEquals(0, second.classifiedPremium());
            assertEquals(1, second.alreadyClassified());
            assertEquals("PREMIUM", storage.classification("notch").get().orElseThrow());
        }
    }

    @Test
    void migrationWritesABackup() throws Exception {
        Path database = temporaryDirectory.resolve("migration-backup.db");
        try (Storage storage = new Storage(database)) {
            storage.putLink("NoTcH", UUID, "Notch", "AUTO").get();

            Storage.MigrationReport report = storage.migrateLegacyClassification().get();

            assertNotNull(report.backupPath());
            assertTrue(Files.isRegularFile(report.backupPath()));
            assertTrue(report.backupPath().getFileName().toString().matches("migration-backup\\.db\\.bak-\\d{8}-\\d{6}"));
            try (Connection backup = open(report.backupPath());
                 Statement statement = backup.createStatement();
                 ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM account_classification")) {
                assertTrue(result.next());
                assertEquals(0, result.getInt(1), "backup is taken before the migration writes");
            }
        }
    }

    @Test
    void migrationNeverWritesOffline() throws Exception {
        Path database = temporaryDirectory.resolve("migration-no-offline.db");
        try (Storage storage = new Storage(database);
             Connection connection = open(database);
             Statement statement = connection.createStatement()) {
            storage.putLink("NoTcH", UUID, "Notch", "AUTO").get();
            storage.addExempt("LegacyGuy", "legacy account", "operator").get();

            storage.migrateLegacyClassification().get();

            try (ResultSet result = statement.executeQuery(
                "SELECT COUNT(*) FROM account_classification WHERE classification = 'OFFLINE'")) {
                assertTrue(result.next());
                assertEquals(0, result.getInt(1));
            }
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
