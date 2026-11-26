package com.hizeric.logincore.handshake;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Bounds pending handshakes per IP by distinct normalized player names, not by connection count.
 * Entries are dropped as soon as their last name is released.
 */
public final class PendingHandshakeThrottle {

    private final Map<String, Set<String>> byIp = new HashMap<>();
    private final Object lock = new Object();

    public boolean reserve(String ip, String normalizedName, int limit) {
        synchronized (lock) {
            Set<String> names = byIp.computeIfAbsent(ip, ignored -> new HashSet<>());
            if (names.contains(normalizedName)) {
                return true;
            }
            if (names.size() >= Math.max(1, limit)) {
                return false;
            }
            names.add(normalizedName);
            return true;
        }
    }

    public void release(String ip, String normalizedName) {
        synchronized (lock) {
            Set<String> names = byIp.get(ip);
            if (names == null) {
                return;
            }
            names.remove(normalizedName);
            if (names.isEmpty()) {
                byIp.remove(ip);
            }
        }
    }

    public int count(String ip) {
        synchronized (lock) {
            Set<String> names = byIp.get(ip);
            return names == null ? 0 : names.size();
        }
    }

    public int size() {
        synchronized (lock) {
            return byIp.size();
        }
    }
}
