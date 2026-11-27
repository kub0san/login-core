package com.hizeric.logincore.command;

import com.hizeric.logincore.TestPlugin;
import com.hizeric.logincore.command.PasswordCommandTestSupport.Recorder;
import com.hizeric.logincore.session.AuthState;
import com.hizeric.logincore.storage.Storage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoginCommandTest {

    @TempDir
    Path temporaryDirectory;

    private Storage storage;
    private AuthState state;
    private LoginCommand command;

    @BeforeEach
    void setUp() throws Exception {
        storage = new Storage(temporaryDirectory.resolve("login.db"));
        storage.registerPlayer("OfflineGuy", "secret12").get();
        state = new AuthState();
        command = new LoginCommand(TestPlugin.create(), Runnable::run, storage, state, player -> true);
    }

    @AfterEach
    void tearDown() {
        storage.close();
    }

    @Test
    void correctPasswordAuthenticatesAndPersistsLogin() {
        Recorder player = PasswordCommandTestSupport.player("OfflineGuy");

        assertTrue(command.onCommand(player.player(), null, "login", new String[]{"secret12"}));

        assertTrue(state.isAuthenticated("offlineguy"));
        assertTrue(player.said("logged in"), player.transcript());
        assertTrue(player.kicks().isEmpty());
    }

    @Test
    void rejectedPasswordHookSkipsGrantAndSuccessMessage() throws Exception {
        Recorder player = PasswordCommandTestSupport.player("OfflineGuy");
        LoginCommand hooked = new LoginCommand(TestPlugin.create(), Runnable::run, storage, state,
            candidate -> false);

        assertTrue(hooked.onCommand(player.player(), null, "login", new String[]{"secret12"}));

        assertFalse(state.isAuthenticated("offlineguy"));
        assertTrue(player.transcript().isEmpty(), player.transcript());
        assertEquals(0, loggedFlag("OfflineGuy"),
            "a rejected password hook must not leave the authme row flagged as logged in");
    }

    @Test
    void passwordHookIsInvokedOffMainThread() throws Exception {
        ExecutorService workers = Executors.newSingleThreadExecutor(task -> new Thread(task, "login-worker"));
        try {
            AtomicReference<String> hookThread = new AtomicReference<>();
            CountDownLatch hookCalled = new CountDownLatch(1);
            LoginCommand offloaded = new LoginCommand(TestPlugin.create(), workers, storage, state, candidate -> {
                hookThread.set(Thread.currentThread().getName());
                hookCalled.countDown();
                return true;
            });
            Recorder player = PasswordCommandTestSupport.player("OfflineGuy");

            assertTrue(offloaded.onCommand(player.player(), null, "login", new String[]{"secret12"}));
            assertTrue(hookCalled.await(5, TimeUnit.SECONDS), "the password hook must run on the worker");
            assertEquals("login-worker", hookThread.get());
        } finally {
            workers.shutdownNow();
        }
    }

    private int loggedFlag(String name) throws Exception {
        Class.forName("org.sqlite.JDBC");
        try (Connection connection = DriverManager.getConnection(
                "jdbc:sqlite:" + temporaryDirectory.resolve("login.db").toAbsolutePath());
             PreparedStatement statement = connection.prepareStatement(
                 "SELECT isLogged FROM authme WHERE username = ? COLLATE NOCASE")) {
            statement.setString(1, name);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next(), "authme row must exist for " + name);
                return result.getInt("isLogged");
            }
        }
    }

    @Test
    void wrongPasswordDoesNotAuthenticate() {
        Recorder player = PasswordCommandTestSupport.player("OfflineGuy");

        assertTrue(command.onCommand(player.player(), null, "login", new String[]{"wrongpass"}));

        assertFalse(state.isAuthenticated("offlineguy"));
        assertTrue(player.said("attempts remaining"), player.transcript());
        assertTrue(player.kicks().isEmpty());
    }

    @Test
    void fiveFailuresWithinWindowKickAndKeepTheCounter() {
        Recorder player = PasswordCommandTestSupport.player("OfflineGuy");

        for (int attempt = 1; attempt <= 5; attempt++) {
            assertTrue(command.onCommand(player.player(), null, "login", new String[]{"wrongpass"}));
            assertEquals(attempt < 5 ? 0 : 1, player.kicks().size(), player.transcript());
        }

        assertFalse(state.isAuthenticated("offlineguy"));
        assertEquals(1, player.kicks().size());
        assertEquals("Too many failed login attempts. Reconnect and try again.", player.kicks().get(0));
        assertEquals(4, player.messages().size(), player.transcript());
        for (int attempt = 1; attempt <= 4; attempt++) {
            assertEquals("Incorrect password. " + (5 - attempt) + " attempts remaining.",
                player.messages().get(attempt - 1), player.transcript());
        }
    }

    @Test
    void reconnectWithinWindowDoesNotGrantFiveNewGuesses() {
        Recorder first = PasswordCommandTestSupport.player("OfflineGuy");
        for (int attempt = 0; attempt < 5; attempt++) {
            command.onCommand(first.player(), null, "login", new String[]{"wrongpass"});
        }
        assertEquals(1, first.kicks().size());

        Recorder reconnected = PasswordCommandTestSupport.player("OfflineGuy");
        assertTrue(command.onCommand(reconnected.player(), null, "login", new String[]{"wrongpass"}));

        assertEquals(1, reconnected.kicks().size(),
            "one wrong password right after a kick must be kicked, not start a fresh 5-guess window");
        assertEquals("Too many failed login attempts. Reconnect and try again.", reconnected.kicks().get(0));
        assertTrue(reconnected.messages().isEmpty(), reconnected.transcript());
    }

    @Test
    void successfulLoginAfterKickStillWorks() {
        Recorder player = PasswordCommandTestSupport.player("OfflineGuy");
        for (int attempt = 0; attempt < 5; attempt++) {
            command.onCommand(player.player(), null, "login", new String[]{"wrongpass"});
        }
        assertEquals(1, player.kicks().size());

        assertTrue(command.onCommand(player.player(), null, "login", new String[]{"secret12"}));

        assertTrue(state.isAuthenticated("offlineguy"));
    }

    @Test
    void premiumClassificationRejectsPasswordLogin() throws Exception {
        storage.setClassification("PremiumGuy", "PREMIUM").get();
        storage.registerPlayer("PremiumGuy", "secret12").get();
        Recorder player = PasswordCommandTestSupport.player("PremiumGuy");

        assertTrue(command.onCommand(player.player(), null, "login", new String[]{"secret12"}));

        assertFalse(state.isAuthenticated("premiumguy"));
        assertTrue(player.transcript().toLowerCase().contains("premium accounts"), player.transcript());
    }

    @Test
    void tabCompletionNeverSuggestsPasswords() {
        assertTrue(command.onTabComplete(PasswordCommandTestSupport.player("OfflineGuy").player(), null,
            "login", new String[]{""}).isEmpty());
    }
}
