package com.hizeric.logincore.command;

import com.hizeric.logincore.TestPlugin;
import com.hizeric.logincore.command.PasswordCommandTestSupport.Recorder;
import com.hizeric.logincore.mojang.LookupResult;
import com.hizeric.logincore.session.AuthState;
import com.hizeric.logincore.storage.Storage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegisterCommandTest {

    @TempDir
    Path temporaryDirectory;

    private Storage storage;
    private AuthState state;
    private RegisterCommand command;

    @BeforeEach
    void setUp() throws Exception {
        storage = new Storage(temporaryDirectory.resolve("register.db"));
        state = new AuthState();
        command = commandWithLookup((name, ip) -> LookupResult.notPremium());
    }

    @AfterEach
    void tearDown() {
        storage.close();
    }

    private RegisterCommand commandWithLookup(BiFunction<String, String, LookupResult> lookup) {
        return new RegisterCommand(TestPlugin.create(), Runnable::run, storage, state, "ENFORCE", lookup);
    }

    @Test
    void registersOfflineAccountAndAutoLogsIn() throws Exception {
        storage.setClassification("OfflineGuy", "OFFLINE").get();
        Recorder player = PasswordCommandTestSupport.player("OfflineGuy");

        assertTrue(command.onCommand(player.player(), null, "register", new String[]{"secret12", "secret12"}));

        assertTrue(state.isAuthenticated("offlineguy"));
        assertTrue(storage.verifyPassword("OFFLINEGUY", "secret12").get());
        assertTrue(player.said("registered"), player.transcript());
    }

    @Test
    void mismatchedPasswordsDoNotRegister() throws Exception {
        Recorder player = PasswordCommandTestSupport.player("MismatchGuy");

        assertTrue(command.onCommand(player.player(), null, "register", new String[]{"secret12", "secret13"}));

        assertFalse(state.isAuthenticated("mismatchguy"));
        assertFalse(storage.verifyPassword("MismatchGuy", "secret12").get());
        assertTrue(player.said("do not match"), player.transcript());
    }

    @Test
    void shortAndOversizedPasswordsAreRejected() throws Exception {
        Recorder shortPlayer = PasswordCommandTestSupport.player("ShortGuy");
        assertTrue(command.onCommand(shortPlayer.player(), null, "register", new String[]{"short", "short"}));
        assertFalse(storage.verifyPassword("ShortGuy", "short").get());
        assertTrue(shortPlayer.said("6 and 72 bytes"), shortPlayer.transcript());

        String tooLong = "x".repeat(73);
        Recorder longPlayer = PasswordCommandTestSupport.player("LongGuy");
        assertTrue(command.onCommand(longPlayer.player(), null, "register", new String[]{tooLong, tooLong}));
        assertFalse(storage.verifyPassword("LongGuy", tooLong).get());
        assertTrue(longPlayer.said("6 and 72 bytes"), longPlayer.transcript());
    }

    @Test
    void alreadyRegisteredNameIsNotOverwrittenOrAutoLoggedIn() throws Exception {
        storage.registerPlayer("ExistingGuy", "oldpass1").get();
        Recorder player = PasswordCommandTestSupport.player("ExistingGuy");

        assertTrue(command.onCommand(player.player(), null, "register", new String[]{"newpass1", "newpass1"}));

        assertFalse(state.isAuthenticated("existingguy"));
        assertTrue(storage.verifyPassword("ExistingGuy", "oldpass1").get());
        assertFalse(storage.verifyPassword("ExistingGuy", "newpass1").get());
        assertTrue(player.said("already registered"), player.transcript());
    }

    @Test
    void premiumClassificationRejectsRegistration() throws Exception {
        storage.setClassification("PremiumGuy", "PREMIUM").get();
        Recorder player = PasswordCommandTestSupport.player("PremiumGuy");

        assertTrue(command.onCommand(player.player(), null, "register", new String[]{"secret12", "secret12"}));

        assertFalse(state.isAuthenticated("premiumguy"));
        assertFalse(storage.verifyPassword("PremiumGuy", "secret12").get());
        assertTrue(player.transcript().toLowerCase().contains("premium accounts"), player.transcript());
    }

    @Test
    void unclassifiedRegistrationRequiresNotPremiumLookup() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        command = commandWithLookup((name, ip) -> {
            calls.incrementAndGet();
            return LookupResult.notPremium();
        });
        Recorder player = PasswordCommandTestSupport.player("FreshGuy");

        assertTrue(command.onCommand(player.player(), null, "register", new String[]{"secret12", "secret12"}));

        assertEquals(1, calls.get());
        assertTrue(storage.verifyPassword("FRESHGUY", "secret12").get());
        assertTrue(state.isAuthenticated("freshguy"));
        assertEquals("OFFLINE", storage.classification("FreshGuy").get().orElseThrow());
        assertTrue(player.said("registered"), player.transcript());
    }

    @Test
    void premiumLookupRefusesUnclassifiedRegistration() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        command = commandWithLookup((name, ip) -> {
            calls.incrementAndGet();
            return LookupResult.premium(UUID.randomUUID(), "Real");
        });
        Recorder player = PasswordCommandTestSupport.player("Namesake");

        assertTrue(command.onCommand(player.player(), null, "register", new String[]{"secret12", "secret12"}));

        assertEquals(1, calls.get());
        assertFalse(storage.verifyPassword("Namesake", "secret12").get());
        assertTrue(storage.classification("Namesake").get().isEmpty());
        assertFalse(state.isAuthenticated("namesake"));
        assertTrue(player.transcript().toLowerCase().contains("premium accounts"), player.transcript());
    }

    @Test
    void unknownLookupRefusesUnclassifiedRegistration() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        command = commandWithLookup((name, ip) -> {
            calls.incrementAndGet();
            return LookupResult.unknown("timeout");
        });
        Recorder player = PasswordCommandTestSupport.player("TimeoutGuy");

        assertTrue(command.onCommand(player.player(), null, "register", new String[]{"secret12", "secret12"}));

        assertEquals(1, calls.get());
        assertFalse(storage.verifyPassword("TimeoutGuy", "secret12").get());
        assertTrue(storage.classification("TimeoutGuy").get().isEmpty());
        assertFalse(state.isAuthenticated("timeoutguy"));
        assertTrue(player.said("verify"), player.transcript());
    }

    @Test
    void storedPremiumRefusesRegistration() throws Exception {
        storage.setClassification("PremiumGuy", "PREMIUM").get();
        AtomicInteger calls = new AtomicInteger();
        command = commandWithLookup((name, ip) -> {
            calls.incrementAndGet();
            return LookupResult.notPremium();
        });
        Recorder player = PasswordCommandTestSupport.player("PremiumGuy");

        assertTrue(command.onCommand(player.player(), null, "register", new String[]{"secret12", "secret12"}));

        assertEquals(0, calls.get());
        assertFalse(storage.verifyPassword("PremiumGuy", "secret12").get());
        assertEquals("PREMIUM", storage.classification("PremiumGuy").get().orElseThrow());
        assertFalse(state.isAuthenticated("premiumguy"));
    }

    @Test
    void storedOfflineRegistrationSucceedsWithoutLookup() throws Exception {
        storage.setClassification("OfflineGuy", "OFFLINE").get();
        AtomicInteger calls = new AtomicInteger();
        command = commandWithLookup((name, ip) -> {
            calls.incrementAndGet();
            return LookupResult.notPremium();
        });
        Recorder player = PasswordCommandTestSupport.player("OfflineGuy");

        assertTrue(command.onCommand(player.player(), null, "register", new String[]{"secret12", "secret12"}));

        assertEquals(0, calls.get());
        assertTrue(storage.verifyPassword("OfflineGuy", "secret12").get());
        assertEquals("OFFLINE", storage.classification("OfflineGuy").get().orElseThrow());
    }

    @Test
    void shadowModeAllowsUnclassifiedRegistration() throws Exception {
        command = new RegisterCommand(TestPlugin.create(), Runnable::run, storage, state, "SHADOW",
            (name, ip) -> {
                throw new AssertionError("SHADOW mode must not look up names");
            });
        Recorder player = PasswordCommandTestSupport.player("ShadowGuy");

        assertTrue(command.onCommand(player.player(), null, "register", new String[]{"secret12", "secret12"}));

        assertTrue(storage.verifyPassword("ShadowGuy", "secret12").get());
        assertTrue(storage.classification("ShadowGuy").get().isEmpty());
        assertTrue(state.isAuthenticated("shadowguy"));
    }

    @Test
    void tabCompletionNeverSuggestsPasswords() {
        assertTrue(command.onTabComplete(PasswordCommandTestSupport.player("OfflineGuy").player(), null,
            "register", new String[]{""}).isEmpty());
    }
}
