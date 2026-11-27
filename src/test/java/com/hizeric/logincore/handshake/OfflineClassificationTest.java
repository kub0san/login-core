package com.hizeric.logincore.handshake;

import com.hizeric.logincore.decision.LoginDecisionEngine;
import com.hizeric.logincore.mojang.LookupCache;
import com.hizeric.logincore.mojang.LookupResult;
import com.hizeric.logincore.mojang.MojangClient;
import com.hizeric.logincore.mojang.RateBudget;
import com.hizeric.logincore.storage.Storage;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
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

    @Test
    void confirmedAbsenceLookupPersistsOfflineClassification() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/lookup", exchange -> {
            byte[] payload = "not found".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(404, payload.length);
            try (OutputStream body = exchange.getResponseBody()) {
                body.write(payload);
            }
        });
        server.start();
        try {
            MojangClient client = new MojangClient(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build(),
                "http://localhost:" + server.getAddress().getPort() + "/lookup/%s",
                null,
                new LookupCache(),
                new RateBudget(100, Duration.ofMinutes(10), 10, Duration.ofMinutes(1)));

            LookupResult lookup = client.lookupName("OfflineGuy", "127.0.0.1");
            assertTrue(lookup.isNotPremium());
            assertFalse(lookup.isPremium());

            LoginDecisionEngine.Decision decision = LoginDecisionEngine.decide(
                new LoginDecisionEngine.DecisionInput("OfflineGuy", true, lookup,
                    false, null, null, false, false, null));
            assertEquals(LoginDecisionEngine.Decision.PASS_THROUGH, decision);
            assertTrue(LoginDecisionEngine.shouldClassifyOffline(decision, lookup, false));

            try (Storage storage = new Storage(temporaryDirectory.resolve("confirmed-absence.db"))) {
                AtomicBoolean passedThrough = new AtomicBoolean(false);
                List<String> disconnects = new ArrayList<>();
                List<String> warnings = new ArrayList<>();

                OfflineClassification.write(storage, "OfflineGuy",
                    () -> passedThrough.set(true), disconnects::add, warnings::add).get();

                assertTrue(passedThrough.get());
                assertTrue(disconnects.isEmpty(), disconnects.toString());
                assertTrue(warnings.isEmpty(), warnings.toString());
                assertEquals("OFFLINE", storage.classification("OFFLINEGUY").get().orElseThrow());
                assertTrue(storage.link("OfflineGuy").get().isEmpty());
                assertEquals(1, storage.summary().get().classifications());
            }
        } finally {
            server.stop(0);
        }
    }
}
