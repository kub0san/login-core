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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyAuthMeImportTest {

    private static final Path BUNDLED = Path.of("src", "main", "resources", "config.yml");
    private static final Logger LOGGER = Logger.getLogger(LegacyAuthMeImportTest.class.getName());
    private static final String LEGACY_CONFIG = """
        DataSource:
          backend: MYSQL
          mySQLDatabase: authme
          username: root
        settings:
          foo: bar
        Security:
          password: secret
        mode: SHADOW
        """;

    @TempDir
    Path temporaryDirectory;

    @Test
    void currentConfigIsNotImported() throws Exception {
        Path folder = temporaryDirectory.resolve("current");
        Files.createDirectories(folder);
        Path config = folder.resolve("config.yml");
        byte[] bundled = Files.readAllBytes(BUNDLED);
        Files.write(config, bundled);

        LegacyAuthMeImport.ImportResult result = importIfNeeded(folder);

        assertFalse(result.imported());
        assertEquals(0, result.droppedLegacyKeys());
        assertFalse(result.databaseCopied());
        assertNull(result.backup());
        assertArrayEquals(bundled, Files.readAllBytes(config));
        assertEquals(0, filesMatching(folder, ".legacy-"));
    }

    @Test
    void nonSqliteBackendSkipsDatabaseCopyAndStillConvertsConfig() throws Exception {
        Path folder = temporaryDirectory.resolve("mysql");
        Files.createDirectories(folder);
        Files.writeString(folder.resolve("config.yml"), LEGACY_CONFIG, StandardCharsets.UTF_8);
        byte[] existing = "existing-db".getBytes(StandardCharsets.UTF_8);
        Files.write(folder.resolve("authme.db"), existing);

        LegacyAuthMeImport.ImportResult result = importIfNeeded(folder);

        assertTrue(result.imported());
        assertFalse(result.databaseCopied());
        assertArrayEquals(existing, Files.readAllBytes(folder.resolve("authme.db")));
        assertNotNull(result.backup());
        assertTrue(Files.exists(result.backup()));
        assertEquals(LEGACY_CONFIG, Files.readString(result.backup(), StandardCharsets.UTF_8));
        YamlConfiguration yaml = parse(folder.resolve("config.yml"));
        assertEquals(1, yaml.getInt("config-version"));
        assertFalse(yaml.contains("DataSource.backend"));
        assertEquals("ENFORCE", yaml.getString("mode"));
    }

    @Test
    void sqliteFilenameHandoffCopiesOnlyWhenTargetAbsent() throws Exception {
        Path absent = temporaryDirectory.resolve("absent-target");
        Files.createDirectories(absent);
        byte[] payload = "legacy-data".getBytes(StandardCharsets.UTF_8);
        Files.write(absent.resolve("authme.db"), "should-not-be-touched".getBytes(StandardCharsets.UTF_8));
        Files.write(absent.resolve("custom.db"), payload);
        Files.write(absent.resolve("custom.db-wal"), "wal".getBytes(StandardCharsets.UTF_8));
        Files.write(absent.resolve("custom.db-shm"), "shm".getBytes(StandardCharsets.UTF_8));
        String sqliteConfig = LEGACY_CONFIG.replace("MYSQL", "SQLITE").replace("mySQLDatabase: authme",
            "mySQLDatabase: custom");
        Files.writeString(absent.resolve("config.yml"), sqliteConfig, StandardCharsets.UTF_8);

        LegacyAuthMeImport.ImportResult first = importIfNeeded(absent);

        assertTrue(first.imported());
        assertFalse(first.databaseCopied(), "authme.db already exists, so no handoff");
        assertArrayEquals("should-not-be-touched".getBytes(StandardCharsets.UTF_8),
            Files.readAllBytes(absent.resolve("authme.db")));

        Path fresh = temporaryDirectory.resolve("fresh-target");
        Files.createDirectories(fresh);
        Files.writeString(fresh.resolve("config.yml"), sqliteConfig, StandardCharsets.UTF_8);
        Files.write(fresh.resolve("custom.db"), payload);
        Files.write(fresh.resolve("custom.db-wal"), "wal".getBytes(StandardCharsets.UTF_8));
        Files.write(fresh.resolve("custom.db-shm"), "shm".getBytes(StandardCharsets.UTF_8));

        LegacyAuthMeImport.ImportResult second = importIfNeeded(fresh);

        assertTrue(second.imported());
        assertTrue(second.databaseCopied());
        assertArrayEquals(payload, Files.readAllBytes(fresh.resolve("authme.db")));
        assertArrayEquals("wal".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(fresh.resolve("authme.db-wal")));
        assertArrayEquals("shm".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(fresh.resolve("authme.db-shm")));
        assertTrue(Files.exists(fresh.resolve("custom.db")), "the legacy file is never deleted");
    }

    @Test
    void droppedKeyCountCountsOnlyLeavesUnderLegacyPrefixes() throws Exception {
        Path folder = temporaryDirectory.resolve("counts");
        Files.createDirectories(folder);
        Files.writeString(folder.resolve("config.yml"), LEGACY_CONFIG, StandardCharsets.UTF_8);

        LegacyAuthMeImport.ImportResult result = importIfNeeded(folder);

        assertTrue(result.imported());
        // DataSource.backend, DataSource.mySQLDatabase, DataSource.username, settings.foo, Security.password.
        assertEquals(5, result.droppedLegacyKeys());
    }

    @Test
    void legacyConfigIsNotImportedTwice() throws Exception {
        Path folder = temporaryDirectory.resolve("once");
        Files.createDirectories(folder);
        Files.writeString(folder.resolve("config.yml"), LEGACY_CONFIG, StandardCharsets.UTF_8);

        assertTrue(importIfNeeded(folder).imported());
        LegacyAuthMeImport.ImportResult second = importIfNeeded(folder);
        assertFalse(second.imported());
    }

    private LegacyAuthMeImport.ImportResult importIfNeeded(Path folder) throws IOException {
        try (InputStream defaults = Files.newInputStream(BUNDLED)) {
            return LegacyAuthMeImport.importIfNeeded(folder, defaults, LOGGER);
        }
    }

    private static long filesMatching(Path folder, String fragment) throws IOException {
        try (var stream = Files.list(folder)) {
            return stream.filter(path -> path.getFileName().toString().contains(fragment)).count();
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
