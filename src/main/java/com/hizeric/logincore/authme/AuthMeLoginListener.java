package com.hizeric.logincore.authme;

import fr.xephi.authme.events.LoginEvent;
import fr.xephi.authme.events.RestoreSessionEvent;
import com.hizeric.logincore.session.VerifiedSessions;
import com.hizeric.logincore.session.VerifiedSessions.VerifiedSession;
import com.hizeric.logincore.storage.Storage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.plugin.Plugin;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;

public final class AuthMeLoginListener implements Listener {

    private final Plugin plugin;
    private final AuthMeBridge authMeBridge;
    private final Storage storage;
    private final VerifiedSessions verifiedSessions;
    private final Consumer<String> onVerifiedLogin;
    private final SecureRandom secureRandom = new SecureRandom();

    public AuthMeLoginListener(Plugin plugin, AuthMeBridge authMeBridge, Storage storage,
                               VerifiedSessions verifiedSessions, Consumer<String> onVerifiedLogin) {
        this.plugin = plugin;
        this.authMeBridge = authMeBridge;
        this.storage = storage;
        this.verifiedSessions = verifiedSessions;
        this.onVerifiedLogin = onVerifiedLogin;
    }

    /**
     * Applies the verified Mojang profile as early as possible.
     * <p>
     * LuckPerms loads (and caches) permission data from {@link AsyncPlayerPreLoginEvent} at
     * {@link EventPriority#LOW}, keyed by {@link AsyncPlayerPreLoginEvent#getUniqueId()}, and then
     * looks the user up again by {@link org.bukkit.entity.Player#getUniqueId()} in
     * {@link org.bukkit.event.player.PlayerLoginEvent}. Paper's event returns the UUID of the
     * mutable player profile, so the profile must be replaced before LuckPerms runs, otherwise
     * permissions are pre-loaded under the offline UUID while the player joins with the Mojang
     * UUID and LuckPerms denies the login ("data was not loaded during the pre-login stage").
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onAsyncPreLoginProfile(AsyncPlayerPreLoginEvent event) {
        Optional<VerifiedSession> pending = verifiedSessions.find(event.getName(), event.getAddress());
        if (pending.isEmpty()) {
            return;
        }
        VerifiedSession session = pending.get();
        session.playerUuid(plugin.getConfig().getBoolean("use-premium-uuid", true))
            .ifPresent(uuid -> event.setPlayerProfile(Bukkit.createProfile(
                uuid, session.mojangSession().canonicalName())));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAsyncPreLogin(AsyncPlayerPreLoginEvent event) {
        Optional<VerifiedSession> pending = verifiedSessions.find(event.getName(), event.getAddress());
        if (pending.isEmpty()) {
            return;
        }
        VerifiedSession session = pending.get();
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            verifiedSessions.remove(session.connectionId());
            return;
        }
        if (session.requiresPasswordOnce()) {
            if (!authMeBridge.isRegistered(event.getName())) {
                event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    "Your AuthMe account changed during login. Please reconnect.");
                verifiedSessions.remove(session.connectionId());
            }
            return;
        }

        try {
            if (session.shouldRegister()) {
                if (authMeBridge.isRegistered(event.getName())) {
                    event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                        "An account with this name already exists. Please reconnect or contact an administrator.");
                    verifiedSessions.remove(session.connectionId());
                    return;
                }
                String randomPassword = randomPassword();
                if (!authMeBridge.registerPlayer(event.getName(), randomPassword)) {
                    event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                        "Your account could not be prepared. Please try again or contact an administrator.");
                    verifiedSessions.remove(session.connectionId());
                    return;
                }
            }
            if (session.autoLogin()) {
                authMeBridge.forceLoginFromProxy(event.getName());
            }
        } catch (RuntimeException ex) {
            plugin.getLogger().severe("AuthMe rejected the verified login for " + event.getName() + ": " + ex.getMessage());
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                "Authentication could not be completed. Please try again later.");
            verifiedSessions.remove(session.connectionId());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSessionRestore(RestoreSessionEvent event) {
        Player player = event.getPlayer();
        if (player != null && verifiedSessions.find(player.getName(), player.getAddress()).isPresent()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAuthMeLogin(LoginEvent event) {
        Player player = event.getPlayer();
        if (player == null) {
            return;
        }
        Optional<VerifiedSession> pending = verifiedSessions.consume(player.getName(), player.getAddress());
        if (pending.isEmpty()) {
            return;
        }
        VerifiedSession session = pending.get();
        onVerifiedLogin.accept(session.connectionId());
        boolean shouldLink = session.link().isEmpty()
            && (session.shouldRegister() || session.requiresPasswordOnce() || session.autoLogin());
        if (!shouldLink) {
            return;
        }
        storage.putLink(session.name(), session.mojangSession().uuid().toString(),
            session.mojangSession().canonicalName(), session.linkSource())
            .whenComplete((ignored, error) -> {
                if (error != null) {
                    Throwable cause = error instanceof CompletionException && error.getCause() != null
                        ? error.getCause() : error;
                    plugin.getLogger().severe("Failed to store the verified account link for "
                        + session.name() + ": " + cause.getMessage());
                }
            });
    }

    private String randomPassword() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
