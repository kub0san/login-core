package com.hizeric.logincore.command;

import com.hizeric.logincore.storage.Storage;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminCommandTest {

    private static final String UUID_TEXT = "069a79f4-44e9-4726-a5be-fca90e38a0e1";

    @TempDir
    Path temporaryDirectory;

    @Test
    void noCommandMutatesIdentityType() {
        AdminCommand command = new AdminCommand(null, null, null, null, null);
        CommandSender sender = (CommandSender) Proxy.newProxyInstance(
            CommandSender.class.getClassLoader(), new Class<?>[]{CommandSender.class},
            (proxy, method, args) -> "hasPermission".equals(method.getName()) ? Boolean.TRUE : null);

        // The command's own root arg list, as exposed by tab completion, is the registered subcommand set.
        assertEquals(List.of("status"),
            command.onTabComplete(sender, null, "logincore", new String[]{""}));
    }

    @Test
    void statusReportsClassificationReadOnly() throws Exception {
        Path database = temporaryDirectory.resolve("status.db");
        try (Storage storage = new Storage(database)) {
            assertEquals(Storage.ClassificationResult.SET,
                storage.setClassification("Notch", "PREMIUM").get());

            String line = AdminCommand.statusLine(storage.account("Notch").get(), false,
                storage.classification("Notch").get());

            assertTrue(line.contains("classification: PREMIUM"), line);
            assertFalse(line.contains("exempt"), line);
            assertEquals("PREMIUM", storage.classification("NOTCH").get().orElseThrow());
            assertEquals(1, storage.summary().get().classifications());
        }
    }

    @Test
    void statusShowsLinkedNameForUuidConflict() throws Exception {
        Path database = temporaryDirectory.resolve("conflict-status.db");
        try (Storage storage = new Storage(database)) {
            storage.putLink("Old", UUID_TEXT, "Old", "AUTO").get();

            String line = AdminCommand.statusLine(storage.account("New").get(), false,
                storage.classification("New").get(), storage.linkByUuid(UUID_TEXT).get());

            assertTrue(line.contains("linked-name: old"), line);
            assertEquals(UUID_TEXT, storage.link("old").get().orElseThrow().mojangUuid(),
                "status display must not rewrite the link");
        }
    }

    @Test
    void statusReportsUnclassifiedWhenNoRowExists() throws Exception {
        Path database = temporaryDirectory.resolve("unclassified.db");
        try (Storage storage = new Storage(database)) {
            String line = AdminCommand.statusLine(storage.account("LegacyGuy").get(), true,
                storage.classification("LegacyGuy").get());

            assertTrue(line.contains("classification: UNCLASSIFIED"), line);
            assertTrue(storage.classification("LEGACYGUY").get().isEmpty());
            assertEquals(0, storage.summary().get().classifications());
        }
    }

}
