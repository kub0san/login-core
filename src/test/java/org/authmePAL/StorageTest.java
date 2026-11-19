package org.authmePAL;

import org.authmePAL.storage.Storage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StorageTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void persistsNormalizedLinksAndExemptNames() throws Exception {
        try (Storage storage = new Storage(temporaryDirectory.resolve("accounts.db"))) {
            storage.putLink("NoTcH", "069a79f4-44e9-4726-a5be-fca90e38a0e1", "Notch", "AUTO").get();
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
}
