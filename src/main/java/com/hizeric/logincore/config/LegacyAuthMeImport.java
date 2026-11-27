package com.hizeric.logincore.config;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.logging.Logger;

/** One-time, enable-time conversion of an AuthMe-shaped config into a login-core config. */
public final class LegacyAuthMeImport {

    public record ImportResult(boolean imported, int droppedLegacyKeys, boolean databaseCopied, Path backup) {}

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private LegacyAuthMeImport() {
    }

    public static ImportResult importIfNeeded(Path dataFolder, InputStream bundledDefaults, Logger logger) throws IOException {
        Path configFile = dataFolder.resolve("config.yml");
        if (!Files.exists(configFile)) {
            return new ImportResult(false, 0, false, null);
        }

        YamlConfiguration disk = new YamlConfiguration();
        try (Reader reader = Files.newBufferedReader(configFile, StandardCharsets.UTF_8)) {
            disk.load(reader);
        } catch (InvalidConfigurationException ex) {
            // ConfigUpdater owns recovery of unparseable files.
            return new ImportResult(false, 0, false, null);
        }

        int dropped = 0;
        for (String key : disk.getKeys(true)) {
            if (!disk.isConfigurationSection(key) && ConfigUpdater.isObsolete(key)) {
                dropped++;
            }
        }
        if (disk.get("config-version") != null || dropped == 0) {
            return new ImportResult(false, 0, false, null);
        }

        // Read the legacy pointers before anything is rewritten.
        String backend = disk.getString("DataSource.backend", "SQLITE");
        String databaseName = disk.getString("DataSource.mySQLDatabase", "authme");

        Path authmeDb = dataFolder.resolve("authme.db");
        boolean databaseCopied = false;
        if ("SQLITE".equalsIgnoreCase(backend)) {
            Path legacyDb = dataFolder.resolve(databaseName + ".db");
            if (!legacyDb.toAbsolutePath().normalize().equals(authmeDb.toAbsolutePath().normalize())
                && Files.exists(legacyDb)) {
                if (Files.exists(authmeDb)) {
                    logger.info("authme.db already exists; legacy " + legacyDb.getFileName() + " was not copied.");
                } else {
                    try {
                        Files.copy(legacyDb, authmeDb);
                        copySibling(legacyDb, authmeDb, "-wal");
                        copySibling(legacyDb, authmeDb, "-shm");
                        databaseCopied = true;
                        logger.info("Imported legacy SQLite database " + legacyDb.getFileName() + " as authme.db.");
                    } catch (IOException ex) {
                        logger.severe("Could not copy " + legacyDb + " to " + authmeDb
                            + "; leaving config.yml untouched for retry: " + ex.getMessage());
                        throw ex;
                    }
                }
            }
        } else {
            logger.warning("Legacy backend '" + backend + "' is not SQLite; database import skipped.");
        }

        String stamp = LocalDateTime.now().format(STAMP);
        Path backup = configFile.resolveSibling(configFile.getFileName() + ".legacy-" + stamp);
        ConfigUpdater.safeWrite(configFile, bundledDefaults.readAllBytes(), backup, logger);
        logger.info("Converted legacy config: " + dropped + " obsolete key(s) dropped; original saved as "
            + backup.getFileName() + ".");
        return new ImportResult(true, dropped, databaseCopied, backup);
    }

    private static void copySibling(Path legacyDb, Path authmeDb, String suffix) throws IOException {
        Path source = Path.of(legacyDb + suffix);
        if (Files.exists(source)) {
            Files.copy(source, Path.of(authmeDb + suffix));
        }
    }
}
