package com.hizeric.logincore.command;

import com.hizeric.logincore.TestPlugin;
import com.hizeric.logincore.session.AuthState;
import com.hizeric.logincore.storage.Storage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClassificationImmutabilityTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void publicCommandsLeaveClassificationUnchanged() throws Exception {
        try (Storage storage = new Storage(temporaryDirectory.resolve("public-commands.db"))) {
            storage.setClassification("PremiumGuy", "PREMIUM").get();
            storage.setClassification("OfflineGuy", "OFFLINE").get();
            storage.registerPlayer("PremiumGuy", "secret12").get();
            storage.registerPlayer("OfflineGuy", "secret12").get();

            TestPlugin plugin = TestPlugin.create();
            AuthState state = new AuthState();
            LoginCommand login = new LoginCommand(plugin, Runnable::run, storage, state, player -> true);
            RegisterCommand register = new RegisterCommand(plugin, Runnable::run, storage, state);
            ChangepasswordCommand changepassword = new ChangepasswordCommand(plugin, Runnable::run, storage, state);

            PasswordCommandTestSupport.Recorder premium = PasswordCommandTestSupport.player("PremiumGuy");
            PasswordCommandTestSupport.Recorder offline = PasswordCommandTestSupport.player("OfflineGuy");
            PasswordCommandTestSupport.Recorder fresh = PasswordCommandTestSupport.player("FreshGuy");

            // /login: a correct PREMIUM password is still refused; a wrong OFFLINE password is only a failure.
            assertTrue(login.onCommand(premium.player(), null, "login", new String[]{"secret12"}));
            assertTrue(login.onCommand(offline.player(), null, "login", new String[]{"wrongpass"}));

            // /register: a valid fresh registration and an invalid mismatched-password attempt.
            assertTrue(register.onCommand(fresh.player(), null, "register", new String[]{"secret12", "secret12"}));
            assertTrue(register.onCommand(PasswordCommandTestSupport.player("MismatchGuy").player(), null,
                "register", new String[]{"secret12", "secret13"}));

            // /changepassword: a valid change and an invalid attempt using the now-stale old password.
            state.authenticate("OfflineGuy");
            assertTrue(changepassword.onCommand(offline.player(), null, "changepassword",
                new String[]{"secret12", "newsecret"}));
            assertTrue(changepassword.onCommand(offline.player(), null, "changepassword",
                new String[]{"secret12", "newsecret"}));

            assertEquals("PREMIUM", storage.classification("PremiumGuy").get().orElseThrow());
            assertEquals("OFFLINE", storage.classification("OfflineGuy").get().orElseThrow());
            assertTrue(storage.classification("FreshGuy").get().isEmpty());
            assertTrue(storage.classification("MismatchGuy").get().isEmpty());
            assertEquals(2, storage.summary().get().classifications(),
                "public commands never add a classification row");
            assertEquals(0, storage.summary().get().links(), "public commands never create a premium link");
            assertFalse(state.isAuthenticated("premiumguy"), "PREMIUM must never be password-authenticated");
        }
    }
}
