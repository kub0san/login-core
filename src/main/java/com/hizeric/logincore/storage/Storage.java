package com.hizeric.logincore.storage;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class Storage implements AutoCloseable {

    private static final int CURRENT_SCHEMA_VERSION = 3;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "login-core-SQLite");
        thread.setDaemon(true);
        return thread;
    });
    private final Path databasePath;
    private Connection connection;

    public Storage(Path databasePath) throws SQLException, IOException {
        this.databasePath = databasePath.toAbsolutePath();
        Path parent = databasePath.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException ex) {
            throw new SQLException("SQLite JDBC driver is unavailable", ex);
        }
        // SQLite creates the file on connect; capture freshness first so a new install is not backed up.
        boolean databaseExisted = Files.exists(databasePath);
        try {
            connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath.toAbsolutePath());
            try (Statement statement = connection.createStatement()) {
                // Reject a newer schema before any pragma or DDL writes to it.
                int schemaVersion = schemaVersion(statement);
                if (schemaVersion > CURRENT_SCHEMA_VERSION) {
                    throw new SQLException("Database was written by a newer login-core (user_version "
                        + schemaVersion + ", supported up to " + CURRENT_SCHEMA_VERSION
                        + "); refusing to open it");
                }
                boolean needsMigration = schemaVersion < CURRENT_SCHEMA_VERSION;

                // Durability pragmas stay idempotent across restarts; they precede all DDL.
                statement.execute("PRAGMA journal_mode=WAL");
                statement.execute("PRAGMA busy_timeout=5000");
                statement.execute("PRAGMA synchronous=NORMAL");

                if (needsMigration && databaseExisted) {
                    backupDatabase();
                }

                statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS links (
                        name_lower TEXT PRIMARY KEY,
                        mojang_uuid TEXT NOT NULL,
                        canonical_name TEXT,
                        linked_at INTEGER NOT NULL,
                        last_verified_at INTEGER NOT NULL,
                        source TEXT NOT NULL
                    )
                    """);
                // AuthMe schema compatibility (upstream sql-initialize.sql:3-25). Only
                // AUTO_INCREMENT is adapted to SQLite's AUTOINCREMENT spelling.
                statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS authme (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        username VARCHAR(255) NOT NULL UNIQUE,
                        password VARCHAR(255) NOT NULL,
                        totp VARCHAR(16),
                        ip VARCHAR(40),
                        lastlogin BIGINT,
                        regdate BIGINT NOT NULL,
                        regip VARCHAR(40),
                        x DOUBLE NOT NULL DEFAULT '0.0',
                        y DOUBLE NOT NULL DEFAULT '0.0',
                        z DOUBLE NOT NULL DEFAULT '0.0',
                        world VARCHAR(255) NOT NULL DEFAULT 'world',
                        yaw FLOAT,
                        pitch FLOAT,
                        email VARCHAR(255),
                        isLogged INT DEFAULT '0',
                        realname VARCHAR(255) NOT NULL DEFAULT 'Player',
                        salt varchar(255),
                        hasSession INT NOT NULL DEFAULT '0',
                        premiumUUID VARCHAR(36),
                        playerUUID VARCHAR(36)
                    )
                    """);

                if (needsMigration) {
                    boolean autoCommit = connection.getAutoCommit();
                    connection.setAutoCommit(false);
                    try {
                        // links.mojang_uuid is NOT NULL, so OFFLINE accounts cannot live in links.
                        // Retire the old column (and its trigger) in favour of account_classification.
                        statement.executeUpdate("DROP TRIGGER IF EXISTS links_classification_immutable");
                        boolean hasLegacyClassification = false;
                        try (ResultSet result = statement.executeQuery("PRAGMA table_info(links)")) {
                            while (result.next()) {
                                if ("classification".equals(result.getString("name"))) {
                                    hasLegacyClassification = true;
                                    break;
                                }
                            }
                        }

                        statement.executeUpdate("""
                            CREATE TABLE IF NOT EXISTS account_classification (
                                name_lower TEXT PRIMARY KEY,
                                classification TEXT NOT NULL CHECK (classification IN ('PREMIUM','OFFLINE')),
                                set_at INTEGER NOT NULL
                            )
                            """);
                        if (hasLegacyClassification) {
                            try (PreparedStatement copy = connection.prepareStatement("""
                                INSERT INTO account_classification (name_lower, classification, set_at)
                                SELECT name_lower, classification, ? FROM links WHERE classification IS NOT NULL
                                ON CONFLICT(name_lower) DO NOTHING
                                """)) {
                                copy.setLong(1, Instant.now().toEpochMilli());
                                copy.executeUpdate();
                            }
                            statement.executeUpdate("ALTER TABLE links DROP COLUMN classification");
                        }

                        try {
                            statement.executeUpdate("""
                                CREATE UNIQUE INDEX IF NOT EXISTS idx_links_mojang_uuid ON links (mojang_uuid)
                                """);
                        } catch (SQLException ex) {
                            List<String> duplicates = new ArrayList<>();
                            try (ResultSet result = statement.executeQuery("""
                                SELECT mojang_uuid FROM links GROUP BY mojang_uuid HAVING COUNT(*) > 1
                                """)) {
                                while (result.next()) {
                                    duplicates.add(result.getString(1));
                                }
                            }
                            throw new SQLException("Duplicate premium UUID(s) in links: " + duplicates
                                + ". Resolve them before starting login-core", ex);
                        }

                        statement.executeUpdate("""
                            CREATE TRIGGER IF NOT EXISTS account_classification_immutable
                            BEFORE UPDATE ON account_classification
                            WHEN OLD.classification <> NEW.classification
                            BEGIN
                                SELECT RAISE(ABORT, 'classification is immutable');
                            END
                            """);
                        // v3: stable account token on authme + archive for evicted cracked accounts.
                        boolean hasAccountToken = false;
                        try (ResultSet result = statement.executeQuery("PRAGMA table_info(authme)")) {
                            while (result.next()) {
                                if ("account_token".equals(result.getString("name"))) {
                                    hasAccountToken = true;
                                    break;
                                }
                            }
                        }
                        if (!hasAccountToken) {
                            statement.executeUpdate("ALTER TABLE authme ADD COLUMN account_token VARCHAR(36)");
                        }
                        List<Long> missingTokens = new ArrayList<>();
                        try (ResultSet result = statement.executeQuery(
                            "SELECT id FROM authme WHERE account_token IS NULL")) {
                            while (result.next()) {
                                missingTokens.add(result.getLong(1));
                            }
                        }
                        try (PreparedStatement update = connection.prepareStatement(
                            "UPDATE authme SET account_token = ? WHERE id = ?")) {
                            for (long id : missingTokens) {
                                update.setString(1, UUID.randomUUID().toString());
                                update.setLong(2, id);
                                update.executeUpdate();
                            }
                        }
                        statement.executeUpdate("""
                            CREATE TABLE IF NOT EXISTS evicted_accounts (
                                token TEXT PRIMARY KEY,
                                old_username TEXT NOT NULL,
                                password TEXT NOT NULL,
                                regdate INTEGER NOT NULL,
                                realname TEXT,
                                evicted_at INTEGER NOT NULL,
                                reason TEXT
                            )
                            """);
                        // Exemptions are never re-imported: classification is the only identity signal.
                        statement.executeUpdate("DROP TABLE IF EXISTS exempt");
                        // Stamped last, inside the same transaction, so a partial migration is never marked done.
                        statement.execute("PRAGMA user_version = " + CURRENT_SCHEMA_VERSION);
                        connection.commit();
                    } catch (SQLException ex) {
                        connection.rollback();
                        throw ex;
                    } finally {
                        connection.setAutoCommit(autoCommit);
                    }
                }
            }
        } catch (SQLException ex) {
            if (connection != null) {
                try {
                    connection.close();
                } catch (SQLException closeFailure) {
                    ex.addSuppressed(closeFailure);
                }
            }
            throw new SQLException("Unable to initialize login-core SQLite storage", ex);
        }
    }

    public CompletableFuture<AccountState> account(String name) {
        return submit(() -> new AccountState(findLink(normalize(name))));
    }

    public CompletableFuture<Optional<Link>> link(String name) {
        return submit(() -> findLink(normalize(name)));
    }

    /** Read-only lookup of the name linked to a premium UUID (name-keyed via links.name_lower). */
    public CompletableFuture<Optional<Link>> linkByUuid(String mojangUuid) {
        return submit(() -> findLinkByUuid(mojangUuid));
    }

    /** Reads the stored AuthMe password hash; empty when the name has no authme row. */
    public CompletableFuture<Optional<String>> authmePassword(String name) {
        return submit(() -> {
            try (PreparedStatement statement = connection.prepareStatement(
                "SELECT password FROM authme WHERE username = ? COLLATE NOCASE")) {
                statement.setString(1, name);
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() ? Optional.ofNullable(result.getString(1)) : Optional.empty();
                }
            }
        });
    }

    /** Reads the stable account token; empty when the name or its token is missing. */
    public CompletableFuture<Optional<String>> authmeToken(String name) {
        if (name == null || name.isBlank()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        return submit(() -> {
            try (PreparedStatement statement = connection.prepareStatement(
                "SELECT account_token FROM authme WHERE username = ? COLLATE NOCASE")) {
                statement.setString(1, normalize(name));
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() ? Optional.ofNullable(result.getString(1)) : Optional.empty();
                }
            }
        });
    }

    /** True when an authme row exists for the name, case-insensitively. */
    public CompletableFuture<Boolean> isRegistered(String name) {
        if (name == null || name.isBlank()) {
            return CompletableFuture.completedFuture(false);
        }
        return submit(() -> {
            try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM authme WHERE username = ? COLLATE NOCASE")) {
                statement.setString(1, normalize(name));
                try (ResultSet result = statement.executeQuery()) {
                    return result.next();
                }
            }
        });
    }

    /** All registered authme names, ordered case-insensitively. */
    public CompletableFuture<List<String>> registeredNames() {
        return submit(() -> {
            List<String> names = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                "SELECT username FROM authme ORDER BY username COLLATE NOCASE");
                 ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    names.add(result.getString(1));
                }
            }
            return List.copyOf(names);
        });
    }

    /** Local projection of an authme row; empty when the name is not registered. */
    public CompletableFuture<Optional<PlayerInfo>> playerInfo(String name) {
        if (name == null || name.isBlank()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        return submit(() -> {
            try (PreparedStatement statement = connection.prepareStatement(
                "SELECT lastlogin, ip, regdate FROM authme WHERE username = ? COLLATE NOCASE")) {
                statement.setString(1, normalize(name));
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) {
                        return Optional.empty();
                    }
                    long lastLogin = result.getLong("lastlogin");
                    boolean hasLastLogin = !result.wasNull();
                    String lastIp = result.getString("ip");
                    long registeredAt = result.getLong("regdate");
                    return Optional.of(new PlayerInfo(
                        hasLastLogin ? Optional.of(Instant.ofEpochMilli(lastLogin)) : Optional.empty(),
                        lastIp == null ? Optional.empty() : Optional.of(lastIp),
                        Instant.ofEpochMilli(registeredAt)));
                }
            }
        });
    }

    /** Registers a new offline account with a BCrypt password hash. Returns false if the name is already registered. */
    public CompletableFuture<Boolean> registerPlayer(String name, String password) {
        if (name == null || name.isBlank() || password == null || password.isBlank()) {
            return CompletableFuture.completedFuture(false);
        }
        String hash = com.hizeric.logincore.authme.BCrypt.gensalt(12);
        String hashed = com.hizeric.logincore.authme.BCrypt.hashpw(password, hash);
        String normalized = normalize(name);
        return submit(() -> {
            // Check existing
            try (PreparedStatement check = connection.prepareStatement(
                    "SELECT 1 FROM authme WHERE username = ? COLLATE NOCASE")) {
                check.setString(1, normalized);
                try (ResultSet rs = check.executeQuery()) {
                    if (rs.next()) {
                        return false;
                    }
                }
            }
            try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO authme (username, password, regdate, regip, realname, playerUUID, account_token, isLogged, hasSession)
                VALUES (?, ?, ?, ?, ?, ?, ?, 0, 0)
                """)) {
                insert.setString(1, normalized);
                insert.setString(2, hashed);
                insert.setLong(3, Instant.now().toEpochMilli());
                insert.setString(4, "127.0.0.1");
                insert.setString(5, name);
                insert.setString(6, offlineUuid(name));
                insert.setString(7, UUID.randomUUID().toString());
                insert.executeUpdate();
                return true;
            }
        });
    }

    /** Verifies a plain password against the stored BCrypt hash. Returns false if no account or unsupported hash. */
    public CompletableFuture<Boolean> verifyPassword(String name, String password) {
        if (name == null || name.isBlank() || password == null) {
            return CompletableFuture.completedFuture(false);
        }
        return submit(() -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT password FROM authme WHERE username = ? COLLATE NOCASE")) {
                statement.setString(1, normalize(name));
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) {
                        return false;
                    }
                    String hash = result.getString(1);
                    return com.hizeric.logincore.authme.AuthMePasswords.verify(password, hash);
                }
            }
        });
    }

    /** Marks an account as logged in. */
    public CompletableFuture<Void> setLoggedIn(String name) {
        return submit(() -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE authme SET isLogged = 1, lastlogin = ? WHERE username = ? COLLATE NOCASE")) {
                statement.setLong(1, Instant.now().toEpochMilli());
                statement.setString(2, normalize(name));
                statement.executeUpdate();
                return null;
            }
        });
    }

    /** Marks an account as logged out. */
    public CompletableFuture<Void> setLoggedOut(String name) {
        return submit(() -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE authme SET isLogged = 0 WHERE username = ? COLLATE NOCASE")) {
                statement.setString(1, normalize(name));
                statement.executeUpdate();
                return null;
            }
        });
    }

    /** Changes password after verifying the old one. Returns false if old password is wrong or account missing. */
    public CompletableFuture<Boolean> changePassword(String name, String oldPassword, String newPassword) {
        if (name == null || name.isBlank() || oldPassword == null || newPassword == null
                || newPassword.isBlank()) {
            return CompletableFuture.completedFuture(false);
        }
        return verifyPassword(name, oldPassword).thenCompose(ok -> {
            if (!ok) {
                return CompletableFuture.completedFuture(false);
            }
            String hash = com.hizeric.logincore.authme.BCrypt.gensalt(12);
            String hashed = com.hizeric.logincore.authme.BCrypt.hashpw(newPassword, hash);
            return submit(() -> {
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE authme SET password = ? WHERE username = ? COLLATE NOCASE")) {
                    statement.setString(1, hashed);
                    statement.setString(2, normalize(name));
                    return statement.executeUpdate() > 0;
                }
            });
        });
    }

    private static String offlineUuid(String name) {
        // Paper's offline UUID: UUID.nameUUIDFromBytes("OfflinePlayer:" + name)
        return java.util.UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    }

    public CompletableFuture<ClassificationResult> setClassification(String name, String classification) {
        String normalizedName = classificationKey(name);
        String normalizedClassification = classification == null ? "" : classification.trim().toUpperCase(Locale.ROOT);
        if (!"PREMIUM".equals(normalizedClassification) && !"OFFLINE".equals(normalizedClassification)) {
            throw new IllegalArgumentException("Unsupported classification: " + classification);
        }
        return submit(() -> applyClassification(normalizedName, normalizedClassification));
    }

    public CompletableFuture<Optional<String>> classification(String name) {
        String normalizedName = classificationKey(name);
        return submit(() -> Optional.ofNullable(storedClassification(normalizedName)));
    }

    private Path backupDatabase() throws SQLException {
        // WAL holds the most recent commits; checkpoint so the copied file stands alone.
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA wal_checkpoint(TRUNCATE)");
        }
        String timestamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneOffset.UTC).format(Instant.now());
        Path backup = databasePath.resolveSibling(databasePath.getFileName() + ".bak-" + timestamp);
        try {
            // No REPLACE_EXISTING: an existing backup at this timestamp is skipped, not overwritten.
            Files.copy(databasePath, backup);
        } catch (FileAlreadyExistsException ex) {
            return backup;
        } catch (IOException ex) {
            throw new SQLException("Unable to back up login-core SQLite storage", ex);
        }
        return backup;
    }

    private static int schemaVersion(Statement statement) throws SQLException {
        try (ResultSet result = statement.executeQuery("PRAGMA user_version")) {
            return result.next() ? result.getInt(1) : 0;
        }
    }

    /**
     * Internal-only (login pipeline); never wire this to a command. Returns true when the
     * requested UUID is stored for the name; false when a different UUID is already linked
     * (write-once, reported as a conflict so callers can fail closed).
     */
    public CompletableFuture<Boolean> putLink(String name, String mojangUuid, String canonicalName, String source) {
        String normalized = normalize(name);
        return submit(() -> writeLink(normalized, mojangUuid, canonicalName, source));
    }

    /**
     * Commits the write-once PREMIUM classification and the link in one transaction: a link
     * failure (a duplicate premium UUID or a different UUID already linked to this name) rolls
     * the classification back, so a failed persistence can never leave a lockout behind. False
     * means a conflicting stored classification or a UUID conflict; SQL failures complete the
     * future exceptionally.
     */
    public CompletableFuture<Boolean> commitPremiumIdentity(String name, String mojangUuid,
                                                            String canonicalName, String source) {
        String normalizedName = classificationKey(name);
        return submit(() -> {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                if (insertClassification(normalizedName, "PREMIUM") == 0
                    && !"PREMIUM".equals(storedClassification(normalizedName))) {
                    connection.rollback();
                    return false;
                }
                if (!writeLink(normalizedName, mojangUuid, canonicalName, source)) {
                    connection.rollback();
                    return false;
                }
                connection.commit();
                return true;
            } catch (SQLException ex) {
                connection.rollback();
                throw ex;
            } catch (RuntimeException ex) {
                connection.rollback();
                throw ex;
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        });
    }

    /**
     * Moves a premium identity to a new name in one transaction: releases the old name,
     * archives any cracked squatter holding the new name, then writes the premium link,
     * classification and authme row. Conflicts roll the whole migration back.
     */
    public CompletableFuture<RenameMigrationResult> migratePremiumRename(String oldName, String newName,
                                                                          String mojangUuid, String newToken) {
        String oldKey = classificationKey(oldName);
        String newKey = classificationKey(newName);
        String canonical = newName.trim();
        return submit(() -> {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                // Release the old name. Classification uses DELETE because it is immutable.
                try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM links WHERE name_lower = ?")) {
                    statement.setString(1, oldKey);
                    statement.executeUpdate();
                }
                try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM account_classification WHERE name_lower = ?")) {
                    statement.setString(1, oldKey);
                    statement.executeUpdate();
                }
                try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM authme WHERE username = ? COLLATE NOCASE")) {
                    statement.setString(1, oldKey);
                    statement.executeUpdate();
                }

                // Evict a squatter, preserving its account data for a later restore.
                long now = Instant.now().toEpochMilli();
                int archived;
                try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO evicted_accounts
                        (token, old_username, password, regdate, realname, evicted_at, reason)
                    SELECT account_token, username, password, regdate, realname, ?, 'premium-rename'
                    FROM authme WHERE username = ? COLLATE NOCASE
                    """)) {
                    statement.setLong(1, now);
                    statement.setString(2, newKey);
                    archived = statement.executeUpdate();
                }
                if (archived > 0) {
                    try (PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM authme WHERE username = ? COLLATE NOCASE")) {
                        statement.setString(1, newKey);
                        statement.executeUpdate();
                    }
                    try (PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM account_classification WHERE name_lower = ?")) {
                        statement.setString(1, newKey);
                        statement.executeUpdate();
                    }
                }

                // Write the premium identity. The old link is already gone, so the UUID is free.
                if (insertClassification(newKey, "PREMIUM") == 0
                    && !"PREMIUM".equals(storedClassification(newKey))) {
                    connection.rollback();
                    return RenameMigrationResult.CONFLICT;
                }
                if (!writeLink(newKey, mojangUuid, canonical, "AUTO")) {
                    connection.rollback();
                    return RenameMigrationResult.CONFLICT;
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO authme
                        (username, password, regdate, realname, playerUUID, account_token, isLogged, hasSession)
                    VALUES (?, ?, ?, ?, ?, ?, 0, 0)
                    """)) {
                    statement.setString(1, newKey);
                    // Premium has no local password; a random UUID is not a supported hash, so it cannot authenticate.
                    statement.setString(2, UUID.randomUUID().toString());
                    statement.setLong(3, now);
                    statement.setString(4, canonical);
                    statement.setString(5, mojangUuid);
                    statement.setString(6, newToken);
                    statement.executeUpdate();
                }
                connection.commit();
                return RenameMigrationResult.COMMITTED;
            } catch (SQLException ex) {
                connection.rollback();
                throw ex;
            } catch (RuntimeException ex) {
                connection.rollback();
                throw ex;
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        });
    }

    /** Reads an archived cracked account by its stable token. */
    public CompletableFuture<Optional<EvictedAccount>> findEvicted(String token) {
        return submit(() -> findEvictedRow(token));
    }

    private Optional<EvictedAccount> findEvictedRow(String token) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT token, old_username, password, regdate, realname FROM evicted_accounts WHERE token = ?
            """)) {
            statement.setString(1, token);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                return Optional.of(new EvictedAccount(result.getString("token"), result.getString("old_username"),
                    result.getString("password"), result.getLong("regdate"), result.getString("realname")));
            }
        }
    }

    /**
     * Restores an archived cracked account into a new name. Fails closed without touching the
     * archive when the name is already PREMIUM or already registered.
     */
    public CompletableFuture<RestoreResult> restoreEvicted(String token, String newName) {
        String normalized = classificationKey(newName);
        return submit(() -> {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                Optional<EvictedAccount> archived = findEvictedRow(token);
                if (archived.isEmpty()) {
                    connection.rollback();
                    return RestoreResult.NOT_FOUND;
                }
                if ("PREMIUM".equals(storedClassification(normalized)) || hasAuthme(normalized)) {
                    connection.rollback();
                    return RestoreResult.COLLISION;
                }
                EvictedAccount row = archived.orElseThrow();
                try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO authme
                        (username, password, regdate, realname, playerUUID, account_token, isLogged, hasSession)
                    VALUES (?, ?, ?, ?, ?, ?, 0, 0)
                    """)) {
                    statement.setString(1, normalized);
                    statement.setString(2, row.password());
                    statement.setLong(3, row.regdate());
                    statement.setString(4, row.realname() == null ? newName.trim() : row.realname());
                    statement.setString(5, offlineUuid(newName));
                    statement.setString(6, row.token());
                    statement.executeUpdate();
                }
                insertClassification(normalized, "OFFLINE");
                try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM evicted_accounts WHERE token = ?")) {
                    statement.setString(1, row.token());
                    statement.executeUpdate();
                }
                connection.commit();
                return RestoreResult.RESTORED;
            } catch (SQLException ex) {
                connection.rollback();
                throw ex;
            } catch (RuntimeException ex) {
                connection.rollback();
                throw ex;
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        });
    }

    private boolean hasAuthme(String normalizedName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT 1 FROM authme WHERE username = ? COLLATE NOCASE")) {
            statement.setString(1, normalizedName);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    // Internal-only (login pipeline); never wire this to a command — classification is immutable.
    public CompletableFuture<Boolean> unlink(String name) {
        return submit(() -> {
            try (PreparedStatement statement = connection.prepareStatement("DELETE FROM links WHERE name_lower = ?")) {
                statement.setString(1, normalize(name));
                return statement.executeUpdate() > 0;
            }
        });
    }

    public CompletableFuture<Summary> summary() {
        return submit(() -> new Summary(countRows("links"), countRows("account_classification")));
    }

    private int countRows(String table) throws SQLException {
        if (!"links".equals(table) && !"account_classification".equals(table)) {
            throw new IllegalArgumentException("Unsupported table name");
        }
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            return result.next() ? result.getInt(1) : 0;
        }
    }

    private ClassificationResult applyClassification(String normalizedName, String classification)
        throws SQLException {
        int inserted = insertClassification(normalizedName, classification);
        if (inserted > 0) {
            return ClassificationResult.SET;
        }
        return classification.equals(storedClassification(normalizedName))
            ? ClassificationResult.ALREADY_SAME
            : ClassificationResult.ALREADY_DIFFERENT;
    }

    private int insertClassification(String normalizedName, String classification) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO account_classification (name_lower, classification, set_at)
            VALUES (?, ?, ?)
            ON CONFLICT(name_lower) DO NOTHING
            """)) {
            statement.setString(1, normalizedName);
            statement.setString(2, classification);
            statement.setLong(3, Instant.now().toEpochMilli());
            return statement.executeUpdate();
        }
    }

    /** Returns false when a different UUID is already stored for the name (write-once link). */
    private boolean writeLink(String normalizedName, String mojangUuid, String canonicalName, String source)
        throws SQLException {
        long now = Instant.now().toEpochMilli();
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO links (name_lower, mojang_uuid, canonical_name, linked_at, last_verified_at, source)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT(name_lower) DO UPDATE SET
                mojang_uuid = excluded.mojang_uuid,
                canonical_name = excluded.canonical_name,
                last_verified_at = excluded.last_verified_at,
                source = excluded.source
            WHERE links.mojang_uuid = excluded.mojang_uuid
            """)) {
            statement.setString(1, normalizedName);
            statement.setString(2, mojangUuid);
            statement.setString(3, canonicalName);
            statement.setLong(4, now);
            statement.setLong(5, now);
            statement.setString(6, source);
            // A conflicting UUID makes the DO UPDATE predicate false, so zero rows change.
            return statement.executeUpdate() > 0;
        }
    }

    private String storedClassification(String normalizedName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT classification FROM account_classification WHERE name_lower = ?")) {
            statement.setString(1, normalizedName);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getString(1) : null;
            }
        }
    }

    private Optional<Link> findLink(String normalizedName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT mojang_uuid, canonical_name, source FROM links WHERE name_lower = ?
            """)) {
            statement.setString(1, normalizedName);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                return Optional.of(new Link(normalizedName, result.getString(1), result.getString(2), result.getString(3)));
            }
        }
    }

    private Optional<Link> findLinkByUuid(String mojangUuid) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT name_lower, canonical_name, source FROM links WHERE mojang_uuid = ?")) {
            statement.setString(1, mojangUuid);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                return Optional.of(new Link(result.getString(1), mojangUuid, result.getString(2), result.getString(3)));
            }
        }
    }

    private <T> CompletableFuture<T> submit(SqlOperation<T> operation) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return operation.run();
            } catch (SQLException ex) {
                throw new CompletionException(new IllegalStateException("login-core storage operation failed", ex));
            }
        }, executor);
    }

    private static String normalize(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Player name must not be blank");
        }
        return name.toLowerCase(Locale.ROOT);
    }

    private static String classificationKey(String name) {
        return normalize(name).trim();
    }

    @Override
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException ex) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ex) {
                throw new IllegalStateException("Unable to close login-core SQLite storage", ex);
            }
        }
    }

    @FunctionalInterface
    private interface SqlOperation<T> {
        T run() throws SQLException;
    }

    public record Link(String name, String mojangUuid, String canonicalName, String source) {
    }

    /** Local projection of AuthMe account data so callers never depend on a vendor type. */
    public record PlayerInfo(Optional<Instant> lastLogin, Optional<String> lastIp, Instant registeredAt) {
    }

    public record AccountState(Optional<Link> link) {
    }

    public record Summary(int links, int classifications) {
    }

    public enum ClassificationResult { SET, ALREADY_SAME, ALREADY_DIFFERENT }

    public enum RenameMigrationResult { COMMITTED, CONFLICT }

    public enum RestoreResult { RESTORED, COLLISION, NOT_FOUND }

    /** Archived cracked account data, keyed by the stable account token. */
    public record EvictedAccount(String token, String oldUsername, String password,
                                 long regdate, String realname) {
    }
}
