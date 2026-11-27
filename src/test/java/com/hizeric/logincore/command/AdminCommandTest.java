package com.hizeric.logincore.command;

import com.hizeric.logincore.TestPlugin;
import com.hizeric.logincore.handshake.LoginMetrics;
import com.hizeric.logincore.migration.MigrationAudit;
import com.hizeric.logincore.storage.Storage;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Constructor;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminCommandTest {

    private static final String UUID_TEXT = "069a79f4-44e9-4726-a5be-fca90e38a0e1";

    @TempDir
    Path temporaryDirectory;

    @Test
    void noCommandMutatesIdentityType() {
        AdminCommand command = new AdminCommand(null, null, null, null, null, null);
        CommandSender sender = (CommandSender) Proxy.newProxyInstance(
            CommandSender.class.getClassLoader(), new Class<?>[]{CommandSender.class},
            (proxy, method, args) -> "hasPermission".equals(method.getName()) ? Boolean.TRUE : null);

        // The command's own root arg list, as exposed by tab completion, is the registered subcommand set.
        assertEquals(List.of("status", "migrate"),
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

    @Test
    void everySubcommandLeavesStoredClassificationUntouched() throws Exception {
        try (Storage storage = new Storage(temporaryDirectory.resolve("commands.db"))) {
            storage.setClassification("BaselineGuy", "OFFLINE").get();
            MigrationAudit audit = audit(storage, temporaryDirectory.resolve("data"));
            ExecutorService workers = Executors.newSingleThreadExecutor();
            try {
                AdminCommand command = new AdminCommand(TestPlugin.create(), storage, null,
                    audit, workers, new LoginMetrics());
                CommandSender sender = sender();
                for (String[] args : new String[][]{
                    {"status"},
                    {"migrate", "audit"},
                    {"migrate", "classify"}
                }) {
                    assertTrue(command.onCommand(sender, null, "logincore", args));
                    assertEquals("OFFLINE", storage.classification("BaselineGuy").get().orElseThrow(),
                        Arrays.toString(args));
                    assertTrue(storage.classification("FreshGuy").get().isEmpty(), Arrays.toString(args));
                }
                assertEquals(1, storage.summary().get().classifications(),
                    "no subcommand may add or change a classification row");
            } finally {
                workers.shutdownNow();
                audit.close();
            }
        }
    }

    @Test
    void everyAdminSubcommandPreservesPremiumBaseline() throws Exception {
        try (Storage storage = new Storage(temporaryDirectory.resolve("premium-commands.db"))) {
            storage.setClassification("PremiumGuy", "PREMIUM").get();
            MigrationAudit audit = audit(storage, temporaryDirectory.resolve("premium-data"));
            ExecutorService workers = Executors.newSingleThreadExecutor();
            try {
                AdminCommand command = new AdminCommand(TestPlugin.create(), storage, null,
                    audit, workers, new LoginMetrics());
                CommandSender sender = sender();
                for (String[] args : new String[][]{
                    {"status"},
                    {"migrate", "audit"},
                    {"migrate", "classify"}
                }) {
                    assertTrue(command.onCommand(sender, null, "logincore", args));
                    for (String variant : new String[]{"PremiumGuy", "PREMIUMGUY", "premiumguy"}) {
                        assertEquals("PREMIUM", storage.classification(variant).get().orElseThrow(),
                            Arrays.toString(args));
                    }
                    assertTrue(storage.classification("FreshGuy").get().isEmpty(), Arrays.toString(args));
                }
                assertEquals(1, storage.summary().get().classifications(),
                    "no subcommand may add or change a classification row");
            } finally {
                workers.shutdownNow();
                audit.close();
            }
        }
    }

    @Test
    void migrateClassifyOnlyWritesPremiumForLinkedNames() throws Exception {
        try (Storage storage = new Storage(temporaryDirectory.resolve("classify.db"))) {
            storage.putLink("LinkedGuy", UUID_TEXT, "LinkedGuy", "AUTO").get();
            storage.setClassification("OfflineGuy", "OFFLINE").get();
            storage.registerPlayer("UnlinkedGuy", "password").get();
            MigrationAudit audit = audit(storage, temporaryDirectory.resolve("data"));
            ExecutorService workers = Executors.newSingleThreadExecutor();
            try {
                AdminCommand command = new AdminCommand(TestPlugin.create(), storage, null,
                    audit, workers, new LoginMetrics());

                assertTrue(command.onCommand(sender(), null, "logincore", new String[]{"migrate", "classify"}));

                assertEquals("PREMIUM", storage.classification("LinkedGuy").get().orElseThrow());
                assertEquals("OFFLINE", storage.classification("OfflineGuy").get().orElseThrow());
                assertTrue(storage.classification("UnlinkedGuy").get().isEmpty(),
                    "an unlinked name stays unclassified");
                assertTrue(storage.classification("FreshGuy").get().isEmpty());
                assertEquals(2, storage.summary().get().classifications(), "migrate classify never writes OFFLINE");
            } finally {
                workers.shutdownNow();
                audit.close();
            }
        }
    }

    private static MigrationAudit audit(Storage storage, Path dataFolder) throws Exception {
        // The apply/audit seam needs only a data folder and storage; no AuthMe singleton.
        Constructor<MigrationAudit> constructor =
            MigrationAudit.class.getDeclaredConstructor(Path.class, Storage.class);
        constructor.setAccessible(true);
        return constructor.newInstance(dataFolder, storage);
    }

    private static CommandSender sender() {
        return (CommandSender) Proxy.newProxyInstance(CommandSender.class.getClassLoader(),
            new Class<?>[]{CommandSender.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "hasPermission" -> Boolean.TRUE;
                case "getName" -> "operator";
                default -> null;
            });
    }
}
