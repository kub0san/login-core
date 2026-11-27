package com.hizeric.logincore;

import com.hizeric.logincore.decision.LoginDecisionEngine;
import com.hizeric.logincore.mojang.LookupResult;
import com.hizeric.logincore.storage.Storage;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoginCoreConfigurationTest {

    private static final Path CONFIG_FILE = Path.of("src", "main", "resources", "config.yml");
    private static final Pattern KEY = Pattern.compile("^(\\s*)([A-Za-z0-9_.-]+):\\s*(.*)$");
    private static final UUID PREMIUM_UUID = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38a0e1");

    @TempDir
    Path temporaryDirectory;

    @Test
    void adoptPolicyIsRejectedAtStartup() {
        assertFalse(LoginCore.validExistingAccountPolicy("ADOPT"));
        assertTrue(LoginCore.validExistingAccountPolicy("REQUIRE_PASSWORD_ONCE"));
        assertTrue(LoginCore.validExistingAccountPolicy("DENY"));
    }

    /** Case 11: config is read at decision time; no key can write or rewrite a stored type. */
    @Test
    void configKeysNeverChangeStoredClassification() throws Exception {
        List<String> keys = leafKeys();
        assertTrue(keys.containsAll(List.of("mode", "on-lookup-failure", "existing-account-policy",
            "use-premium-uuid", "debug", "throttle.per-ip-new-names-per-minute",
            "throttle.max-pending-handshakes-per-ip", "mojang.lookup-url", "mojang.session-url")), keys.toString());

        try (Storage storage = new Storage(temporaryDirectory.resolve("config.db"))) {
            storage.setClassification("PremiumGuy", "PREMIUM").get();
            storage.setClassification("OfflineGuy", "OFFLINE").get();

            for (String key : keys) {
                FileConfiguration config = loadConfig();
                config.set(key, flipped(config, key));

                // These are the only config reads that reach a decision; every other key
                // (mojang.*, throttle.*, debug, use-premium-uuid) is read, never persisted as a type.
                boolean shadow = "SHADOW".equalsIgnoreCase(config.getString("mode", "ENFORCE"));
                boolean allow = "ALLOW".equalsIgnoreCase(config.getString("on-lookup-failure", "DENY"));
                assertEquals("mode".equals(key), shadow, key);
                assertEquals("on-lookup-failure".equals(key), allow, key);

                decide(premiumInput(shadow, allow));
                decide(offlineInput(shadow, allow));
                decide(freshInput(shadow, allow));

                assertEquals("PREMIUM", storage.classification("PremiumGuy").get().orElseThrow(), key);
                assertEquals("OFFLINE", storage.classification("OfflineGuy").get().orElseThrow(), key);
                assertTrue(storage.classification("FreshGuy").get().isEmpty(), key);
            }
            assertEquals(2, storage.summary().get().classifications());
        }
    }

    private static LoginDecisionEngine.DecisionInput premiumInput(boolean shadow, boolean allow) {
        return new LoginDecisionEngine.DecisionInput("PremiumGuy", false, true,
            LookupResult.unknown("outage"), false, null, null, false, shadow, allow, "PREMIUM");
    }

    private static LoginDecisionEngine.DecisionInput offlineInput(boolean shadow, boolean allow) {
        return new LoginDecisionEngine.DecisionInput("OfflineGuy", false, true,
            LookupResult.premium(PREMIUM_UUID, "OfflineGuy"), false, null, null, false, shadow, allow, "OFFLINE");
    }

    private static LoginDecisionEngine.DecisionInput freshInput(boolean shadow, boolean allow) {
        return new LoginDecisionEngine.DecisionInput("FreshGuy", false, true,
            LookupResult.unknown("outage"), false, null, null, false, shadow, allow, null);
    }

    private static void decide(LoginDecisionEngine.DecisionInput input) {
        LoginDecisionEngine.Decision decision = LoginDecisionEngine.decide(input);
        // Even when ALLOW turns an UNKNOWN into PASS_THROUGH, no OFFLINE write is authorised.
        assertFalse(LoginDecisionEngine.shouldClassifyOffline(decision, input.lookupResult(), false));
    }

    private static FileConfiguration loadConfig() throws Exception {
        return YamlConfiguration.loadConfiguration(
            new StringReader(Files.readString(CONFIG_FILE, StandardCharsets.UTF_8)));
    }

    private static Object flipped(FileConfiguration config, String key) {
        Object current = config.get(key);
        if (current instanceof Boolean value) {
            return !value;
        }
        if (current instanceof Number value) {
            return value.intValue() + 1;
        }
        return switch (key) {
            case "mode" -> "SHADOW";
            case "on-lookup-failure" -> "ALLOW";
            case "existing-account-policy" -> "DENY";
            default -> "flipped-" + current;
        };
    }

    private static List<String> leafKeys() throws Exception {
        Deque<Map.Entry<Integer, String>> parents = new ArrayDeque<>();
        List<String> keys = new ArrayList<>();
        for (String line : Files.readAllLines(CONFIG_FILE, StandardCharsets.UTF_8)) {
            Matcher matcher = KEY.matcher(line);
            if (!matcher.matches()) {
                continue;
            }
            int indent = matcher.group(1).length();
            while (!parents.isEmpty() && parents.peek().getKey() >= indent) {
                parents.pop();
            }
            String path = parents.isEmpty() ? matcher.group(2)
                : parents.peek().getValue() + "." + matcher.group(2);
            parents.push(Map.entry(indent, path));
            if (!matcher.group(3).isBlank()) {
                keys.add(path);
            }
        }
        return keys;
    }
}
