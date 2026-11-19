package org.authmePAL;

import com.sun.net.httpserver.HttpServer;
import org.authmePAL.mojang.LookupCache;
import org.authmePAL.mojang.LookupResult;
import org.authmePAL.mojang.MojangClient;
import org.authmePAL.mojang.RateBudget;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class MojangClientTest {

    private final List<HttpServer> servers = new ArrayList<>();

    @AfterEach
    void stopServers() {
        servers.forEach(server -> server.stop(0));
    }

    @Test
    void lookupNameClassifiesPremiumUsers() throws Exception {
        HttpServer server = createServer(200, "{\"name\":\"Notch\",\"id\":\"069a79f444e94726a5befca90e38a0e1\"}");
        {
            MojangClient client = new MojangClient(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build(),
                "http://localhost:" + server.getAddress().getPort() + "/lookup/%s",
                null,
                new LookupCache(),
                new RateBudget(100, Duration.ofMinutes(10), 10, Duration.ofMinutes(1))
            );

            LookupResult result = client.lookupName("Notch");

            assertEquals(LookupResult.Type.PREMIUM, result.type());
            assertEquals(UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38a0e1"), result.mojangUuid());
            assertEquals("Notch", result.canonicalName());
        }
    }

    @Test
    void lookupNameClassifiesNotPremiumResponses() throws Exception {
        HttpServer server = createServer(404, "not found");
        {
            MojangClient client = new MojangClient(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build(),
                "http://localhost:" + server.getAddress().getPort() + "/lookup/%s",
                null,
                new LookupCache(),
                new RateBudget(100, Duration.ofMinutes(10), 10, Duration.ofMinutes(1))
            );

            LookupResult result = client.lookupName("someoneelse");

            assertEquals(LookupResult.Type.NOT_PREMIUM, result.type());
            assertTrue(result.isNotPremium());
        }
    }

    @Test
    void lookupNameClassifiesHttpErrorsAsUnknown() throws Exception {
        HttpServer server = createServer(429, "too many requests");
        {
            MojangClient client = new MojangClient(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build(),
                "http://localhost:" + server.getAddress().getPort() + "/lookup/%s",
                null,
                new LookupCache(),
                new RateBudget(100, Duration.ofMinutes(10), 10, Duration.ofMinutes(1))
            );

            LookupResult result = client.lookupName("rate-limited");

            assertEquals(LookupResult.Type.UNKNOWN, result.type());
            assertFalse(result.isPremium());
        }
    }

    @Test
    void hasJoinedParsesVerifiedProfile() throws Exception {
        String body = "{\"name\":\"Notch\",\"id\":\"069a79f444e94726a5befca90e38a0e1\"}";
        HttpServer server = createServer("/session", 200, body);
        {
            MojangClient client = clientFor(server, "/lookup/%s", null,
                "http://localhost:" + server.getAddress().getPort() + "/session");

            MojangClient.SessionResult result = client.hasJoined("Notch", "server-hash");

            assertEquals(MojangClient.SessionStatus.VERIFIED, result.status());
            assertEquals(UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38a0e1"), result.uuid());
            assertEquals("Notch", result.canonicalName());
        }
    }

    @Test
    void hasJoinedClassifiesNoSessionAsNotVerified() throws Exception {
        HttpServer server = createServer("/session", 204, "");
        {
            MojangClient client = clientFor(server, "/lookup/%s", null,
                "http://localhost:" + server.getAddress().getPort() + "/session");

            MojangClient.SessionResult result = client.hasJoined("Notch", "server-hash");

            assertEquals(MojangClient.SessionStatus.NOT_VERIFIED, result.status());
        }
    }

    @Test
    void concurrentNameLookupsShareOneHttpRequestAndThenHitCache() throws Exception {
        AtomicInteger requestCount = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/lookup", exchange -> {
            requestCount.incrementAndGet();
            try {
                Thread.sleep(100);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            byte[] payload = "{\"name\":\"Notch\",\"id\":\"069a79f444e94726a5befca90e38a0e1\"}"
                .getBytes();
            exchange.sendResponseHeaders(200, payload.length);
            try (OutputStream outputStream = exchange.getResponseBody()) {
                outputStream.write(payload);
            }
        });
        server.start();
        ExecutorService callers = Executors.newFixedThreadPool(12);
        try {
            MojangClient client = clientFor(server, "/lookup/%s", null,
                "https://session.invalid/hasJoined");
            CompletableFuture<?>[] lookups = new CompletableFuture<?>[12];
            for (int i = 0; i < lookups.length; i++) {
                lookups[i] = CompletableFuture.supplyAsync(() -> client.lookupName("Notch"), callers);
            }
            CompletableFuture.allOf(lookups).get(10, TimeUnit.SECONDS);
            client.lookupName("Notch");

            assertEquals(1, requestCount.get());
            assertEquals(1, client.metrics().cacheHits());
        } finally {
            callers.shutdownNow();
            server.stop(0);
        }
    }

    @Test
    void rateBudgetStopsLookupsAfterLimit() {
        RateBudget rateBudget = new RateBudget(2, Duration.ofMinutes(10), 1, Duration.ofMinutes(1));

        assertTrue(rateBudget.tryConsumeGlobal());
        assertTrue(rateBudget.tryConsumeGlobal());
        assertFalse(rateBudget.tryConsumeGlobal());

        assertTrue(rateBudget.tryConsumeForIp("127.0.0.1"));
        assertFalse(rateBudget.tryConsumeForIp("127.0.0.1"));
    }

    private HttpServer createServer(int statusCode, String body) throws IOException {
        return createServer("/lookup", statusCode, body);
    }

    private HttpServer createServer(String path, int statusCode, String body) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext(path, exchange -> {
            byte[] payload = body.getBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(statusCode, statusCode == 204 ? -1 : payload.length);
            try (OutputStream outputStream = exchange.getResponseBody()) {
                outputStream.write(payload);
            }
        });
        server.start();
        servers.add(server);
        return server;
    }

    private MojangClient clientFor(HttpServer server, String primaryPath, String fallbackPath, String sessionUrl) {
        String base = "http://localhost:" + server.getAddress().getPort();
        String resolvedSessionUrl = sessionUrl.startsWith("/") ? base + sessionUrl : sessionUrl;
        return new MojangClient(
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build(),
            base + primaryPath,
            fallbackPath == null ? null : base + fallbackPath,
            resolvedSessionUrl,
            new LookupCache(),
            new RateBudget(100, Duration.ofMinutes(10), 10, Duration.ofMinutes(1))
        );
    }
}
