package com.hizeric.logincore.authme;

import com.hizeric.logincore.events.LoginEvent;
import com.hizeric.logincore.events.RegisterEvent;
import com.hizeric.logincore.mojang.MojangClient;
import com.hizeric.logincore.session.AuthState;
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
    private final AuthState authState;
    private final Storage storage;
    private final VerifiedSessions verifiedSessions;
    private final Consumer<String> onVerifiedLogin;
    private final Consumer<String> grant;
    private final Predicate<String> isRegistered;
    private final BiPredicate<String, String> register;
    // Registration intent survives from the async pre-login commit to the synchronous login
    // check so a player rejected by Bukkit's ban check never gets an account.
    private final Map<String, String> pendingRegistrations = new ConcurrentHashMap<>();
    private final SecureRandom secureRandom = new SecureRandom();
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    public AuthMeLoginListener(Plugin plugin, AuthState authState, Storage storage,
                               VerifiedSessions verifiedSessions, Consumer<String> onVerifiedLogin) {
        this(plugin, authState, storage, verifiedSessions, onVerifiedLogin,
            name -> storage.setLoggedIn(name).join(),
            name -> storage.isRegistered(name).join(),
            (name, password) -> {
                if (!storage.registerPlayer(name, password).join()) {
                    return false;
                }
                storage.setLoggedIn(name).join();
                return true;
            });
    }

    AuthMeLoginListener(Plugin plugin, AuthState authState, Storage storage, VerifiedSessions verifiedSessions,
                        Consumer<String> onVerifiedLogin, Consumer<String> grant) {
        this(plugin, authState, storage, verifiedSessions, onVerifiedLogin, grant, name -> true,
            (name, password) -> true);
    }

    AuthMeLoginListener(Plugin plugin, AuthState authState, Storage storage, VerifiedSessions verifiedSessions,
                        Consumer<String> onVerifiedLogin, Consumer<String> grant,
                        Predicate<String> isRegistered, BiPredicate<String, String> register) {
        this.plugin = plugin;
        this.authState = authState;
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
     * Auto-login paths prove identity with the handshake, so classification + link are committed
     * here before the grant. REQUIRE_PASSWORD_ONCE does not commit: the account password must be
     * verified first, and {@link #onPasswordLogin(Player)} commits only after that succeeds.
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
            // The existing account must still exist, but no identity write is allowed before the
            // password; the session survives to the password login for the commit.
            if (!isRegistered.test(event.getName())) {
                event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    "Your account changed during login. Please reconnect.");
            }
            return;
        }
        if (!session.autoLogin()) {
            // Fail closed: no other policy may be granted before a password is proven.
            return;
        }
        // One-shot consume on auto-login paths, before any commit or grant.
        verifiedSessions.consume(event.getName(), event.getAddress());

        try {
            // Single transactional commit, on the async pre-login thread: never on the main thread.
            CommitResult result = commitIdentity(session);
            if (result == CommitResult.RENAME_CONFLICT) {
                event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, message("rename-conflict"));
                return;
            }
            if (result == CommitResult.CONFLICT) {
                plugin.getLogger().severe("Premium identity for " + session.name()
                    + " could not be persisted; refusing to grant access.");
                event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, message("account-conflict"));
                return;
            }
            if (session.shouldRegister()) {
                if (isRegistered.test(event.getName())) {
                    event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                        "An account with this name already exists. Please reconnect or contact an administrator.");
                    return;
                }
                pendingRegistrations.put(registrationKey(event.getName(), event.getAddress()), randomPassword());
            }
            authState.authenticate(event.getName());
            grant.accept(event.getName());
            onVerifiedLogin.accept(session.connectionId());
        } catch (RuntimeException ex) {
            plugin.getLogger().severe("Login verification rejected the verified login for "
                + event.getName() + ": " + ex.getMessage());
            // A pre-login kick gets no PlayerQuitEvent, so clear any authentication set before the failure.
            authState.logout(event.getName());
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                "Authentication could not be completed. Please try again later.");
        }
    }

    /** Write-once PREMIUM classification + link in one transaction; never grants by itself. */
    private CommitResult commitIdentity(VerifiedSession session) {
        MojangClient.SessionResult verification = session.mojangSession();
        if (verification.status() != MojangClient.SessionStatus.VERIFIED
            || verification.uuid() == null
            || verification.canonicalName() == null || verification.canonicalName().isBlank()) {
            plugin.getLogger().warning("Refusing identity commit for " + session.name()
                + ": Mojang session is not verified");
            return CommitResult.CONFLICT;
        }
        try {
            String mojangUuid = session.mojangSession().uuid().toString();
            if (session.link().isEmpty()) {
                Optional<Storage.Link> byUuid = storage.linkByUuid(mojangUuid).join();
                if (byUuid.isPresent() && !byUuid.get().name().equalsIgnoreCase(session.name())) {
                    plugin.getLogger().warning("Premium UUID " + mojangUuid + " is already linked to "
                        + byUuid.get().name() + "; refusing rename to " + session.name()
                        + " (the old link is left untouched)");
                    return CommitResult.RENAME_CONFLICT;
                }
            }
            return storage.commitPremiumIdentity(session.name(), mojangUuid,
                session.mojangSession().canonicalName(), session.linkSource()).join()
                ? CommitResult.COMMITTED : CommitResult.CONFLICT;
        } catch (CompletionException | RejectedExecutionException ex) {
            return CommitResult.CONFLICT;
        }
    }

    /**
     * Bukkit performs its ban check before this handler. Registering here keeps rejected players
     * out of the account table without delaying or reordering the identity commit at pre-login.
     * A rejected pre-login must also never leave stale in-memory authentication behind.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerLogin(PlayerLoginEvent event) {
        Player player = event.getPlayer();
        if (player == null) {
            return;
        }
        String name = player.getName();
        // Consume intent before the ban check so a rejected join cannot leave stale registration intent.
        String password = pendingRegistrations.remove(registrationKey(name, event.getAddress()));
        if (event.getResult() != PlayerLoginEvent.Result.ALLOWED) {
            authState.logout(name);
            return;
        }
        if (password == null) {
            // Pre-authenticated premium auto-login: no registration, and the password hook is not used.
            if (authState.isAuthenticated(name)) {
                plugin.getServer().getPluginManager().callEvent(new LoginEvent(player));
            }
            return;
        }
        if (!register.test(name, password)) {
            plugin.getLogger().severe("Could not prepare the account for " + name);
            event.disallow(PlayerLoginEvent.Result.KICK_OTHER, message("account-conflict"));
            authState.logout(name);
            return;
        }
        plugin.getServer().getPluginManager().callEvent(new RegisterEvent(player));
        plugin.getServer().getPluginManager().callEvent(new LoginEvent(player));
    }

    /**
     * Called by the password command after the account password has been verified. REQUIRE_PASSWORD_ONCE
     * sessions survive pre-login; commit their identity first, and only then release. A failed commit
     * kicks instead. Auto-login sessions were consumed at pre-login, so this is one-shot.
     *
     * @return false when the player was kicked; true when the caller may complete the grant. On the
     *         no-pending path the password has already been checked by the caller and its grant is
     *         completed there; only the password-once path is fully granted here.
     */
    public boolean onPasswordLogin(Player player) {
        if (player == null) {
            return false;
        }
        Optional<VerifiedSession> pending = verifiedSessions.consume(player.getName(), player.getAddress());
        if (pending.isEmpty()) {
            plugin.getServer().getPluginManager().callEvent(new LoginEvent(player));
            return true;
        }
        VerifiedSession session = pending.get();
        CommitResult result;
        try {
            result = commitIdentity(session);
        } catch (RuntimeException ex) {
            result = CommitResult.CONFLICT;
        }
        if (result != CommitResult.COMMITTED) {
            plugin.getLogger().severe("Premium identity for " + session.name()
                + " could not be persisted after password login; refusing to release.");
            player.kick(message("account-conflict"));
            authState.logout(player.getName());
            return false;
        }
        authState.authenticate(player.getName());
        onVerifiedLogin.accept(session.connectionId());
        plugin.getServer().getPluginManager().callEvent(new LoginEvent(player));
        return true;
    }

    private enum CommitResult { COMMITTED, CONFLICT, RENAME_CONFLICT }

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
