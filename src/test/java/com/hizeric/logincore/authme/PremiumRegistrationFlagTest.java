package com.hizeric.logincore.authme;

import com.hizeric.logincore.TestPlugin;
import com.hizeric.logincore.events.LoginEvent;
import com.hizeric.logincore.events.RegisterEvent;
import com.hizeric.logincore.mojang.MojangClient;
import com.hizeric.logincore.session.AuthState;
import com.hizeric.logincore.session.VerifiedSessions;
import com.hizeric.logincore.session.VerifiedSessions.VerifiedSession;
import com.hizeric.logincore.storage.Storage;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PremiumRegistrationFlagTest {

    private static final String NAME = "PremiumGuy";
    private static final UUID MOJANG_UUID = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38a0e1");

    @TempDir
    Path temporaryDirectory;

    @Test
    void deferredPremiumRegistrationIsLoggedInInTheDatabase() throws Exception {
        Path database = temporaryDirectory.resolve("premium.db");
        try (Storage storage = new Storage(database)) {
            VerifiedSessions sessions = new VerifiedSessions();
            assertTrue(sessions.put(session()));
            AuthMeLoginListener listener = new AuthMeLoginListener(
                plugin(), new AuthState(), storage, sessions, ignored -> { });

            AsyncPlayerPreLoginEvent preLogin = preLoginEvent();
            listener.onAsyncPreLogin(preLogin);
            assertEquals(AsyncPlayerPreLoginEvent.Result.ALLOWED, preLogin.getLoginResult());

            List<String> kicks = new ArrayList<>();
            PlayerLoginEvent login = new PlayerLoginEvent(player(kicks), "127.0.0.1",
                InetAddress.getByName("127.0.0.1"));
            listener.onPlayerLogin(login);
            assertTrue(kicks.isEmpty(), kicks.toString());

            List<Event> fired = TestPlugin.firedEvents();
            assertEquals(2, fired.size(), fired.toString());
            assertEquals(1, fired.stream().filter(RegisterEvent.class::isInstance)
                .filter(event -> NAME.equals(((RegisterEvent) event).getPlayer().getName())).count());
            assertEquals(1, fired.stream().filter(LoginEvent.class::isInstance)
                .filter(event -> NAME.equals(((LoginEvent) event).getPlayer().getName())).count());

            assertEquals(1, loggedFlag(database),
                "a deferred premium registration must be persisted as logged in");
        }
    }

    @Test
    void premiumAutoLoginFiresLoginEvent() throws Exception {
        Path database = temporaryDirectory.resolve("premium-login.db");
        try (Storage storage = new Storage(database)) {
            VerifiedSessions sessions = new VerifiedSessions();
            assertTrue(sessions.put(new VerifiedSession("connection", NAME, "127.0.0.1",
                MojangClient.SessionResult.verified(MOJANG_UUID, NAME), Optional.empty(),
                false, false, false, true, "AUTO")));
            AuthState authState = new AuthState();
            AuthMeLoginListener listener = new AuthMeLoginListener(
                plugin(), authState, storage, sessions, ignored -> { });

            AsyncPlayerPreLoginEvent preLogin = preLoginEvent();
            listener.onAsyncPreLogin(preLogin);
            assertEquals(AsyncPlayerPreLoginEvent.Result.ALLOWED, preLogin.getLoginResult());
            assertTrue(authState.isAuthenticated(NAME), "auto-login must authenticate in memory");

            List<String> kicks = new ArrayList<>();
            PlayerLoginEvent login = new PlayerLoginEvent(player(kicks), "127.0.0.1",
                InetAddress.getByName("127.0.0.1"));
            listener.onPlayerLogin(login);
            assertTrue(kicks.isEmpty(), kicks.toString());

            assertEquals(1, TestPlugin.firedEvents().stream().filter(LoginEvent.class::isInstance)
                .filter(event -> NAME.equals(((LoginEvent) event).getPlayer().getName())).count());
            assertTrue(TestPlugin.firedEvents().stream().noneMatch(RegisterEvent.class::isInstance),
                "an already registered premium account must not fire a register event");
        }
    }

    private static VerifiedSession session() {
        return new VerifiedSession("connection", NAME, "127.0.0.1",
            MojangClient.SessionResult.verified(MOJANG_UUID, NAME), Optional.empty(),
            false, true, false, true, "AUTO");
    }

    private static AsyncPlayerPreLoginEvent preLoginEvent() throws Exception {
        // Supplying a profile avoids Bukkit.createProfile(), which needs a live Server.
        com.destroystokyo.paper.profile.PlayerProfile profile =
            (com.destroystokyo.paper.profile.PlayerProfile) Proxy.newProxyInstance(
                com.destroystokyo.paper.profile.PlayerProfile.class.getClassLoader(),
                new Class<?>[]{com.destroystokyo.paper.profile.PlayerProfile.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> NAME;
                    case "getId" -> MOJANG_UUID;
                    default -> null;
                });
        return new AsyncPlayerPreLoginEvent(NAME, InetAddress.getByName("127.0.0.1"), MOJANG_UUID, true, profile);
    }

    private static Player player(List<String> kicks) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getName" -> NAME;
                case "getAddress" -> new InetSocketAddress("127.0.0.1", 25565);
                case "kick" -> { kicks.add("kick"); yield null; }
                case "kickPlayer" -> { kicks.add("kickPlayer"); yield null; }
                default -> null;
            });
    }

    private static int loggedFlag(Path database) throws Exception {
        Class.forName("org.sqlite.JDBC");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
             PreparedStatement statement = connection.prepareStatement(
                 "SELECT isLogged FROM authme WHERE username = ? COLLATE NOCASE")) {
            statement.setString(1, NAME);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next(), "the premium registration must create an authme row");
                return result.getInt("isLogged");
            }
        }
    }

    private static TestPlugin plugin() {
        TestPlugin plugin = TestPlugin.create();
        plugin.getConfig().set("messages.account-conflict", "<red>account-conflict</red>");
        return plugin;
    }
}
