package com.hizeric.logincore.command;

import com.hizeric.logincore.TestPlugin;
import com.hizeric.logincore.command.PasswordCommandTestSupport.Recorder;
import com.hizeric.logincore.storage.Storage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChangepasswordCommandTest {

    @TempDir
    Path temporaryDirectory;

    private Storage storage;
    private ChangepasswordCommand command;

    @BeforeEach
    void setUp() throws Exception {
        storage = new Storage(temporaryDirectory.resolve("changepassword.db"));
        storage.registerPlayer("OfflineGuy", "secret12").get();
        command = new ChangepasswordCommand(TestPlugin.create(), Runnable::run, storage);
    }

    @AfterEach
    void tearDown() {
        storage.close();
    }

    @Test
    void changesPasswordAfterVerifyingTheOldOne() throws Exception {
        Recorder player = PasswordCommandTestSupport.player("OfflineGuy");

        assertTrue(command.onCommand(player.player(), null, "changepassword",
            new String[]{"secret12", "newsecret"}));

        assertFalse(storage.verifyPassword("OfflineGuy", "secret12").get());
        assertTrue(storage.verifyPassword("OFFLINEGUY", "newsecret").get());
        assertTrue(player.said("password changed"), player.transcript());
    }

    @Test
    void wrongOldPasswordLeavesThePasswordAlone() throws Exception {
        Recorder player = PasswordCommandTestSupport.player("OfflineGuy");

        assertTrue(command.onCommand(player.player(), null, "changepassword",
            new String[]{"wrongold", "newsecret"}));

        assertTrue(storage.verifyPassword("OfflineGuy", "secret12").get());
        assertFalse(storage.verifyPassword("OfflineGuy", "newsecret").get());
        assertTrue(player.said("incorrect"), player.transcript());
    }

    @Test
    void shortNewPasswordIsRejected() throws Exception {
        Recorder player = PasswordCommandTestSupport.player("OfflineGuy");

        assertTrue(command.onCommand(player.player(), null, "changepassword",
            new String[]{"secret12", "short"}));

        assertTrue(storage.verifyPassword("OfflineGuy", "secret12").get());
        assertTrue(player.said("6 and 72 bytes"), player.transcript());
    }

    @Test
    void premiumClassificationRejectsPasswordChange() throws Exception {
        storage.setClassification("PremiumGuy", "PREMIUM").get();
        storage.registerPlayer("PremiumGuy", "secret12").get();
        Recorder player = PasswordCommandTestSupport.player("PremiumGuy");

        assertTrue(command.onCommand(player.player(), null, "changepassword",
            new String[]{"secret12", "newsecret"}));

        assertTrue(storage.verifyPassword("PremiumGuy", "secret12").get());
        assertTrue(player.transcript().toLowerCase().contains("premium accounts"), player.transcript());
    }

    @Test
    void tabCompletionNeverSuggestsPasswords() {
        assertTrue(command.onTabComplete(PasswordCommandTestSupport.player("OfflineGuy").player(), null,
            "changepassword", new String[]{""}).isEmpty());
    }
}
