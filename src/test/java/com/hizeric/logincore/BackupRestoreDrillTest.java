package com.hizeric.logincore;

import com.hizeric.logincore.storage.Storage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackupRestoreDrillTest {

    private static final String UUID = "069a79f4-44e9-4726-a5be-fca90e38a0e1";

    @TempDir
    Path temporaryDirectory;

    @Test
    void backupCanBeRestoredToPreMigrationState() throws Exception {
        Path database = temporaryDirectory.resolve("backup-restore.db");
        Storage.MigrationReport report = migrateAndBackUp(database);

        assertNotNull(report.backupPath());
        assertTrue(Files.isRegularFile(report.backupPath()));

        restore(database, report.backupPath());

        try (Storage restored = new Storage(database)) {
            assertTrue(restored.classification("notch").get().isEmpty(), "linked name was classified before the backup");
            assertTrue(restored.classification("legacyguy").get().isEmpty(), "exempt name must stay unclassified");
            assertEquals(0, restored.summary().get().classifications());
            assertTrue(restored.link("notch").get().isPresent(), "link survives the restore");
            assertTrue(restored.account("legacyguy").get().exempt(), "exemption survives the restore");
        }
    }

    @Test
    void migrationIsRepeatableAfterRestore() throws Exception {
        Path database = temporaryDirectory.resolve("repeat-after-restore.db");
        Storage.MigrationReport report = migrateAndBackUp(database);
        restore(database, report.backupPath());

        try (Storage restored = new Storage(database)) {
            Storage.MigrationReport first = restored.migrateLegacyClassification().get();
            assertEquals(1, first.classifiedPremium());
            assertEquals(1, first.leftUnclassified());
            assertEquals(0, first.alreadyClassified());

            Storage.MigrationReport second = restored.migrateLegacyClassification().get();
            assertEquals(0, second.classifiedPremium());
            assertEquals(1, second.alreadyClassified());
            assertEquals("PREMIUM", restored.classification("notch").get().orElseThrow());
            assertTrue(restored.classification("legacyguy").get().isEmpty());
        }
    }

    @Test
    void failedBackupAbortsMigration() throws Exception {
        Path database = temporaryDirectory.resolve("failed-backup.db");
        try (Storage storage = new Storage(database)) {
            storage.putLink("NoTcH", UUID, "Notch", "AUTO").get();
            storage.addExempt("LegacyGuy", "legacy account", "operator").get();

            // Redirect the copy source/target to a location that cannot exist, so backup fails
            // before any classification is written.
            Field path = Storage.class.getDeclaredField("databasePath");
            path.setAccessible(true);
            path.set(storage, temporaryDirectory.resolve("no-such-directory").resolve("unwritable.db"));

            ExecutionException failure = assertThrows(ExecutionException.class,
                () -> storage.migrateLegacyClassification().get());
            StringBuilder messages = new StringBuilder();
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                messages.append(cause.getMessage()).append('\n');
            }
            assertTrue(messages.toString().contains("Unable to back up login-core SQLite storage"),
                "the migration must fail at the backup step");

            assertTrue(storage.classification("notch").get().isEmpty());
            assertTrue(storage.classification("legacyguy").get().isEmpty());
            assertEquals(0, storage.summary().get().classifications());
        }
    }

    private Storage.MigrationReport migrateAndBackUp(Path database) throws Exception {
        try (Storage storage = new Storage(database)) {
            storage.putLink("NoTcH", UUID, "Notch", "AUTO").get();
            storage.addExempt("LegacyGuy", "legacy account", "operator").get();
            return storage.migrateLegacyClassification().get();
        }
    }

    private static void restore(Path database, Path backup) throws Exception {
        Path wal = database.resolveSibling(database.getFileName() + "-wal");
        Path shm = database.resolveSibling(database.getFileName() + "-shm");
        Files.deleteIfExists(wal);
        Files.deleteIfExists(shm);
        Files.copy(backup, database, StandardCopyOption.REPLACE_EXISTING);
        Files.deleteIfExists(wal);
        Files.deleteIfExists(shm);
    }
}
