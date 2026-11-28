package com.hizeric.logincore.session;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

/** Client-side account token cookie: a best-effort identity hint for post-join restore. */
public final class AccountCookie {

    private final Plugin plugin;

    public AccountCookie(Plugin plugin) {
        this.plugin = plugin;
    }

    public static NamespacedKey key(Plugin plugin) {
        return new NamespacedKey(plugin, "acct");
    }

    public void store(Player player, String token) {
        player.storeCookie(key(plugin), encode(token));
    }

    public CompletableFuture<String> read(Player player) {
        return player.retrieveCookie(key(plugin)).thenApply(AccountCookie::decode);
    }

    public static byte[] encode(String token) {
        return token == null ? null : token.getBytes(StandardCharsets.UTF_8);
    }

    public static String decode(byte[] bytes) {
        return bytes == null || bytes.length == 0 ? null : new String(bytes, StandardCharsets.UTF_8);
    }
}
