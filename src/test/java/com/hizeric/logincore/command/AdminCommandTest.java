package com.hizeric.logincore.command;

import com.hizeric.logincore.storage.Storage;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminCommandTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void noCommandMutatesIdentityType() {
        AdminCommand command = new AdminCommand(null, null, null, null, null, null, null);
        CommandSender sender = (CommandSender) Proxy.newProxyInstance(
            CommandSender.class.getClassLoader(), new Class<?>[]{CommandSender.class},
            (proxy, method, args) -> "hasPermission".equals(method.getName()) ? Boolean.TRUE : null);

        // The command's own root arg list, as exposed by tab completion, is the registered subcommand set.
        assertEquals(List.of("status", "exempt", "migrate"),
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
            assertEquals("PREMIUM", storage.classification("NOTCH").get().orElseThrow());
            assertEquals(1, storage.summary().get().classifications());
        }
    }
}
