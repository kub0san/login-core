package com.hizeric.logincore.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class Storage implements AutoCloseable {

    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "login-core-SQLite");
        thread.setDaemon(true);
        return thread;
    });
    private Connection connection;

    public Storage(Path databasePath) throws SQLException, IOException {
        Path parent = databasePath.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException ex) {
            throw new SQLException("SQLite JDBC driver is unavailable", ex);
        }
        try {
            connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath.toAbsolutePath());
            try (Statement statement = connection.createStatement()) {
                // Durability pragmas stay idempotent across restarts; they precede all DDL.
                statement.execute("PRAGMA journal_mode=WAL");
                statement.execute("PRAGMA busy_timeout=5000");
                statement.execute("PRAGMA synchronous=NORMAL");
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
                statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS exempt (
                        name_lower TEXT PRIMARY KEY,
                        reason TEXT,
                        added_at INTEGER NOT NULL,
                        added_by TEXT NOT NULL
                    )
                    """);

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
                    if (hasLegacyClassification) {
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
                        CREATE TABLE IF NOT EXISTS account_classification (
                            name_lower TEXT PRIMARY KEY,
                            classification TEXT NOT NULL CHECK (classification IN ('PREMIUM','OFFLINE')),
                            set_at INTEGER NOT NULL
                        )
                        """);
                    statement.executeUpdate("""
                        CREATE TRIGGER IF NOT EXISTS account_classification_immutable
                        BEFORE UPDATE ON account_classification
                        WHEN OLD.classification <> NEW.classification
                        BEGIN
                            SELECT RAISE(ABORT, 'classification is immutable');
                        END
                        """);

                    connection.commit();
                } catch (SQLException ex) {
                    connection.rollback();
                    throw ex;
                } finally {
                    connection.setAutoCommit(autoCommit);
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
        String normalized = normalize(name);
        return submit(() -> {
            Optional<Link> link = findLink(normalized);
            boolean exempt;
            try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM exempt WHERE name_lower = ?")) {
                statement.setString(1, normalized);
                try (ResultSet result = statement.executeQuery()) {
                    exempt = result.next();
                }
            }
            return new AccountState(link, exempt);
        });
    }

    public CompletableFuture<Optional<Link>> link(String name) {
        return submit(() -> findLink(normalize(name)));
    }

    public CompletableFuture<ClassificationResult> setClassification(String name, String classification) {
        String normalizedName = classificationKey(name);
        String normalizedClassification = classification == null ? "" : classification.trim().toUpperCase(Locale.ROOT);
        if (!"PREMIUM".equals(normalizedClassification) && !"OFFLINE".equals(normalizedClassification)) {
            throw new IllegalArgumentException("Unsupported classification: " + classification);
        }
        return submit(() -> {
            int inserted = insertClassification(normalizedName, normalizedClassification);
            if (inserted > 0) {
                return ClassificationResult.SET;
            }
            String stored = storedClassification(normalizedName);
            return normalizedClassification.equals(stored)
                ? ClassificationResult.ALREADY_SAME
                : ClassificationResult.ALREADY_DIFFERENT;
        });
    }

    public CompletableFuture<Optional<String>> classification(String name) {
        String normalizedName = classificationKey(name);
        return submit(() -> Optional.ofNullable(storedClassification(normalizedName)));
    }

    public CompletableFuture<Void> putLink(String name, String mojangUuid, String canonicalName, String source) {
        String normalized = normalize(name);
        return submit(() -> {
            writeLink(normalized, mojangUuid, canonicalName, source);
            return null;
        });
    }

    /**
     * Commits the write-once PREMIUM classification and the link in one transaction: a link
     * failure (e.g. a duplicate premium UUID) rolls the classification back, so a failed
     * persistence can never leave a lockout behind. False means a conflicting stored
     * classification; SQL failures complete the future exceptionally.
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
                writeLink(normalizedName, mojangUuid, canonicalName, source);
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

    public CompletableFuture<Boolean> unlink(String name) {
        return submit(() -> {
            try (PreparedStatement statement = connection.prepareStatement("DELETE FROM links WHERE name_lower = ?")) {
                statement.setString(1, normalize(name));
                return statement.executeUpdate() > 0;
            }
        });
    }

    public CompletableFuture<Boolean> addExempt(String name, String reason, String addedBy) {
        return submit(() -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO exempt (name_lower, reason, added_at, added_by) VALUES (?, ?, ?, ?)
                ON CONFLICT(name_lower) DO UPDATE SET reason = excluded.reason, added_by = excluded.added_by
                """)) {
                statement.setString(1, normalize(name));
                statement.setString(2, reason);
                statement.setLong(3, Instant.now().toEpochMilli());
                statement.setString(4, addedBy);
                return statement.executeUpdate() > 0;
            }
        });
    }

    public CompletableFuture<Boolean> removeExempt(String name) {
        return submit(() -> {
            try (PreparedStatement statement = connection.prepareStatement("DELETE FROM exempt WHERE name_lower = ?")) {
                statement.setString(1, normalize(name));
                return statement.executeUpdate() > 0;
            }
        });
    }

    public CompletableFuture<List<String>> exemptNames() {
        return submit(() -> {
            List<String> names = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("SELECT name_lower FROM exempt ORDER BY name_lower");
                 ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    names.add(result.getString(1));
                }
            }
            return List.copyOf(names);
        });
    }

    public CompletableFuture<Summary> summary() {
        return submit(() -> new Summary(countRows("links"), countRows("exempt"), countRows("account_classification")));
    }

    private int countRows(String table) throws SQLException {
        if (!"links".equals(table) && !"exempt".equals(table) && !"account_classification".equals(table)) {
            throw new IllegalArgumentException("Unsupported table name");
        }
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            return result.next() ? result.getInt(1) : 0;
        }
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

    private void writeLink(String normalizedName, String mojangUuid, String canonicalName, String source)
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
            """)) {
            statement.setString(1, normalizedName);
            statement.setString(2, mojangUuid);
            statement.setString(3, canonicalName);
            statement.setLong(4, now);
            statement.setLong(5, now);
            statement.setString(6, source);
            statement.executeUpdate();
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

    public record AccountState(Optional<Link> link, boolean exempt) {
    }

    public record Summary(int links, int exemptNames, int classifications) {
    }

    public enum ClassificationResult { SET, ALREADY_SAME, ALREADY_DIFFERENT }
}
