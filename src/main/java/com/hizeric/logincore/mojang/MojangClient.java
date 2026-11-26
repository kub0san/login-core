package com.hizeric.logincore.mojang;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.LongAdder;

public final class MojangClient {

    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration POSITIVE_TTL = Duration.ofMinutes(60);
    private static final Duration NEGATIVE_TTL = Duration.ofMinutes(10);
    private static final Duration UNKNOWN_TTL = Duration.ofSeconds(30);

    private final HttpClient httpClient;
    private final String primaryLookupUrl;
    private final String fallbackLookupUrl;
    private final String sessionUrl;
    private final LookupCache lookupCache;
    private final RateBudget rateBudget;
    private final LongAdder lookupCalls = new LongAdder();
    private final LongAdder cacheHits = new LongAdder();
    private final LongAdder httpRequests = new LongAdder();
    private final LongAdder rateLimitedResponses = new LongAdder();

    public MojangClient() {
        this(
            HttpClient.newBuilder()
                .connectTimeout(DEFAULT_CONNECT_TIMEOUT)
                .build(),
            "https://api.minecraftservices.com/minecraft/profile/lookup/name/%s",
            "https://api.mojang.com/users/profiles/minecraft/%s",
            "https://sessionserver.mojang.com/session/minecraft/hasJoined",
            new LookupCache(),
            new RateBudget(400, Duration.ofMinutes(10), 5, Duration.ofMinutes(1))
        );
    }

    public MojangClient(HttpClient httpClient, String primaryLookupUrl, String fallbackLookupUrl, LookupCache lookupCache, RateBudget rateBudget) {
        this(httpClient, primaryLookupUrl, fallbackLookupUrl, "https://sessionserver.mojang.com/session/minecraft/hasJoined",
            lookupCache, rateBudget);
    }

