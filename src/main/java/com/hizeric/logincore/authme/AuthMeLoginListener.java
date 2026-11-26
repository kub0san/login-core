package com.hizeric.logincore.authme;

import fr.xephi.authme.events.LoginEvent;
import fr.xephi.authme.events.RestoreSessionEvent;
import com.hizeric.logincore.session.VerifiedSessions;
import com.hizeric.logincore.session.VerifiedSessions.VerifiedSession;
import com.hizeric.logincore.storage.Storage;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.plugin.Plugin;

import java.net.InetAddress;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Predicate;

public final class AuthMeLoginListener implements Listener {

    private final Plugin plugin;
    private final Storage storage;
    private final VerifiedSessions verifiedSessions;
    private final Consumer<String> onVerifiedLogin;
    private final Consumer<String> grant;
    private final Predicate<String> isRegistered;
    private final BiPredicate<String, String> register;
    // Registration intent survives from the async pre-login commit to the synchronous login
    // check so a player rejected by Bukkit's ban check never gets an AuthMe account.
    private final Map<String, String> pendingRegistrations = new ConcurrentHashMap<>();
    private final SecureRandom secureRandom = new SecureRandom();
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    public AuthMeLoginListener(Plugin plugin, AuthMeBridge authMeBridge, Storage storage,
                               VerifiedSessions verifiedSessions, Consumer<String> onVerifiedLogin) {
        this(plugin, storage, verifiedSessions, onVerifiedLogin,
            authMeBridge::forceLoginFromProxy, authMeBridge::isRegistered, authMeBridge::registerPlayer);
    }

    AuthMeLoginListener(Plugin plugin, Storage storage, VerifiedSessions verifiedSessions,
                        Consumer<String> onVerifiedLogin, Consumer<String> grant) {
        this(plugin, storage, verifiedSessions, onVerifiedLogin, grant, name -> true,
            (name, password) -> true);
    }

    AuthMeLoginListener(Plugin plugin, Storage storage, VerifiedSessions verifiedSessions,
                        Consumer<String> onVerifiedLogin, Consumer<String> grant,
                        Predicate<String> isRegistered, BiPredicate<String, String> register) {
        this.plugin = plugin;
        this.storage = storage;
        this.verifiedSessions = verifiedSessions;
        this.onVerifiedLogin = onVerifiedLogin;
        this.grant = grant;
        this.isRegistered = isRegistered;
        this.register = register;
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

    /**
     * Identity (classification + link) is proven by the handshake, so it is committed here for
     * every policy before AuthMe can authenticate. Only auto-login paths consume the session;
     * REQUIRE_PASSWORD_ONCE keeps it for {@link #onAuthMeLogin(LoginEvent)}.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAsyncPreLogin(AsyncPlayerPreLoginEvent event) {
        Optional<VerifiedSession> pending = verifiedSessions.find(event.getName(), event.getAddress());
        if (pending.isEmpty()) {
            return;
        }
        VerifiedSession session = pending.get();
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return;
        }
        if (session.requiresPasswordOnce()) {
            if (!isRegistered.test(event.getName())) {
                event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    "Your AuthMe account changed during login. Please reconnect.");
                return;
            }
        } else {
            // One-shot consume on auto-login paths, before any commit or grant.
            verifiedSessions.consume(event.getName(), event.getAddress());
        }

        try {
            // Single join, on the async pre-login thread: never on the main thread.
            boolean committed;
            try {
                committed = storage.commitPremiumIdentity(session.name(),
                    session.mojangSession().uuid().toString(),
                    session.mojangSession().canonicalName(), session.linkSource()).join();
            } catch (CompletionException | RejectedExecutionException ex) {
                committed = false;
            }
            if (!committed) {
                plugin.getLogger().severe("Premium identity for " + session.name()
                    + " could not be persisted; refusing to grant access.");
                event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, message("account-conflict"));
                return;
            }
            if (session.autoLogin()) {
                if (session.shouldRegister()) {
                    if (isRegistered.test(event.getName())) {
                        event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                            "An account with this name already exists. Please reconnect or contact an administrator.");
                        return;
                    }
                    pendingRegistrations.put(registrationKey(event.getName(), event.getAddress()), randomPassword());
                }
                grant.accept(event.getName());
                onVerifiedLogin.accept(session.connectionId());
            }
        } catch (RuntimeException ex) {
            plugin.getLogger().severe("AuthMe rejected the verified login for " + event.getName() + ": " + ex.getMessage());
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                "Authentication could not be completed. Please try again later.");
        }
    }

    /**
     * Bukkit performs its ban check before this handler, and AuthMe only processes the join at
     * {@link org.bukkit.event.player.PlayerJoinEvent}. Registering here keeps banned players out of
     * the account table without delaying or reordering the identity commit at pre-login.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerLogin(PlayerLoginEvent event) {
        Player player = event.getPlayer();
        if (player == null) {
            return;
        }
        String password = pendingRegistrations.remove(registrationKey(player.getName(), event.getAddress()));
        if (password == null || event.getResult() != PlayerLoginEvent.Result.ALLOWED) {
            return;
        }
        if (!register.test(player.getName(), password)) {
            plugin.getLogger().severe("Could not prepare the AuthMe account for " + player.getName());
            event.disallow(PlayerLoginEvent.Result.KICK_OTHER, message("account-conflict"));
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
        // Identity was already committed at pre-login; no storage call (and no join) here.
        Optional<VerifiedSession> pending = verifiedSessions.consume(player.getName(), player.getAddress());
        pending.ifPresent(session -> onVerifiedLogin.accept(session.connectionId()));
    }

    private static String registrationKey(String name, InetAddress address) {
        return name.toLowerCase(Locale.ROOT) + "@" + (address == null ? "" : address.getHostAddress());
    }

    private Component message(String key) {
        String text = plugin.getConfig().getString("messages." + key);
        if (text == null || text.isBlank()) {
            text = "<red>Login verification failed.";
        }
        return miniMessage.deserialize(text);
    }

    private String randomPassword() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
