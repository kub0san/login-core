package com.hizeric.logincore.authme;

import com.hizeric.logincore.mojang.MojangClient;
import com.hizeric.logincore.session.VerifiedSessions;
import com.hizeric.logincore.session.VerifiedSessions.VerifiedSession;
import com.hizeric.logincore.storage.Storage;
import fr.xephi.authme.events.LoginEvent;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommitBeforeGrantTest {

    private static final String UUID_TEXT = "069a79f4-44e9-4726-a5be-fca90e38a0e1";

    @TempDir
    Path temporaryDirectory;

    @Test
    void failedPersistenceRejectsAndDoesNotGrant() throws Exception {
        Storage storage = new Storage(temporaryDirectory.resolve("closed.db"));
        storage.close();
        VerifiedSessions sessions = new VerifiedSessions();
        sessions.put(session());
        List<String> grants = new ArrayList<>();
        AuthMeLoginListener listener = listener(storage, sessions, grants);
        AsyncPlayerPreLoginEvent event = event();

        listener.onAsyncPreLogin(event);

        assertTrue(grants.isEmpty(), "a failed write must not grant");
        assertEquals(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, event.getLoginResult());
        assertTrue(sessions.find("Notch", event.getAddress()).isEmpty(), "session must be one-shot");
    }

    @Test
    void classificationConflictRejectsAndDoesNotGrant() throws Exception {
        try (Storage storage = new Storage(temporaryDirectory.resolve("conflict.db"))) {
            storage.setClassification("Notch", "OFFLINE").get();
            VerifiedSessions sessions = new VerifiedSessions();
            sessions.put(session());
            List<String> grants = new ArrayList<>();
            AuthMeLoginListener listener = listener(storage, sessions, grants);
            AsyncPlayerPreLoginEvent event = event();

            listener.onAsyncPreLogin(event);

            assertTrue(grants.isEmpty());
            assertEquals(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, event.getLoginResult());
            assertTrue(storage.link("Notch").get().isEmpty(), "no link may be written after a CAS conflict");
            assertEquals("OFFLINE", storage.classification("Notch").get().orElseThrow());
        }
    }

    @Test
    void successfulCommitGrantsAfterWrites() throws Exception {
        try (Storage storage = new Storage(temporaryDirectory.resolve("success.db"))) {
            VerifiedSessions sessions = new VerifiedSessions();
            sessions.put(session());
            AtomicBoolean committedBeforeGrant = new AtomicBoolean(false);
            List<String> released = new ArrayList<>();
            List<String> grants = new ArrayList<>();
            AuthMeLoginListener listener = listener(storage, sessions, released::add, name -> {
                grants.add(name);
                committedBeforeGrant.set("PREMIUM".equals(storage.classification("Notch").join().orElse(null))
                    && storage.link("Notch").join().isPresent());
            });
            AsyncPlayerPreLoginEvent event = event();

            listener.onAsyncPreLogin(event);

            assertEquals(AsyncPlayerPreLoginEvent.Result.ALLOWED, event.getLoginResult());
            assertEquals(List.of("Notch"), grants);
            assertEquals(List.of("connection"), released);
            assertTrue(committedBeforeGrant.get(), "grant must observe both committed writes");
            assertTrue(sessions.consume("Notch", event.getAddress()).isEmpty(), "a second hook cannot reuse it");
        }
    }

    @Test
    void failedLinkWriteRollsBackClassificationAndDoesNotGrant() throws Exception {
        try (Storage storage = new Storage(temporaryDirectory.resolve("rollback.db"))) {
            storage.putLink("Other", UUID_TEXT, "Other", "AUTO").get();
            VerifiedSessions sessions = new VerifiedSessions();
            sessions.put(session());
            List<String> grants = new ArrayList<>();
            AuthMeLoginListener listener = listener(storage, sessions, grants);
            AsyncPlayerPreLoginEvent event = event();

            listener.onAsyncPreLogin(event);

            assertTrue(grants.isEmpty());
            assertEquals(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, event.getLoginResult());
            assertTrue(storage.classification("Notch").get().isEmpty(),
                "a failed link insert must not leave a permanent PREMIUM classification behind");
        }
    }

    @Test
    void uuidConflictRejectsAndDoesNotGrant() throws Exception {
        String otherUuid = "00000000-0000-0000-0000-000000000001";
        try (Storage storage = new Storage(temporaryDirectory.resolve("uuid-conflict.db"))) {
            storage.putLink("Notch", otherUuid, "Notch", "AUTO").get();
            VerifiedSessions sessions = new VerifiedSessions();
            sessions.put(session());
            List<String> grants = new ArrayList<>();
            AuthMeLoginListener listener = listener(storage, sessions, grants);
            AsyncPlayerPreLoginEvent event = event();

            listener.onAsyncPreLogin(event);

            assertTrue(grants.isEmpty());
            assertEquals(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, event.getLoginResult());
            assertEquals(otherUuid, storage.link("Notch").get().orElseThrow().mojangUuid(),
                "the conflicting UUID must not be overwritten");
            assertTrue(storage.classification("Notch").get().isEmpty(),
                "a UUID conflict must not leave a PREMIUM classification behind");
        }
    }

    @Test
    void passwordOnceCommitsAtPreLoginAndSurvivesUntilLoginEvent() throws Exception {
        try (Storage storage = new Storage(temporaryDirectory.resolve("password-once.db"))) {
            VerifiedSessions sessions = new VerifiedSessions();
            sessions.put(passwordOnceSession());
            List<String> grants = new ArrayList<>();
            List<String> released = new ArrayList<>();
            AuthMeLoginListener listener = listener(storage, sessions, released::add,
                name -> grants.add(name), name -> true, (name, password) -> true);
            AsyncPlayerPreLoginEvent event = event();

            listener.onAsyncPreLogin(event);

            assertEquals(AsyncPlayerPreLoginEvent.Result.ALLOWED, event.getLoginResult());
            assertTrue(grants.isEmpty(), "the password step must not auto-grant");
            assertTrue(released.isEmpty(), "releaseAfterAuth belongs to LoginEvent on this path");
            assertEquals("PREMIUM", storage.classification("Notch").get().orElseThrow());
            assertTrue(storage.link("Notch").get().isPresent());
            assertTrue(sessions.find("Notch", event.getAddress()).isPresent(),
                "the session must survive to LoginEvent for the password step");

            listener.onAuthMeLogin(new LoginEvent(player()));

            assertEquals(List.of("connection"), released);
            assertTrue(sessions.find("Notch", event.getAddress()).isEmpty());
        }
    }

    @Test
    void passwordOnceCommitFailureRejectsWithoutGrant() throws Exception {
        Storage storage = new Storage(temporaryDirectory.resolve("password-once-fail.db"));
        storage.close();
        VerifiedSessions sessions = new VerifiedSessions();
        sessions.put(passwordOnceSession());
        List<String> grants = new ArrayList<>();
        AuthMeLoginListener listener = listener(storage, sessions, ignored -> { },
            name -> grants.add(name), name -> true, (name, password) -> true);
        AsyncPlayerPreLoginEvent event = event();

        listener.onAsyncPreLogin(event);

        assertEquals(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, event.getLoginResult());
        assertTrue(grants.isEmpty());
        assertTrue(sessions.find("Notch", event.getAddress()).isPresent(),
            "the password-once session is not consumed on the rejected path");
    }

    @Test
    void registrationHappensOnlyAfterCommit() throws Exception {
        Storage storage = new Storage(temporaryDirectory.resolve("register.db"));
        storage.close();
        VerifiedSessions sessions = new VerifiedSessions();
        sessions.put(session(true, false));
        List<String> registrations = new ArrayList<>();
        List<String> grants = new ArrayList<>();
        AuthMeLoginListener listener = listener(storage, sessions, ignored -> { }, grants::add,
            name -> false, (name, password) -> {
                registrations.add(name);
                return true;
            });
        AsyncPlayerPreLoginEvent event = event();

        listener.onAsyncPreLogin(event);

        assertEquals(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, event.getLoginResult());
        assertTrue(registrations.isEmpty(), "no AuthMe account may be created after a failed commit");
        assertTrue(grants.isEmpty());
    }

    @Test
    void registrationAndGrantFollowSuccessfulCommit() throws Exception {
        try (Storage storage = new Storage(temporaryDirectory.resolve("register-ok.db"))) {
            VerifiedSessions sessions = new VerifiedSessions();
            sessions.put(session(true, false));
            List<String> registrations = new ArrayList<>();
            List<String> grants = new ArrayList<>();
            AtomicBoolean committedAtRegistration = new AtomicBoolean(false);
            AuthMeLoginListener listener = listener(storage, sessions, ignored -> { }, grants::add,
                name -> false, (name, password) -> {
                    committedAtRegistration.set("PREMIUM".equals(storage.classification(name).join().orElse(null))
                        && storage.link(name).join().isPresent());
                    registrations.add(name);
                    return true;
                });
            AsyncPlayerPreLoginEvent event = event();

            listener.onAsyncPreLogin(event);

            assertEquals(AsyncPlayerPreLoginEvent.Result.ALLOWED, event.getLoginResult());
            assertTrue(registrations.isEmpty(), "registration is deferred until the login check");
            assertEquals(List.of("Notch"), grants);

            listener.onPlayerLogin(loginEvent());

            assertEquals(List.of("Notch"), registrations);
            assertTrue(committedAtRegistration.get(), "registration must observe the committed identity");
        }
    }

    @Test
    void bannedPlayerIsNotRegistered() throws Exception {
        try (Storage storage = new Storage(temporaryDirectory.resolve("banned.db"))) {
            VerifiedSessions sessions = new VerifiedSessions();
            sessions.put(session(true, false));
            List<String> registrations = new ArrayList<>();
            List<String> grants = new ArrayList<>();
            AuthMeLoginListener listener = listener(storage, sessions, ignored -> { }, grants::add,
                name -> false, (name, password) -> {
                    registrations.add(name);
                    return true;
                });
            AsyncPlayerPreLoginEvent event = event();

            listener.onAsyncPreLogin(event);

            assertEquals(AsyncPlayerPreLoginEvent.Result.ALLOWED, event.getLoginResult());
            assertTrue(registrations.isEmpty(), "registration is deferred past the login check");

            PlayerLoginEvent login = loginEvent();
            login.disallow(PlayerLoginEvent.Result.KICK_BANNED, "banned");
            listener.onPlayerLogin(login);

            assertTrue(registrations.isEmpty(), "a player rejected by the ban check must have no AuthMe account");
        }
    }

    @Test
    void loginRegistrationFailureDisallows() throws Exception {
        try (Storage storage = new Storage(temporaryDirectory.resolve("login-fail.db"))) {
            VerifiedSessions sessions = new VerifiedSessions();
            sessions.put(session(true, false));
            AuthMeLoginListener listener = listener(storage, sessions, ignored -> { }, ignored -> { },
                name -> false, (name, password) -> false);
            AsyncPlayerPreLoginEvent event = event();
            listener.onAsyncPreLogin(event);

            PlayerLoginEvent login = loginEvent();
            listener.onPlayerLogin(login);

            assertEquals(PlayerLoginEvent.Result.KICK_OTHER, login.getResult());
        }
    }

    private static PlayerLoginEvent loginEvent() throws Exception {
        return new PlayerLoginEvent(player(), "127.0.0.1", InetAddress.getByName("127.0.0.1"));
    }

    private static AuthMeLoginListener listener(Storage storage, VerifiedSessions sessions, List<String> grants) {
        return listener(storage, sessions, ignored -> { }, grants::add, name -> true,
            (name, password) -> true);
    }

    private static AuthMeLoginListener listener(Storage storage, VerifiedSessions sessions,
                                                Consumer<String> released, Consumer<String> grant) {
        return listener(storage, sessions, released, grant, name -> true, (name, password) -> true);
    }

    private static AuthMeLoginListener listener(Storage storage, VerifiedSessions sessions,
                                                Consumer<String> released, Consumer<String> grant,
                                                Predicate<String> isRegistered,
                                                BiPredicate<String, String> register) {
        return new AuthMeLoginListener(plugin(), storage, sessions, released, grant, isRegistered, register);
    }

    private static AsyncPlayerPreLoginEvent event() throws Exception {
        // Supplying a profile avoids Bukkit.createProfile(), which needs a live Server.
        UUID uniqueId = UUID.randomUUID();
        com.destroystokyo.paper.profile.PlayerProfile profile = (com.destroystokyo.paper.profile.PlayerProfile)
            Proxy.newProxyInstance(com.destroystokyo.paper.profile.PlayerProfile.class.getClassLoader(),
                new Class<?>[]{com.destroystokyo.paper.profile.PlayerProfile.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> "Notch";
                    case "getId" -> uniqueId;
                    default -> null;
                });
        return new AsyncPlayerPreLoginEvent("Notch", InetAddress.getByName("127.0.0.1"), uniqueId, true, profile);
    }

    private static Player player() {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getName" -> "Notch";
                case "getAddress" -> new InetSocketAddress("127.0.0.1", 25565);
                default -> null;
            });
    }

    private static VerifiedSession session() {
        return session(false, false);
    }

    private static VerifiedSession passwordOnceSession() {
        return session(false, true);
    }

    private static VerifiedSession session(boolean shouldRegister, boolean requiresPasswordOnce) {
        return new VerifiedSession(
            "connection",
            "Notch",
            "127.0.0.1",
            MojangClient.SessionResult.verified(UUID.fromString(UUID_TEXT), "Notch"),
            Optional.empty(),
            false,
            shouldRegister,
            requiresPasswordOnce,
            !requiresPasswordOnce,
            "AUTO"
        );
    }

    /** Minimal Plugin stub; no server, no MockBukkit, no AuthMe singleton. */
    private static Plugin plugin() {
        FileConfiguration config = new YamlConfiguration();
        config.set("messages.account-conflict", "<red>account-conflict</red>");
        return (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(), new Class<?>[]{Plugin.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getConfig" -> config;
                case "getLogger" -> Logger.getLogger("commit-before-grant-test");
                default -> null;
            });
    }
}
