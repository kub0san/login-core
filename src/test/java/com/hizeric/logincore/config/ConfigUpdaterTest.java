package com.hizeric.logincore.config;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigUpdaterTest {

    private static final Path BUNDLED = Path.of("src", "main", "resources", "config.yml");
    private static final Logger LOGGER = Logger.getLogger(ConfigUpdaterTest.class.getName());

    @TempDir
    Path temporaryDirectory;

    @Test
    void freshExtractCopiesBundledBytesVerbatim() throws Exception {
        Path config = temporaryDirectory.resolve("config.yml");
        ConfigUpdater.UpdateResult result = reconcile(config);

        assertTrue(result.changed());
        assertArrayEquals(Files.readAllBytes(BUNDLED), Files.readAllBytes(config));
        assertFalse(Files.exists(ConfigUpdater.backupPath(config)));
        assertEquals(1, parse(config).getInt("config-version"));
    }

    @Test
    void missingKeyIsAdded() throws Exception {
        Path config = temporaryDirectory.resolve("config.yml");
        Files.writeString(config, "config-version: 1\nmode: ENFORCE\n", StandardCharsets.UTF_8);

        ConfigUpdater.UpdateResult result = reconcile(config);

        assertTrue(result.changed());
        assertTrue(result.added() >= 1, "bundled leaves should be filled in");
        YamlConfiguration yaml = parse(config);
        assertEquals(BUNDLED_DEFAULT.get("debug"), yaml.get("debug"));
        assertEquals("https://sessionserver.mojang.com/session/minecraft/hasJoined",
            yaml.getString("mojang.session-url"));
        assertEquals("ENFORCE", yaml.getString("mode"));
    }

    @Test
    void obsoleteLeafIsRemoved() throws Exception {
        Path config = temporaryDirectory.resolve("config.yml");
        Files.writeString(config, """
            config-version: 1
            mode: SHADOW
            DataSource:
              backend: SQLITE
              mySQLDatabase: authme
            settings:
              foo: bar
            Security:
              password: secret
            """, StandardCharsets.UTF_8);

        ConfigUpdater.UpdateResult result = reconcile(config);

        assertEquals(4, result.removed());
        YamlConfiguration yaml = parse(config);
        assertFalse(yaml.contains("DataSource.backend"));
        assertFalse(yaml.contains("DataSource.mySQLDatabase"));
        assertFalse(yaml.contains("settings.foo"));
        assertFalse(yaml.contains("Security.password"));
        assertEquals("SHADOW", yaml.getString("mode"));
    }

    @Test
    void adminValueIsPreserved() throws Exception {
        Path config = temporaryDirectory.resolve("config.yml");
        Files.writeString(config, """
            config-version: 1
            mode: SHADOW
            on-lookup-failure: ALLOW
            custom:
              admin-note: keep-me
            """, StandardCharsets.UTF_8);

        reconcile(config);

        YamlConfiguration yaml = parse(config);
        assertEquals("SHADOW", yaml.getString("mode"));
        assertEquals("ALLOW", yaml.getString("on-lookup-failure"));
        assertEquals("keep-me", yaml.getString("custom.admin-note"));
    }

    @Test
    void currentConfigIsNoOp() throws Exception {
        Path config = temporaryDirectory.resolve("config.yml");
        byte[] bundled = Files.readAllBytes(BUNDLED);
        Files.write(config, bundled);

        ConfigUpdater.UpdateResult result = reconcile(config);

        assertFalse(result.changed());
        assertEquals(0, result.added());
        assertEquals(0, result.removed());
        assertArrayEquals(bundled, Files.readAllBytes(config));
        assertFalse(Files.exists(ConfigUpdater.backupPath(config)));
    }

    @Test
    void rewriteCreatesRollingBackup() throws Exception {
        Path config = temporaryDirectory.resolve("config.yml");
        String original = "config-version: 1\nDataSource:\n  backend: SQLITE\n";
        Files.writeString(config, original, StandardCharsets.UTF_8);
        Path backup = ConfigUpdater.backupPath(config);
        Files.writeString(backup, "stale backup", StandardCharsets.UTF_8);

        ConfigUpdater.UpdateResult result = reconcile(config);

        assertTrue(result.changed());
        assertTrue(Files.exists(backup));
        assertEquals(original, Files.readString(backup, StandardCharsets.UTF_8));
    }

    @Test
    void unparseableConfigRecoversFromBundled() throws Exception {
        Path config = temporaryDirectory.resolve("config.yml");
        String broken = "mode: [unclosed\n";
        Files.writeString(config, broken, StandardCharsets.UTF_8);

        ConfigUpdater.UpdateResult result = reconcile(config);

        assertTrue(result.changed());
        assertEquals(broken, Files.readString(ConfigUpdater.backupPath(config), StandardCharsets.UTF_8));
        YamlConfiguration yaml = parse(config);
        assertEquals(1, yaml.getInt("config-version"));
        assertNotNull(yaml.getString("mojang.lookup-url"));
        assertEquals("ENFORCE", yaml.getString("mode"));
    }

    private ConfigUpdater.UpdateResult reconcile(Path config) throws IOException {
        try (InputStream defaults = Files.newInputStream(BUNDLED)) {
            return ConfigUpdater.reconcile(config, defaults, LOGGER);
        }
    }

    private static final YamlConfiguration BUNDLED_DEFAULT;

    static {
        try {
            BUNDLED_DEFAULT = parse(BUNDLED);
        } catch (Exception ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    private static YamlConfiguration parse(Path file) throws IOException, InvalidConfigurationException {
        YamlConfiguration config = new YamlConfiguration();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            config.load(reader);
        }
        return config;
    }
}
