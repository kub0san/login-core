package org.authmePAL.mojang;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

public final class RateBudget {

    private static final int MAX_TRACKED_IPS = 10_000;
    private final int globalLimit;
    private final Duration globalWindow;
    private final int perIpLimit;
    private final Duration perIpWindow;
    private final Deque<Long> globalTimestamps = new ArrayDeque<>();
    private final Map<String, Deque<Long>> perIpTimestamps = new HashMap<>();
    private final Object lock = new Object();

    public RateBudget(int globalLimit, Duration globalWindow, int perIpLimit, Duration perIpWindow) {
        if (globalLimit <= 0) {
            throw new IllegalArgumentException("globalLimit must be positive");
        }
        if (perIpLimit <= 0) {
            throw new IllegalArgumentException("perIpLimit must be positive");
        }
        this.globalLimit = globalLimit;
        this.globalWindow = globalWindow;
        this.perIpLimit = perIpLimit;
        this.perIpWindow = perIpWindow;
    }

    public boolean tryConsumeGlobal() {
        synchronized (lock) {
            prune(globalTimestamps, globalWindow);
            if (globalTimestamps.size() >= globalLimit) {
                return false;
            }
            globalTimestamps.addLast(Instant.now().toEpochMilli());
            return true;
        }
    }

    public boolean tryConsumeForIp(String ip) {
        if (ip == null || ip.isBlank()) {
            return true;
        }
        synchronized (lock) {
            if (!perIpTimestamps.containsKey(ip) && perIpTimestamps.size() >= MAX_TRACKED_IPS) {
                perIpTimestamps.entrySet().removeIf(entry -> {
                    prune(entry.getValue(), perIpWindow);
                    return entry.getValue().isEmpty();
                });
                if (perIpTimestamps.size() >= MAX_TRACKED_IPS) {
                    return false;
                }
            }
            Deque<Long> timestamps = perIpTimestamps.computeIfAbsent(ip, ignored -> new ArrayDeque<>());
            prune(timestamps, perIpWindow);
            if (timestamps.size() >= perIpLimit) {
                return false;
            }
            timestamps.addLast(Instant.now().toEpochMilli());
            return true;
        }
    }

    private void prune(Deque<Long> timestamps, Duration window) {
        long cutoff = Instant.now().minusMillis(window.toMillis()).toEpochMilli();
        while (!timestamps.isEmpty() && timestamps.peekFirst() <= cutoff) {
            timestamps.removeFirst();
        }
    }
}
