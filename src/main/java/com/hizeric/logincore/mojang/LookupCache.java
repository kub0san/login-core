package com.hizeric.logincore.mojang;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public final class LookupCache {

    private static final int MAX_CACHE_ENTRIES = 10_000;
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<LookupResult>> inFlight = new ConcurrentHashMap<>();

    public Optional<LookupResult> get(String normalizedName) {
        CacheEntry entry = cache.get(normalizedName);
        if (entry == null) {
            return Optional.empty();
        }
        if (entry.expiresAt().isBefore(Instant.now())) {
            cache.remove(normalizedName, entry);
            return Optional.empty();
        }
        return Optional.of(entry.value());
    }

    public CompletableFuture<LookupResult> getInFlight(String normalizedName) {
        return inFlight.get(normalizedName);
    }

    public CompletableFuture<LookupResult> putIfAbsentInFlight(String normalizedName, CompletableFuture<LookupResult> future) {
        return inFlight.putIfAbsent(normalizedName, future);
    }

    public void removeInFlight(String normalizedName) {
        inFlight.remove(normalizedName);
    }

    public void put(String normalizedName, LookupResult result, Duration ttl) {
        if (normalizedName == null || normalizedName.isBlank()) {
            return;
        }
        if (!cache.containsKey(normalizedName) && cache.size() >= MAX_CACHE_ENTRIES) {
            Instant now = Instant.now();
            cache.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(now));
            if (cache.size() >= MAX_CACHE_ENTRIES) {
                String firstKey = cache.keySet().stream().findFirst().orElse(null);
                if (firstKey != null) {
                    cache.remove(firstKey);
                }
            }
        }
        cache.put(normalizedName, new CacheEntry(result, Instant.now().plus(ttl)));
    }

    public int size() {
        return cache.size();
    }

    private record CacheEntry(LookupResult value, Instant expiresAt) {
    }
}
