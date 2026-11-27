package com.hizeric.logincore.config;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/** Reconciles the on-disk config with the keys bundled in the plugin jar. */
public final class ConfigUpdater {

    public record UpdateResult(boolean changed, int added, int removed) {}

    private static final List<String> OBSOLETE_PREFIXES = List.of("DataSource.", "settings.", "Security.");

    private ConfigUpdater() {
    }

    public static UpdateResult reconcile(Path configFile, InputStream bundledDefaults, Logger logger) throws IOException {
        byte[] defaults = bundledDefaults.readAllBytes();
        YamlConfiguration bundled;
        try {
            bundled = parse(defaults);
        } catch (InvalidConfigurationException ex) {
            throw new IOException("Bundled config.yml is unparseable", ex);
        }

        if (!Files.exists(configFile)) {
            safeWrite(configFile, defaults, backupPath(configFile), logger);
            logger.info("No " + configFile.getFileName() + " found; wrote bundled defaults.");
            return new UpdateResult(true, 0, 0);
        }

        YamlConfiguration current;
        try {
            current = parse(Files.readAllBytes(configFile));
        } catch (InvalidConfigurationException ex) {
            Path backup = backupPath(configFile);
            safeWrite(configFile, defaults, backup, logger);
            logger.warning("Could not parse " + configFile.getFileName() + "; restored bundled defaults from "
                + backup.getFileName() + ": " + ex.getMessage());
            return new UpdateResult(true, 0, 0);
        }

        int removed = 0;
        for (String key : new ArrayList<>(current.getKeys(true))) {
            if (!current.isConfigurationSection(key) && isObsolete(key)) {
                current.set(key, null);
                removed++;
            }
        }

        int added = 0;
        for (String key : bundled.getKeys(true)) {
            if (bundled.isConfigurationSection(key) || current.get(key) != null) {
                continue;
            }
            current.set(key, bundled.get(key));
            added++;
        }

        boolean versionStamped = false;
        if (!(current.get("config-version") instanceof Integer version && version == 1)) {
            current.set("config-version", 1);
            versionStamped = true;
        }
        if (added == 0 && removed == 0 && !versionStamped) {
            return new UpdateResult(false, 0, 0);
        }

        safeWrite(configFile, current, backupPath(configFile), logger);
        logger.info("Updated " + configFile.getFileName() + ": " + added + " key(s) added, "
            + removed + " obsolete key(s) removed.");
        return new UpdateResult(true, added, removed);
    }

    static boolean isObsolete(String key) {
        for (String prefix : OBSOLETE_PREFIXES) {
            if (key.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    static Path backupPath(Path file) {
        return Path.of(file.toString() + ".backup");
    }

    static void safeWrite(Path file, YamlConfiguration config, Path backup, Logger logger) throws IOException {
        safeWrite(file, config.saveToString().getBytes(StandardCharsets.UTF_8), backup, logger);
    }

    /**
     * Writes through a same-directory temp file so a failed serialize/parse never damages the
     * original. The caller chooses the backup path so the legacy importer can keep a timestamped copy.
     */
    static void safeWrite(Path file, byte[] contents, Path backup, Logger logger) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        Path temp = Files.createTempFile(parent, ".logincore-", ".tmp");
        try {
            Files.write(temp, contents);
            try {
                parse(Files.readAllBytes(temp));
            } catch (InvalidConfigurationException ex) {
                throw new IOException("Refusing to write unparseable YAML to " + file, ex);
            }
            if (Files.exists(file)) {
                Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING);
            }
            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException | FileAlreadyExistsException ex) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static YamlConfiguration parse(byte[] bytes) throws IOException, InvalidConfigurationException {
        YamlConfiguration config = new YamlConfiguration();
        // loadConfiguration(Reader) swallows parse errors, so load explicitly.
        config.load(new StringReader(new String(bytes, StandardCharsets.UTF_8)));
        return config;
    }
}
