package com.hizeric.logincore.session;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory authenticated names. Persistent login state stays in Storage. */
public final class AuthState {

    private final Set<String> authenticated = ConcurrentHashMap.newKeySet();

    public void authenticate(String playerName) {
        if (playerName != null && !playerName.isBlank()) {
            authenticated.add(normalize(playerName));
        }
    }

    public boolean isAuthenticated(String playerName) {
        return playerName != null && authenticated.contains(normalize(playerName));
    }

    public void logout(String playerName) {
        if (playerName != null) {
            authenticated.remove(normalize(playerName));
        }
    }

    private static String normalize(String playerName) {
        return playerName.toLowerCase(Locale.ROOT);
    }
}
