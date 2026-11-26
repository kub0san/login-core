package com.hizeric.logincore.authme;

import fr.xephi.authme.api.v3.AuthMeApi;
import fr.xephi.authme.api.v3.AuthMePlayer;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

public final class AuthMeBridge {

    private final AuthMeApi authMeApi;

    public AuthMeBridge() {
        this.authMeApi = Objects.requireNonNull(AuthMeApi.getInstance(),
            "AuthMe API is not initialized; ensure AuthMe loads before login-core");
    }

    public boolean isRegistered(String playerName) {
        if (playerName == null || playerName.isBlank()) {
            return false;
        }
        return authMeApi.isRegistered(playerName.toLowerCase(Locale.ROOT));
    }

    public boolean isAuthenticated(Player player) {
        return player != null && authMeApi.isAuthenticated(player);
    }

    public boolean forceLogin(Player player) {
        if (player == null) {
            return false;
        }
        if (authMeApi.isAuthenticated(player)) {
            return false;
        }
        authMeApi.forceLogin(player);
        return true;
    }

    public boolean forceLoginFromProxy(String playerName) {
        if (playerName == null || playerName.isBlank()) {
            return false;
        }
        authMeApi.forceLoginFromProxy(playerName);
        return true;
    }

    public boolean forceRegister(Player player, String password) {
        if (player == null || password == null || password.isBlank()) {
            return false;
        }
        authMeApi.forceRegister(player, password);
        return true;
    }

    public boolean registerPlayer(String playerName, String password) {
        if (playerName == null || playerName.isBlank() || password == null || password.isBlank()) {
            return false;
        }
        if (isRegistered(playerName)) {
            return false;
        }
        return authMeApi.registerPlayer(playerName.toLowerCase(Locale.ROOT), password);
    }

    public List<String> registeredNames() {
        return List.copyOf(authMeApi.getRegisteredNames());
    }

    public String lastIp(String playerName) {
        return authMeApi.getLastIp(playerName);
    }

    public Instant lastLoginTime(String playerName) {
        return authMeApi.getLastLoginTime(playerName);
    }

    /** Local projection of AuthMe data so callers never depend on the vendor API type. */
    public record PlayerInfo(Optional<Instant> lastLogin, Optional<String> lastIp, Instant registeredAt) {
    }

    public Optional<PlayerInfo> playerInfo(String playerName) {
        return authMeApi.getPlayerInfo(playerName).map(info -> new PlayerInfo(
            info.getLastLoginDate(), info.getLastLoginIpAddress(), info.getRegistrationDate()));
    }
}