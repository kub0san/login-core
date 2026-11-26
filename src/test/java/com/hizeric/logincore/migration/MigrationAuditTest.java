package com.hizeric.logincore.migration;

import com.hizeric.logincore.storage.Storage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MigrationAuditTest {

    private static final String UUID = "069a79f4-44e9-4726-a5be-fca90e38a0e1";

    @TempDir
    Path temporaryDirectory;

    @Test
    void migrateApplySkipsPremiumNames() throws Exception {
        Path dataFolder = temporaryDirectory.resolve("data");
        Path migration = dataFolder.resolve("migration");
        Files.createDirectories(migration);
        Files.writeString(migration.resolve("current-audit.txt"), "audit.csv", StandardCharsets.UTF_8);
        Files.writeString(migration.resolve("audit.csv"),
            "name,status,mojang_uuid,last_login,last_ip,registered_at\n"
                + "\"Notch\",PREMIUM_NAME,\"" + UUID + "\",,,\n"
                + "\"OfflineGuy\",PREMIUM_NAME,\"" + UUID + "\",,,\n", StandardCharsets.UTF_8);

        try (Storage storage = new Storage(temporaryDirectory.resolve("apply.db"))) {
            storage.setClassification("Notch", "PREMIUM").get();
            MigrationAudit audit = new MigrationAudit(dataFolder, storage);
            try {
                MigrationAudit.ExemptionReport report =
                    audit.applyPremiumNameExemptions(Set.of(), "operator").get();

                assertEquals(1, report.added());
                assertEquals(1, report.skippedPremium());
                assertFalse(storage.account("notch").get().exempt(),
                    "a PREMIUM-classified conflict must never get an exemption row");
                assertTrue(storage.account("offlineguy").get().exempt());
            } finally {
                audit.close();
            }
        }
    }
}
