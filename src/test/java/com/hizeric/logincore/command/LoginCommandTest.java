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
    void rejectedPasswordHookSkipsGrantAndSuccessMessage() {
        Recorder player = PasswordCommandTestSupport.player("OfflineGuy");
        LoginCommand hooked = new LoginCommand(TestPlugin.create(), Runnable::run, storage, state,
            candidate -> false);

        assertTrue(hooked.onCommand(player.player(), null, "login", new String[]{"secret12"}));

        assertFalse(state.isAuthenticated("offlineguy"));
        assertTrue(player.transcript().isEmpty(), player.transcript());
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
    void fiveFailuresWithinWindowKickAndClearTheCounter() {
        Recorder player = PasswordCommandTestSupport.player("OfflineGuy");

        for (int attempt = 1; attempt <= 5; attempt++) {
            assertTrue(command.onCommand(player.player(), null, "login", new String[]{"wrongpass"}));
            assertEquals(attempt < 5 ? 0 : 1, player.kicks().size(), player.transcript());
        }

        assertFalse(state.isAuthenticated("offlineguy"));
        assertEquals(1, player.kicks().size());
        assertTrue(player.kicks().get(0).toLowerCase().contains("too many"), player.kicks().toString());
        assertEquals(4, player.messages().size(), player.transcript());
    }

    @Test
    void successfulLoginAfterKickWorksBecauseCounterWasCleared() {
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
