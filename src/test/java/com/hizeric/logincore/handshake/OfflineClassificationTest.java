package com.hizeric.logincore.handshake;

import com.hizeric.logincore.storage.Storage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OfflineClassificationTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void concurrentPremiumCommitCannotPassThrough() throws Exception {
        try (Storage storage = new Storage(temporaryDirectory.resolve("toctou.db"))) {
            // Classification was read as unclassified before the lookup; a concurrent
            // handshake commits PREMIUM before this write lands.
            storage.setClassification("Notch", "PREMIUM").get();
            AtomicBoolean passedThrough = new AtomicBoolean(false);
            List<String> disconnects = new ArrayList<>();
            List<String> warnings = new ArrayList<>();

            OfflineClassification.write(storage, "Notch",
                () -> passedThrough.set(true), disconnects::add, warnings::add).get();

            assertFalse(passedThrough.get(),
                "a concurrent PREMIUM commit must never let the cracked login through");
            assertEquals(List.of("account-conflict"), disconnects);
            assertEquals("PREMIUM", storage.classification("Notch").get().orElseThrow());
            assertTrue(storage.link("Notch").get().isEmpty(), "no premium link may appear for this connection");
            assertEquals(1, warnings.size());
        }
    }

    @Test
    void unclassifiedOfflineWriteAllowsPassThrough() throws Exception {
        try (Storage storage = new Storage(temporaryDirectory.resolve("offline.db"))) {
            AtomicBoolean passedThrough = new AtomicBoolean(false);
            List<String> disconnects = new ArrayList<>();

            OfflineClassification.write(storage, "OfflineGuy",
                () -> passedThrough.set(true), disconnects::add, message -> { }).get();

            assertTrue(passedThrough.get());
            assertTrue(disconnects.isEmpty());
            assertEquals("OFFLINE", storage.classification("OFFLINEGUY").get().orElseThrow());
        }
    }

    @Test
    void repeatedOfflineLoginStillAllowsPassThrough() throws Exception {
        try (Storage storage = new Storage(temporaryDirectory.resolve("repeat.db"))) {
            storage.setClassification("OfflineGuy", "OFFLINE").get();
            AtomicBoolean passedThrough = new AtomicBoolean(false);
            List<String> disconnects = new ArrayList<>();

            OfflineClassification.write(storage, "offlineguy",
                () -> passedThrough.set(true), disconnects::add, message -> { }).get();

            assertTrue(passedThrough.get(), "ALREADY_SAME is not a conflict");
            assertTrue(disconnects.isEmpty());
        }
    }
}
