package com.hizeric.logincore.handshake;

import com.hizeric.logincore.decision.LoginDecisionEngine;
import com.hizeric.logincore.mojang.LookupCache;
import com.hizeric.logincore.mojang.LookupResult;
import com.hizeric.logincore.mojang.MojangClient;
import com.hizeric.logincore.mojang.RateBudget;
import com.hizeric.logincore.storage.Storage;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Case 4: a session-server outage is UNKNOWN and never produces an OFFLINE classification. */
class SessionOutageTest {

    private final List<HttpServer> servers = new ArrayList<>();

    @TempDir
    Path temporaryDirectory;

    @AfterEach
    void stopServers() {
        servers.forEach(server -> server.stop(0));
    }

    @Test
    void hasJoinedServerErrorIsUnknown() throws Exception {
        HttpServer server = sessionServer(exchange -> respond(exchange, 503, "service unavailable"));
        MojangClient client = clientFor(server);

        MojangClient.SessionResult result = client.hasJoined("OutageGuy", "server-hash");

        assertEquals(MojangClient.SessionStatus.UNKNOWN, result.status());
        assertEquals("http-status-503", result.reason());
        assertNull(result.uuid());
        assertEquals(1, client.metrics().httpRequests());
        assertEquals(0, client.metrics().rateLimitedResponses(),
            "only 429 counts as rate limited; 503 is a plain outage");
    }

    @Test
    void hasJoinedReadTimeoutIsUnknown() throws Exception {
        // MojangClient's request timeout is 5s; a handler that never answers must fail closed.
        HttpServer server = sessionServer(exchange -> {
            try {
                Thread.sleep(6_000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            respond(exchange, 200, "{}");
        });
        MojangClient client = clientFor(server);

        MojangClient.SessionResult result = client.hasJoined("OutageGuy", "server-hash");

        assertEquals(MojangClient.SessionStatus.UNKNOWN, result.status());
        assertEquals("session-request-failed", result.reason());
    }

    @Test
    void hasJoinedBrokenConnectionIsUnknown() throws Exception {
        HttpServer server = sessionServer(exchange -> exchange.close());
        MojangClient client = clientFor(server);

        MojangClient.SessionResult result = client.hasJoined("OutageGuy", "server-hash");

        assertEquals(MojangClient.SessionStatus.UNKNOWN, result.status());
        assertFalse(result.status() == MojangClient.SessionStatus.VERIFIED);
    }

    @Test
    void unknownSessionNeverWritesOfflineClassification() throws Exception {
        HttpServer server = sessionServer(exchange -> respond(exchange, 503, "service unavailable"));
        MojangClient client = clientFor(server);
        MojangClient.SessionResult session = client.hasJoined("OutageGuy", "server-hash");
        assertEquals(MojangClient.SessionStatus.UNKNOWN, session.status());

        try (Storage storage = new Storage(temporaryDirectory.resolve("outage.db"))) {
            LookupResult outage = LookupResult.unknown(session.reason());
            LoginDecisionEngine.DecisionInput input = new LoginDecisionEngine.DecisionInput(
                "OutageGuy", true, outage, false, null, null, false, false, null);
            LoginDecisionEngine.Decision decision = LoginDecisionEngine.decide(input);

            assertEquals(LoginDecisionEngine.Decision.KICK_VERIFY_FAILED, decision,
                "UNKNOWN without a link must never pass through");
            assertFalse(LoginDecisionEngine.shouldClassifyOffline(decision, outage, false),
                "UNKNOWN must never reach the OFFLINE write");
            // Mirror persistOfflineClassification: it only calls this helper when the gate opens.
            AtomicInteger offlineWrites = new AtomicInteger();
            if (LoginDecisionEngine.shouldClassifyOffline(decision, outage, false)) {
                offlineWrites.incrementAndGet();
                OfflineClassification.write(storage, "OutageGuy", () -> { }, key -> { },
                    message -> { }).join();
            }
            assertEquals(0, offlineWrites.get());
            assertTrue(storage.classification("OutageGuy").get().isEmpty(),
                "a session-server outage must not write OFFLINE");

            // Positive control: an authoritative absence still writes OFFLINE, so the storage
            // assertion above is not vacuous.
            assertTrue(LoginDecisionEngine.shouldClassifyOffline(LoginDecisionEngine.Decision.PASS_THROUGH,
                LookupResult.notPremium(), false));
            OfflineClassification.write(storage, "CrackedGuy", () -> { }, key -> { }, message -> { }).join();
            assertEquals("OFFLINE", storage.classification("CrackedGuy").get().orElseThrow());
            assertEquals(1, storage.summary().get().classifications());
        }
    }

    private HttpServer sessionServer(com.sun.net.httpserver.HttpHandler handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/session", handler);
        server.start();
        servers.add(server);
        return server;
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
        throws IOException {
        byte[] payload = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, payload.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(payload);
        }
    }

    private static MojangClient clientFor(HttpServer server) {
        String base = "http://localhost:" + server.getAddress().getPort();
        return new MojangClient(
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(),
            base + "/lookup/%s",
            null,
            base + "/session",
            new LookupCache(),
            new RateBudget(100, Duration.ofMinutes(10), 10, Duration.ofMinutes(1)));
    }
}
