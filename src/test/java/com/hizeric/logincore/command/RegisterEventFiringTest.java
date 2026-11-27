package com.hizeric.logincore.command;

import com.hizeric.logincore.TestPlugin;
import com.hizeric.logincore.command.PasswordCommandTestSupport.Recorder;
import com.hizeric.logincore.events.RegisterEvent;
import com.hizeric.logincore.session.AuthState;
import com.hizeric.logincore.storage.Storage;
import org.bukkit.event.Event;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegisterEventFiringTest {

    @TempDir
    Path temporaryDirectory;

    private Storage storage;
    private RegisterCommand command;

    @BeforeEach
    void setUp() throws Exception {
        storage = new Storage(temporaryDirectory.resolve("register-event.db"));
        command = new RegisterCommand(TestPlugin.create(), Runnable::run, storage, new AuthState(), "SHADOW",
            (name, ip) -> {
                throw new AssertionError("SHADOW mode must not look up names");
            });
    }

    @AfterEach
    void tearDown() {
        storage.close();
    }

    @Test
    void successfulRegistrationFiresRegisterEvent() {
        Recorder player = PasswordCommandTestSupport.player("EventGuy");

        assertTrue(command.onCommand(player.player(), null, "register", new String[]{"secret12", "secret12"}));

        List<Event> fired = TestPlugin.firedEvents();
        assertEquals(1, fired.size(), fired.toString());
        assertSame(player.player(), ((RegisterEvent) fired.get(0)).getPlayer());
        assertSame(RegisterEvent.getHandlerList(), fired.get(0).getHandlers());
    }

    @Test
    void failedRegistrationDoesNotFireRegisterEvent() {
        Recorder player = PasswordCommandTestSupport.player("MismatchGuy");

        assertTrue(command.onCommand(player.player(), null, "register", new String[]{"secret12", "secret13"}));

        assertTrue(TestPlugin.firedEvents().isEmpty());
    }
}
