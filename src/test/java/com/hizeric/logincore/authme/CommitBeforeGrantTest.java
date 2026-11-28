package com.hizeric.logincore.authme;

import com.hizeric.logincore.TestPlugin;
import com.hizeric.logincore.events.LoginEvent;
import com.hizeric.logincore.events.RegisterEvent;
import com.hizeric.logincore.mojang.MojangClient;
import com.hizeric.logincore.session.AuthState;
import com.hizeric.logincore.session.VerifiedSessions;
import com.hizeric.logincore.session.VerifiedSessions.VerifiedSession;
import com.hizeric.logincore.storage.Storage;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void autoLoginStillCommitsBeforeGrant() throws Exception {
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
        String otherUuid = "00000000-0000-0000-0000-000000000002";
        try (Storage storage = new Storage(temporaryDirectory.resolve("rollback.db"))) {
            // Same name, different UUID: a genuine conflict, not a premium rename.
            storage.putLink("Notch", otherUuid, "Notch", "AUTO").get();
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
    void renamedPremiumMigratesAndGrants() throws Exception {
        try (Storage storage = new Storage(temporaryDirectory.resolve("rename-migration.db"))) {
            storage.putLink("Old", UUID_TEXT, "Old", "AUTO").get();
            VerifiedSessions sessions = new VerifiedSessions();
            sessions.put(session("new"));
            List<String> grants = new ArrayList<>();
            AuthMeLoginListener listener = listener(storage, sessions, grants);
            AsyncPlayerPreLoginEvent event = event("new");

            listener.onAsyncPreLogin(event);

            assertEquals(AsyncPlayerPreLoginEvent.Result.ALLOWED, event.getLoginResult());
            assertEquals(List.of("new"), grants, "a migrated rename follows the normal grant path");
            assertTrue(storage.link("old").get().isEmpty(), "the old link must be released");
            assertEquals(UUID_TEXT, storage.link("new").get().orElseThrow().mojangUuid());
            assertEquals("new", storage.link("new").get().orElseThrow().name());
            assertEquals(1, storage.summary().get().links(), "exactly one link survives the migration");
            assertTrue(storage.authmeToken("new").get().orElseThrow().length() == 36,
                "the migration must write the stable account token for the cookie");
        }
    }

    @Test
    void onlineSquatterIsKickedAndInflightAuthCancelledOnRename() throws Exception {
        try (Storage storage = new Storage(temporaryDirectory.resolve("online-squatter.db"))) {
            storage.putLink("Old", UUID_TEXT, "Old", "AUTO").get();
            InetSocketAddress squatterAddress = new InetSocketAddress("10.0.0.2", 25565);
            VerifiedSession squatterSession = new VerifiedSession(
                "squatter-connection", "new", "10.0.0.2",
                MojangClient.SessionResult.verified(UUID.randomUUID(), "new"),
                Optional.empty(), false, false, false, true, "AUTO");
            VerifiedSessions sessions = new VerifiedSessions();
            sessions.put(squatterSession);
            sessions.put(session("new"));
            List<String> kicks = new ArrayList<>();
            Player squatter = player("new", squatterAddress, kicks);
            List<String> grants = new ArrayList<>();
            AuthMeLoginListener listener = listener(storage, sessions, grants);
            listener.onlinePlayerLookup = name -> name.equalsIgnoreCase("new") ? squatter : null;
            Set<String> pending = pendingRegistrations(listener);
            pending.add("new@10.0.0.2");
            AsyncPlayerPreLoginEvent event = event("new");

            listener.onAsyncPreLogin(event);

            assertEquals(AsyncPlayerPreLoginEvent.Result.ALLOWED, event.getLoginResult());
            assertEquals(List.of("new"), grants);
            assertEquals(1, kicks.size(), kicks.toString());
            assertTrue(kicks.get(0).contains("account-conflict"), kicks.toString());
            assertTrue(pending.isEmpty(), "the squatter's pending registration must be cancelled");
            assertTrue(sessions.find("new", squatterAddress).isEmpty(),
                "the squatter's in-flight verified session must be dropped");
            assertTrue(storage.link("old").get().isEmpty());
            assertEquals(UUID_TEXT, storage.link("new").get().orElseThrow().mojangUuid());
        }
    }

    @Test
    void squatterLookupFailureDoesNotBlockRename() throws Exception {
        try (Storage storage = new Storage(temporaryDirectory.resolve("squatter-lookup-fail.db"))) {
            storage.putLink("Old", UUID_TEXT, "Old", "AUTO").get();
            VerifiedSessions sessions = new VerifiedSessions();
            sessions.put(session("new"));
            List<String> grants = new ArrayList<>();
            AuthMeLoginListener listener = listener(storage, sessions, grants);
            listener.onlinePlayerLookup = name -> {
                throw new IllegalStateException("no live server");
            };
            AsyncPlayerPreLoginEvent event = event("new");

            listener.onAsyncPreLogin(event);

            assertEquals(AsyncPlayerPreLoginEvent.Result.ALLOWED, event.getLoginResult());
            assertEquals(List.of("new"), grants);
            assertEquals(UUID_TEXT, storage.link("new").get().orElseThrow().mojangUuid());
        }
    }

    @Test
    void unknownUuidStillRegistersNormally() throws Exception {
        try (Storage storage = new Storage(temporaryDirectory.resolve("unknown-uuid.db"))) {
            VerifiedSessions sessions = new VerifiedSessions();
            sessions.put(session("fresh"));
            List<String> grants = new ArrayList<>();
            AuthMeLoginListener listener = listener(storage, sessions, grants);
            AsyncPlayerPreLoginEvent event = event("fresh");

            listener.onAsyncPreLogin(event);

            assertEquals(AsyncPlayerPreLoginEvent.Result.ALLOWED, event.getLoginResult());
            assertEquals(List.of("fresh"), grants);
            assertEquals(UUID_TEXT, storage.link("fresh").get().orElseThrow().mojangUuid());
        }
    }

    @Test
    void requirePasswordOnceDoesNotCommitBeforePassword() throws Exception {
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
            assertTrue(storage.classification("Notch").get().isEmpty(),
                "no classification may be committed before the password");
            assertTrue(storage.link("Notch").get().isEmpty(),
                "no link may be committed before the password");
            assertTrue(sessions.find("Notch", event.getAddress()).isPresent(),
                "the session must survive to LoginEvent for the password step");
        }
    }

    @Test
    void requirePasswordOnceCommitsAfterPasswordLogin() throws Exception {
        try (Storage storage = new Storage(temporaryDirectory.resolve("password-once-ok.db"))) {
            VerifiedSessions sessions = new VerifiedSessions();
            sessions.put(passwordOnceSession());
            List<String> released = new ArrayList<>();
            List<String> kicks = new ArrayList<>();
            AuthMeLoginListener listener = listener(storage, sessions, released::add,
                name -> { }, name -> true, (name, password) -> true);
            AsyncPlayerPreLoginEvent event = event();

            listener.onAsyncPreLogin(event);
            assertEquals(AsyncPlayerPreLoginEvent.Result.ALLOWED, event.getLoginResult());
            assertTrue(storage.link("Notch").get().isEmpty());

            Player player = player(kicks);
            assertTrue(listener.onPasswordLogin(player), "a verified commit must grant");
            assertTrue(TestPlugin.firedEvents().stream()
                .anyMatch(fired -> fired instanceof LoginEvent login && login.getPlayer() == player),
                "a successful password login must fire the compatibility event");

            assertEquals("PREMIUM", storage.classification("Notch").get().orElseThrow());
            Storage.Link link = storage.link("Notch").get().orElseThrow();
            assertEquals(UUID_TEXT, link.mojangUuid());
            assertEquals("PASSWORD_ONCE", link.source());
            assertEquals(List.of("connection"), released);
            assertTrue(kicks.isEmpty(), "a successful commit must not kick");
            assertTrue(sessions.find("Notch", event.getAddress()).isEmpty(), "the session is one-shot");
        }
    }

    @Test
    void requirePasswordOnceCommitFailureDoesNotRelease() throws Exception {
        Storage storage = new Storage(temporaryDirectory.resolve("password-once-fail.db"));
        storage.close();
        VerifiedSessions sessions = new VerifiedSessions();
        sessions.put(passwordOnceSession());
        List<String> released = new ArrayList<>();
        List<String> kicks = new ArrayList<>();
        AuthMeLoginListener listener = listener(storage, sessions, released::add,
            name -> { }, name -> true, (name, password) -> true);
        AsyncPlayerPreLoginEvent event = event();

        listener.onAsyncPreLogin(event);
        assertEquals(AsyncPlayerPreLoginEvent.Result.ALLOWED, event.getLoginResult(),
            "the failure belongs to LoginEvent, not pre-login");

        assertFalse(listener.onPasswordLogin(player(kicks)), "a failed commit must not grant");

        assertTrue(released.isEmpty(), "releaseAfterAuth must not run after a failed commit");
        assertTrue(TestPlugin.firedEvents().stream().noneMatch(fired -> fired instanceof LoginEvent),
            "a failed password login must not fire the compatibility event");
        assertTrue(kicks.get(0).contains("account-conflict"), kicks.toString());
        assertTrue(sessions.find("Notch", event.getAddress()).isEmpty());
    }

    @Test
    void requirePasswordOnceClassificationConflictDoesNotRelease() throws Exception {
        try (Storage storage = new Storage(temporaryDirectory.resolve("password-once-conflict.db"))) {
            storage.setClassification("Notch", "OFFLINE").get();
            VerifiedSessions sessions = new VerifiedSessions();
            sessions.put(passwordOnceSession());
            List<String> released = new ArrayList<>();
            List<String> kicks = new ArrayList<>();
            AuthMeLoginListener listener = listener(storage, sessions, released::add,
                name -> { }, name -> true, (name, password) -> true);
            AsyncPlayerPreLoginEvent event = event();

            listener.onAsyncPreLogin(event);
            assertEquals(AsyncPlayerPreLoginEvent.Result.ALLOWED, event.getLoginResult());

            assertFalse(listener.onPasswordLogin(player(kicks)), "a failed commit must not grant");

            assertTrue(released.isEmpty(), "ALREADY_DIFFERENT must not release");
            assertTrue(kicks.get(0).contains("account-conflict"), kicks.toString());
            assertEquals("OFFLINE", storage.classification("Notch").get().orElseThrow());
            assertTrue(storage.link("Notch").get().isEmpty(), "no link after a CAS conflict");
        }
    }

    @Test
    void invalidVerificationNeverAuthenticatesOnPasswordLogin() throws Exception {
        try (Storage storage = new Storage(temporaryDirectory.resolve("invalid-verification.db"))) {
            VerifiedSessions sessions = new VerifiedSessions();
            sessions.put(invalidVerificationSession());
            AuthState authState = new AuthState();
            List<String> released = new ArrayList<>();
            List<String> kicks = new ArrayList<>();
            AuthMeLoginListener listener = listener(authState, storage, sessions, released::add,
                name -> { }, name -> true, (name, password) -> true);
            AsyncPlayerPreLoginEvent event = event();

            listener.onAsyncPreLogin(event);

            assertEquals(AsyncPlayerPreLoginEvent.Result.ALLOWED, event.getLoginResult());
            assertTrue(storage.classification("Notch").get().isEmpty(),
                "REQUIRE_PASSWORD_ONCE must not commit classification before the password");
            assertTrue(storage.link("Notch").get().isEmpty(),
                "REQUIRE_PASSWORD_ONCE must not commit a link before the password");

            assertFalse(listener.onPasswordLogin(player(kicks)),
                "a NOT_VERIFIED session must never authenticate via password login");
            assertFalse(kicks.isEmpty(), "an invalid session must kick the player, not authenticate them");
            assertTrue(released.isEmpty(), "an invalid session must never reach the release/grant hook");
            assertFalse(authState.isAuthenticated("notch"),
                "an invalid session must never mark the player authenticated");
            assertTrue(TestPlugin.firedEvents().stream().noneMatch(fired -> fired instanceof LoginEvent),
                "an invalid session must never fire the compatibility LoginEvent");
            assertTrue(storage.classification("Notch").get().isEmpty(),
                "an invalid session must not leave a PREMIUM classification behind");
            assertTrue(storage.link("Notch").get().isEmpty(),
                "an invalid session must not leave a link behind");
            assertTrue(sessions.find("Notch", event.getAddress()).isEmpty(),
                "the session must be consumed one-shot");
        }
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
            assertEquals(List.of("Notch"), registrations, "registration runs on the pre-login worker");
            assertTrue(committedAtRegistration.get(), "registration must observe the committed identity");
            assertEquals(List.of("Notch"), grants);
            assertTrue(TestPlugin.firedEvents().isEmpty(), "events wait for PlayerLoginEvent");

            listener.onPlayerLogin(loginEvent());

            assertEquals(List.of("Notch"), registrations, "PlayerLoginEvent must not register again");
            assertEquals(2, TestPlugin.firedEvents().size());
        }
    }

    @Test
    void bannedPreLoginDoesNotRegister() throws Exception {
        try (Storage storage = new Storage(temporaryDirectory.resolve("banned.db"))) {
            VerifiedSessions sessions = new VerifiedSessions();
            sessions.put(session(true, false));
            List<String> registrations = new ArrayList<>();
            List<String> grants = new ArrayList<>();
            AuthState authState = new AuthState();
            AuthMeLoginListener listener = new AuthMeLoginListener(plugin(), authState, storage, sessions,
                ignored -> { }, grants::add, name -> false, (name, password) -> {
                    registrations.add(name);
                    return true;
                }, (name, address) -> true, Runnable::run);
            AsyncPlayerPreLoginEvent event = event();

            listener.onAsyncPreLogin(event);

            assertEquals(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, event.getLoginResult());
            assertTrue(registrations.isEmpty(), "a banned player must never reach registration");
            assertTrue(grants.isEmpty());
            assertFalse(authState.isAuthenticated("notch"));
            assertTrue(TestPlugin.firedEvents().isEmpty());
        }
    }

    @Test
    void defaultBanCheckRejectsIpBannedPlayerBeforeRegistration() throws Exception {
        MockBukkit.mock();
        try (Storage storage = new Storage(temporaryDirectory.resolve("ip-ban.db"))) {
            VerifiedSessions sessions = new VerifiedSessions();
            sessions.put(session(true, false));
            TestPlugin plugin = mockBukkitPlugin();
            plugin.getServer().getBanList(BanList.Type.IP)
                .addBan("127.0.0.1", "test", (Date) null, "test");
            AuthState authState = new AuthState();
            AuthMeLoginListener listener =
                new AuthMeLoginListener(plugin, authState, storage, sessions, ignored -> { });
            AsyncPlayerPreLoginEvent event = event();

            listener.onAsyncPreLogin(event);

            assertEquals(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, event.getLoginResult(),
                "the default production check must cover IP bans");
            assertFalse(storage.isRegistered("Notch").get(), "an IP-banned player must not get an account");
            assertFalse(authState.isAuthenticated("notch"));
            assertTrue(TestPlugin.firedEvents().isEmpty());
        } finally {
            MockBukkit.unmock();
        }
    }

    @Test
    void defaultBanCheckRejectsUuidBannedPlayerBeforeRegistration() throws Exception {
        MockBukkit.mock();
        try (Storage storage = new Storage(temporaryDirectory.resolve("uuid-ban.db"))) {
            UUID uuid = UUID.fromString(UUID_TEXT);
            VerifiedSessions sessions = new VerifiedSessions();
            sessions.put(session(true, false));
            TestPlugin plugin = mockBukkitPlugin();
            plugin.getServer().getBanList(BanList.Type.NAME)
                .addBan(uuid.toString(), "test", (Date) null, "test");
            AuthState authState = new AuthState();
            AuthMeLoginListener listener =
                new AuthMeLoginListener(plugin, authState, storage, sessions, ignored -> { });
            AsyncPlayerPreLoginEvent event = event("Notch", uuid);

            listener.onAsyncPreLogin(event);

            assertEquals(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, event.getLoginResult(),
                "the default check must cover UUID bans");
            assertFalse(storage.isRegistered("Notch").get(), "a UUID-banned player must not get an account");
            assertFalse(authState.isAuthenticated("notch"));
            assertTrue(TestPlugin.firedEvents().isEmpty());
        } finally {
            MockBukkit.unmock();
        }
    }

    @Test
    void failedGrantAfterRegistrationLeavesNoEventMarker() throws Exception {
        try (Storage storage = new Storage(temporaryDirectory.resolve("grant-throws-registration.db"))) {
            VerifiedSessions sessions = new VerifiedSessions();
            sessions.put(session(true, false));
            List<String> registrations = new ArrayList<>();
            AuthState authState = new AuthState();
            AuthMeLoginListener listener = new AuthMeLoginListener(plugin(), authState, storage, sessions,
                ignored -> { }, name -> { throw new IllegalStateException("grant failed"); },
                name -> false, (name, password) -> {
                    registrations.add(name);
                    return true;
                }, (name, address) -> false, Runnable::run);
            AsyncPlayerPreLoginEvent event = event();

            listener.onAsyncPreLogin(event);

            assertEquals(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, event.getLoginResult());
            assertEquals(List.of("Notch"), registrations, "registration may commit before a later grant throws");
            assertFalse(authState.isAuthenticated("notch"));

            listener.onPlayerLogin(loginEvent());

            assertTrue(TestPlugin.firedEvents().isEmpty(),
                "a failed pre-login must leave no marker that fires RegisterEvent/LoginEvent");
        }
    }

    @Test
    void rejectedJoinClearsPendingRegistrationIntent() throws Exception {
        try (Storage storage = new Storage(temporaryDirectory.resolve("rejected-join.db"))) {
            VerifiedSessions sessions = new VerifiedSessions();
            sessions.put(session(true, false));
            List<String> registrations = new ArrayList<>();
            AuthMeLoginListener listener = listener(storage, sessions, ignored -> { }, ignored -> { },
                name -> false, (name, password) -> {
                    registrations.add(name);
                    return true;
                });
            AsyncPlayerPreLoginEvent preLogin = event();

            listener.onAsyncPreLogin(preLogin);

            assertEquals(AsyncPlayerPreLoginEvent.Result.ALLOWED, preLogin.getLoginResult());
            assertEquals(List.of("Notch"), registrations);

            PlayerLoginEvent rejected = loginEvent();
            rejected.disallow(PlayerLoginEvent.Result.KICK_BANNED, "banned");
            listener.onPlayerLogin(rejected);
            assertTrue(TestPlugin.firedEvents().isEmpty(), "a rejected join must not fire compatibility events");

            PlayerLoginEvent retry = new PlayerLoginEvent(player(new ArrayList<>()), "127.0.0.1",
                InetAddress.getByName("127.0.0.1"));
            listener.onPlayerLogin(retry);

            assertEquals(PlayerLoginEvent.Result.ALLOWED, retry.getResult());
            assertEquals(List.of("Notch"), registrations, "no duplicate registration on retry");
            assertTrue(TestPlugin.firedEvents().isEmpty(), "a rejected join must not leave a stale event marker");
        }
    }

    @Test
    void registrationFailureDisallowsAtPreLogin() throws Exception {
        try (Storage storage = new Storage(temporaryDirectory.resolve("login-fail.db"))) {
            VerifiedSessions sessions = new VerifiedSessions();
            sessions.put(session(true, false));
            AuthMeLoginListener listener = listener(storage, sessions, ignored -> { }, ignored -> { },
                name -> false, (name, password) -> false);
            AsyncPlayerPreLoginEvent event = event();
            listener.onAsyncPreLogin(event);

            assertEquals(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, event.getLoginResult());
            listener.onPlayerLogin(loginEvent());
            assertTrue(TestPlugin.firedEvents().isEmpty(), "a failed registration must not fire events");
        }
    }

    @Test
    void registrationFailureClearsAuthenticatedState() throws Exception {
        try (Storage storage = new Storage(temporaryDirectory.resolve("register-fail-auth-state.db"))) {
            VerifiedSessions sessions = new VerifiedSessions();
            sessions.put(session(true, false));
            AuthState authState = new AuthState();
            AuthMeLoginListener listener = listener(authState, storage, sessions, ignored -> { }, ignored -> { },
                name -> false, (name, password) -> false);
            AsyncPlayerPreLoginEvent preLogin = event();

            listener.onAsyncPreLogin(preLogin);

            assertEquals(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, preLogin.getLoginResult());
            assertFalse(authState.isAuthenticated("Notch"),
                "a registration failure must not leave stale in-memory authentication");
            assertTrue(TestPlugin.firedEvents().stream().noneMatch(fired -> fired instanceof LoginEvent),
                "a failed registration must not fire the compatibility LoginEvent");
        }
    }

    @Test
    void autoLoginGrantMarksPlayerAuthenticated() throws Exception {
        try (Storage storage = new Storage(temporaryDirectory.resolve("auto-auth-state.db"))) {
            VerifiedSessions sessions = new VerifiedSessions();
            sessions.put(session());
            AuthState authState = new AuthState();

            listener(authState, storage, sessions, ignored -> { }, ignored -> { })
                .onAsyncPreLogin(event());

            assertTrue(authState.isAuthenticated("notch"));
        }
    }

    @Test
    void grantFailureClearsAuthenticatedState() throws Exception {
        try (Storage storage = new Storage(temporaryDirectory.resolve("grant-fail-auth-state.db"))) {
            VerifiedSessions sessions = new VerifiedSessions();
            sessions.put(session());
            AuthState authState = new AuthState();
            AuthMeLoginListener listener = listener(authState, storage, sessions, ignored -> { },
                name -> { throw new RuntimeException("grant failed"); });
            AsyncPlayerPreLoginEvent event = event();

            listener.onAsyncPreLogin(event);

            assertEquals(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, event.getLoginResult());
            assertFalse(authState.isAuthenticated("Notch"),
                "a player kicked at pre-login must not keep stale in-memory authentication");
        }
    }

    @Test
    void passwordOnceReleaseMarksPlayerAuthenticated() throws Exception {
        try (Storage storage = new Storage(temporaryDirectory.resolve("password-once-auth-state.db"))) {
            VerifiedSessions sessions = new VerifiedSessions();
            sessions.put(passwordOnceSession());
            AuthState authState = new AuthState();
            AuthMeLoginListener listener = listener(authState, storage, sessions, ignored -> { }, ignored -> { });

            listener.onAsyncPreLogin(event());
            assertTrue(listener.onPasswordLogin(player()));

            assertTrue(authState.isAuthenticated("notch"));
        }
    }

    @Test
    void registrationHappensOnPreLoginWorker() throws Exception {
        try (Storage storage = new Storage(temporaryDirectory.resolve("worker.db"))) {
            VerifiedSessions sessions = new VerifiedSessions();
            sessions.put(session(true, false));
            List<String> registrationThreads = new ArrayList<>();
            AuthMeLoginListener listener = new AuthMeLoginListener(plugin(), new AuthState(), storage, sessions,
                ignored -> { }, ignored -> { }, name -> false, (name, password) -> {
                    registrationThreads.add(Thread.currentThread().getName());
                    return true;
                }, (name, address) -> false, Runnable::run);
            AsyncPlayerPreLoginEvent event = event();

            Thread worker = new Thread(() -> listener.onAsyncPreLogin(event), "pre-login-worker");
            worker.start();
            worker.join();

            assertEquals(List.of("pre-login-worker"), registrationThreads);
            assertTrue(TestPlugin.firedEvents().isEmpty(), "events must wait for PlayerLoginEvent");

            listener.onPlayerLogin(loginEvent());

            assertEquals(List.of("pre-login-worker"), registrationThreads, "onPlayerLogin must not register");
            assertEquals(1, TestPlugin.firedEvents().stream().filter(RegisterEvent.class::isInstance).count());
            assertEquals(1, TestPlugin.firedEvents().stream().filter(LoginEvent.class::isInstance).count());
        }
    }

    @Test
    void passwordOnceCommitRunsOnCallingThreadAndSchedulesMainCallbacks() throws Exception {
        try (Storage storage = new Storage(temporaryDirectory.resolve("password-once-main.db"))) {
            VerifiedSessions sessions = new VerifiedSessions();
            sessions.put(passwordOnceSession());
            List<Runnable> mainCallbacks = new ArrayList<>();
            AuthState authState = new AuthState();
            AuthMeLoginListener listener = new AuthMeLoginListener(plugin(), authState, storage, sessions,
                ignored -> { }, ignored -> { }, name -> true, (name, password) -> true,
                (name, address) -> false, mainCallbacks::add);
            List<String> kicks = new ArrayList<>();
            AtomicReference<String> callerThread = new AtomicReference<>();
            AtomicReference<String> classification = new AtomicReference<>();
            AtomicBoolean granted = new AtomicBoolean();

            Thread worker = new Thread(() -> {
                callerThread.set(Thread.currentThread().getName());
                granted.set(listener.onPasswordLogin(player(kicks)));
                classification.set(storage.classification("Notch").join().orElse(null));
            }, "password-worker");
            worker.start();
            worker.join();

            assertTrue(granted.get());
            assertEquals("password-worker", callerThread.get());
            assertEquals("PREMIUM", classification.get(), "commit must finish on the calling thread");
            assertEquals(3, mainCallbacks.size(), "auth state, release and event must be queued for main");
            assertFalse(authState.isAuthenticated("notch"), "no auth side effect before main callbacks run");
            assertTrue(kicks.isEmpty(), kicks.toString());
            assertTrue(TestPlugin.firedEvents().isEmpty(), "events must not fire on the worker thread");

            mainCallbacks.forEach(Runnable::run);

            assertTrue(authState.isAuthenticated("notch"));
            assertTrue(kicks.isEmpty(), kicks.toString());
            assertEquals(1, TestPlugin.firedEvents().stream().filter(LoginEvent.class::isInstance).count());
        }
    }

    @Test
    void passwordOnceCommitFailureQueuesKickAndLogout() throws Exception {
        Storage storage = new Storage(temporaryDirectory.resolve("password-once-main-fail.db"));
        storage.close();
        VerifiedSessions sessions = new VerifiedSessions();
        sessions.put(passwordOnceSession());
        List<Runnable> mainCallbacks = new ArrayList<>();
        AuthState authState = new AuthState();
        AuthMeLoginListener listener = new AuthMeLoginListener(plugin(), authState, storage, sessions,
            ignored -> { }, ignored -> { }, name -> true, (name, password) -> true,
            (name, address) -> false, mainCallbacks::add);
        List<String> kicks = new ArrayList<>();

        assertFalse(listener.onPasswordLogin(player(kicks)));
        assertEquals(2, mainCallbacks.size(), "kick and logout must be queued for main");
        assertTrue(kicks.isEmpty(), kicks.toString());

        mainCallbacks.forEach(Runnable::run);

        assertEquals(1, kicks.size());
        assertFalse(authState.isAuthenticated("notch"));
        assertTrue(TestPlugin.firedEvents().stream().noneMatch(fired -> fired instanceof LoginEvent));
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
        return listener(new AuthState(), storage, sessions, released, grant, isRegistered, register);
    }

    private static AuthMeLoginListener listener(AuthState authState, Storage storage, VerifiedSessions sessions,
                                                Consumer<String> released, Consumer<String> grant) {
        return listener(authState, storage, sessions, released, grant, name -> true,
            (name, password) -> true);
    }

    private static AuthMeLoginListener listener(AuthState authState, Storage storage, VerifiedSessions sessions,
                                                Consumer<String> released, Consumer<String> grant,
                                                Predicate<String> isRegistered,
                                                BiPredicate<String, String> register) {
        return new AuthMeLoginListener(plugin(), authState, storage, sessions, released, grant, isRegistered, register,
            (name, address) -> false, Runnable::run);
    }

    private static AsyncPlayerPreLoginEvent event() throws Exception {
        return event("Notch");
    }

    private static AsyncPlayerPreLoginEvent event(String name) throws Exception {
        return event(name, UUID.randomUUID());
    }

    private static AsyncPlayerPreLoginEvent event(String name, UUID uniqueId) throws Exception {
        // Supplying a profile avoids Bukkit.createProfile(), which needs a live Server.
        com.destroystokyo.paper.profile.PlayerProfile profile = (com.destroystokyo.paper.profile.PlayerProfile)
            Proxy.newProxyInstance(com.destroystokyo.paper.profile.PlayerProfile.class.getClassLoader(),
                new Class<?>[]{com.destroystokyo.paper.profile.PlayerProfile.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> name;
                    case "getId" -> uniqueId;
                    default -> null;
                });
        return new AsyncPlayerPreLoginEvent(name, InetAddress.getByName("127.0.0.1"), uniqueId, true, profile);
    }

    private static Player player() {
        return player(new ArrayList<>());
    }

    private static Player player(List<String> kicks) {
        return player("Notch", new InetSocketAddress("127.0.0.1", 25565), kicks);
    }

    private static Player player(String name, InetSocketAddress address, List<String> kicks) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getName" -> name;
                case "getAddress" -> address;
                case "kick" -> {
                    kicks.add("kick:" + MiniMessage.miniMessage().serialize((Component) args[0]));
                    yield null;
                }
                case "kickPlayer" -> {
                    kicks.add("kickPlayer:" + args[0]);
                    yield null;
                }
                default -> null;
            });
    }

    @SuppressWarnings("unchecked")
    private static Set<String> pendingRegistrations(AuthMeLoginListener listener) throws Exception {
        Field field = AuthMeLoginListener.class.getDeclaredField("pendingRegistrations");
        field.setAccessible(true);
        return (Set<String>) field.get(listener);
    }

    private static VerifiedSession session() {
        return session(false, false);
    }

    private static VerifiedSession session(String name) {
        return new VerifiedSession(
            "connection", name, "127.0.0.1",
            MojangClient.SessionResult.verified(UUID.fromString(UUID_TEXT), name),
            Optional.empty(), false, false, false, true, "AUTO");
    }

    private static VerifiedSession passwordOnceSession() {
        return session(false, true);
    }

    private static VerifiedSession invalidVerificationSession() {
        return new VerifiedSession(
            "connection",
            "Notch",
            "127.0.0.1",
            new MojangClient.SessionResult(MojangClient.SessionStatus.NOT_VERIFIED,
                UUID.fromString(UUID_TEXT), "Notch", "not-verified"),
            Optional.empty(),
            false,
            false,
            true,
            false,
            "PASSWORD_ONCE"
        );
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
            requiresPasswordOnce ? "PASSWORD_ONCE" : "AUTO"
        );
    }

    /** Disabled plugin stub with a recording server; no MockBukkit, no vendor singleton. */
    private static Plugin plugin() {
        TestPlugin plugin = TestPlugin.create();
        plugin.getConfig().set("messages.account-conflict", "<red>account-conflict</red>");
        return plugin;
    }

    /** Same stub, with MockBukkit's server injected so the default ban check sees real ban lists. */
    private static TestPlugin mockBukkitPlugin() throws Exception {
        TestPlugin plugin = TestPlugin.create();
        Field server = JavaPlugin.class.getDeclaredField("server");
        server.setAccessible(true);
        server.set(plugin, Bukkit.getServer());
        plugin.getConfig().set("messages.account-conflict", "<red>account-conflict</red>");
        return plugin;
    }
}