    public MojangClient(HttpClient httpClient, String primaryLookupUrl, String fallbackLookupUrl, String sessionUrl,
                        LookupCache lookupCache, RateBudget rateBudget) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.primaryLookupUrl = Objects.requireNonNull(primaryLookupUrl, "primaryLookupUrl");
        this.fallbackLookupUrl = fallbackLookupUrl;
        this.sessionUrl = Objects.requireNonNull(sessionUrl, "sessionUrl");
        this.lookupCache = Objects.requireNonNull(lookupCache, "lookupCache");
        this.rateBudget = Objects.requireNonNull(rateBudget, "rateBudget");
    }

    public LookupResult lookupName(String inputName) {
        return lookupName(inputName, null);
    }

    public LookupResult lookupName(String inputName, String remoteIp) {
        lookupCalls.increment();
        String normalizedName = normalizeName(inputName);
        if (normalizedName.isBlank()) {
            return LookupResult.unknown("invalid-name");
        }

        Optional<LookupResult> cached = lookupCache.get(normalizedName);
        if (cached.isPresent()) {
            cacheHits.increment();
            return cached.get();
        }

        CompletableFuture<LookupResult> existingFlight = lookupCache.getInFlight(normalizedName);
        if (existingFlight != null) {
            return existingFlight.join();
        }

        CompletableFuture<LookupResult> inFlight = new CompletableFuture<>();
        CompletableFuture<LookupResult> other = lookupCache.putIfAbsentInFlight(normalizedName, inFlight);
        if (other != null) {
            return other.join();
        }

        try {
            if (remoteIp != null && !rateBudget.tryConsumeForIp(remoteIp)) {
                LookupResult result = LookupResult.unknown("ip-budget-exhausted");
                lookupCache.put(normalizedName, result, UNKNOWN_TTL);
                inFlight.complete(result);
                return result;
            }
            LookupResult result = performLookup(normalizedName);
            lookupCache.put(normalizedName, result, ttlFor(result));
            inFlight.complete(result);
            return result;
        } catch (RuntimeException e) {
            LookupResult result = LookupResult.unknown("lookup-error:" + e.getClass().getSimpleName());
            lookupCache.put(normalizedName, result, UNKNOWN_TTL);
            inFlight.complete(result);
            return result;
        } finally {
            lookupCache.removeInFlight(normalizedName);
        }
    }

    public CompletionStage<LookupResult> lookupNameAsync(String name, String remoteIp, Executor executor) {
        return CompletableFuture.supplyAsync(() -> lookupName(name, remoteIp), executor);
    }

    public CompletionStage<SessionResult> hasJoinedAsync(String name, String serverHash, Executor executor) {
        return CompletableFuture.supplyAsync(() -> hasJoined(name, serverHash), executor);
    }

    public SessionResult hasJoined(String name, String serverHash) {
        if (name == null || name.isBlank() || serverHash == null || serverHash.isBlank()) {
            return SessionResult.unknown("invalid-request");
        }
        if (!rateBudget.tryConsumeGlobal()) {
            return SessionResult.unknown("budget-exhausted");
        }
        httpRequests.increment();

        String separator = sessionUrl.contains("?") ? "&" : "?";
        String url = sessionUrl + separator + "username=" + encodeQuery(name) + "&serverId=" + encodeQuery(serverHash);
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(url))
                .timeout(DEFAULT_REQUEST_TIMEOUT)
                .GET()
                .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 204) {
                return SessionResult.notVerified();
            }
            if (response.statusCode() != 200) {
                if (response.statusCode() == 429) {
                    rateLimitedResponses.increment();
                }
                return SessionResult.unknown("http-status-" + response.statusCode());
            }
            return parseSession(response.body());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return SessionResult.unknown("interrupted");
        } catch (IOException | IllegalArgumentException ex) {
            return SessionResult.unknown("session-request-failed");
        }
    }

    private LookupResult performLookup(String normalizedName) {
        LookupResult primaryResult = requestLookup(primaryLookupUrl, normalizedName);
        if (primaryResult.isPremium() || primaryResult.isNotPremium()) {
            return primaryResult;
        }
        if (fallbackLookupUrl == null || fallbackLookupUrl.isBlank()) {
            return primaryResult;
        }
        return requestLookup(fallbackLookupUrl, normalizedName);
    }

    private LookupResult requestLookup(String template, String normalizedName) {
        if (!rateBudget.tryConsumeGlobal()) {
            return LookupResult.unknown("budget-exhausted");
        }
        httpRequests.increment();

        String resolvedUrl = template.formatted(encodePath(normalizedName));
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(resolvedUrl))
                .timeout(DEFAULT_REQUEST_TIMEOUT)
                .GET()
                .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int statusCode = response.statusCode();
            String body = response.body();

            // 404/204 = NOT_PREMIUM; 403/429/5xx/timeout/malformed = UNKNOWN
            if (statusCode == 200) {
                Optional<UUID> uuid = parseUuid(body);
                if (uuid.isPresent()) {
                    return LookupResult.premium(uuid.get(), parseCanonicalName(body).orElse(normalizedName));
                }
                return LookupResult.unknown("malformed-200");
            }
            if (statusCode == 404 || statusCode == 204) {
                return LookupResult.notPremium();
            }
            if (statusCode == 429) {
                rateLimitedResponses.increment();
            }
            return LookupResult.unknown("http-status-" + statusCode);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return LookupResult.unknown("interrupted");
        } catch (IOException | IllegalArgumentException ex) {
            return LookupResult.unknown("lookup-request-failed");
        }
    }

    private SessionResult parseSession(String body) {
        try {
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            JsonElement idElement = json.get("id");
            JsonElement nameElement = json.get("name");
            if (idElement == null || nameElement == null || idElement.isJsonNull() || nameElement.isJsonNull()) {
                return SessionResult.unknown("malformed-session-response");
            }
            UUID uuid = parseProfileUuid(idElement.getAsString());
            String canonicalName = nameElement.getAsString();
            return SessionResult.verified(uuid, canonicalName);
        } catch (RuntimeException ex) {
            return SessionResult.unknown("malformed-session-response");
        }
    }

    private Optional<UUID> parseUuid(String body) {
        try {
            JsonObject jsonObject = JsonParser.parseString(body).getAsJsonObject();
            JsonElement uuidElement = jsonObject.get("id");
            if (uuidElement == null || uuidElement.isJsonNull()) {
                return Optional.empty();
            }
            return Optional.of(parseProfileUuid(uuidElement.getAsString()));
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
    }

    private Optional<String> parseCanonicalName(String body) {
        try {
            JsonObject jsonObject = JsonParser.parseString(body).getAsJsonObject();
            JsonElement nameElement = jsonObject.get("name");
            if (nameElement != null && !nameElement.isJsonNull()) {
                return Optional.of(nameElement.getAsString());
            }
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    private UUID parseProfileUuid(String profileId) {
        String normalized = profileId.replace("-", "");
        if (!normalized.matches("[0-9a-fA-F]{32}")) {
            return UUID.fromString(profileId);
        }
        return UUID.fromString(normalized.substring(0, 8) + "-" + normalized.substring(8, 12) + "-"
            + normalized.substring(12, 16) + "-" + normalized.substring(16, 20) + "-" + normalized.substring(20));
    }

    private String encodePath(String value) {
        return value.replace("%", "%25").replace(" ", "%20").replace("/", "%2F").replace("?", "%3F");
    }

    private String encodeQuery(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    public record SessionResult(SessionStatus status, UUID uuid, String canonicalName, String reason) {
        public static SessionResult verified(UUID uuid, String canonicalName) {
            return new SessionResult(SessionStatus.VERIFIED, uuid, canonicalName, null);
        }

        public static SessionResult notVerified() {
            return new SessionResult(SessionStatus.NOT_VERIFIED, null, null, "not-verified");
        }

        public static SessionResult unknown(String reason) {
            return new SessionResult(SessionStatus.UNKNOWN, null, null, reason);
        }
    }

    public enum SessionStatus {
        VERIFIED,
        NOT_VERIFIED,
        UNKNOWN
    }

    public Metrics metrics() {
        return new Metrics(lookupCalls.sum(), cacheHits.sum(), httpRequests.sum(), rateLimitedResponses.sum());
    }

    public record Metrics(long lookupCalls, long cacheHits, long httpRequests, long rateLimitedResponses) {
    }

    private Duration ttlFor(LookupResult result) {
        if (result.isPremium()) {
            return POSITIVE_TTL;
        }
        if (result.isNotPremium()) {
            return NEGATIVE_TTL;
        }
        return UNKNOWN_TTL;
    }

    private String normalizeName(String inputName) {
        if (inputName == null) {
            return "";
        }
        return inputName.trim().toLowerCase(Locale.ROOT);
    }
}
