package com.hizeric.logincore.migration;

import com.hizeric.logincore.mojang.LookupResult;
import com.hizeric.logincore.mojang.MojangClient;
import com.hizeric.logincore.storage.Storage;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MigrationAudit implements AutoCloseable {

    public record ExemptionReport(int added, int skippedPremium) {
    }

    private static final String HEADER = "name,status,mojang_uuid,last_login,last_ip,registered_at";

    private final JavaPlugin plugin;
    private final Path dataFolder;
    private final Storage storage;
    private final MojangClient mojang;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "login-core-MigrationAudit");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean running = new AtomicBoolean();

    public MigrationAudit(JavaPlugin plugin, Storage storage, MojangClient mojang) {
        this.plugin = plugin;
        this.dataFolder = plugin.getDataFolder().toPath();
        this.storage = storage;
        this.mojang = mojang;
    }

    /** The apply path only needs the audit directory and storage; no plugin or authme singleton. */
    MigrationAudit(Path dataFolder, Storage storage) {
        this.plugin = null;
        this.dataFolder = dataFolder;
        this.storage = storage;
        this.mojang = null;
    }

    public CompletableFuture<Path> startAudit() {
        if (!running.compareAndSet(false, true)) {
            return CompletableFuture.failedFuture(new IllegalStateException("A migration audit is already running"));
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                return runAudit();
            } catch (IOException | InterruptedException ex) {
                if (ex instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                throw new CompletionException(ex);
            } finally {
                running.set(false);
            }
        }, executor);
    }

    public CompletableFuture<ExemptionReport> applyPremiumNameExemptions(Set<String> exceptNames, String addedBy) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Path audit = latestAudit();
                if (audit == null) {
                    throw new IllegalStateException("No migration audit exists. Run /logincore migrate audit first.");
                }
                int added = 0;
                int skippedPremium = 0;
                for (String line : Files.readAllLines(audit, StandardCharsets.UTF_8).stream().skip(1).toList()) {
                    String[] columns = parseCsvLine(line);
                    if (columns.length < 2 || !"PREMIUM_NAME".equals(columns[1])) {
                        continue;
                    }
                    String normalizedName = columns[0].toLowerCase(Locale.ROOT);
                    if (exceptNames.contains(normalizedName)) {
                        continue;
                    }
                    // An exemption must never neutralize an authoritative PREMIUM classification.
                    if ("PREMIUM".equals(storage.classification(normalizedName).join().orElse(null))) {
                        skippedPremium++;
                        continue;
                    }
                    storage.addExempt(columns[0], "Migration audit premium-name conflict", addedBy).join();
                    added++;
                }
                return new ExemptionReport(added, skippedPremium);
            } catch (IOException ex) {
                throw new CompletionException(ex);
            }
        }, executor);
    }

    private Path runAudit() throws IOException, InterruptedException {
        Path directory = dataFolder.resolve("migration");
        Files.createDirectories(directory);
        Path audit = latestAudit();
        if (audit == null) {
            String timestamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                .withZone(java.time.ZoneOffset.UTC).format(Instant.now());
            audit = directory.resolve("audit-" + timestamp + ".csv");
            Files.writeString(audit, HEADER + System.lineSeparator(), StandardCharsets.UTF_8);
            Files.writeString(directory.resolve("current-audit.txt"), audit.getFileName().toString(), StandardCharsets.UTF_8);
        }
        Set<String> completed = completedNames(audit);
        List<String> names = storage.registeredNames().join();
        int processed = 0;
        for (String name : names) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Migration audit interrupted");
            }
            String normalizedName = name.toLowerCase(Locale.ROOT);
            if (completed.contains(normalizedName)) {
                continue;
            }
            Storage.PlayerInfo info = storage.playerInfo(name).join().orElse(null);
            LookupResult lookup = mojang.lookupName(name);
            String status = lookup.isPremium() ? "PREMIUM_NAME"
                : lookup.isNotPremium() ? "NOT_PREMIUM" : "LOOKUP_ERROR";
            String uuid = lookup.isPremium() ? lookup.mojangUuid().toString() : "";
            String lastLogin = info == null ? "" : info.lastLogin().map(Instant::toString).orElse("");
            String lastIp = info == null ? "" : info.lastIp().orElse("");
            String registeredAt = info == null ? "" : info.registeredAt().toString();
            String row = csv(name) + "," + status + "," + csv(uuid) + "," + csv(lastLogin) + ","
                + csv(lastIp) + "," + csv(registeredAt) + System.lineSeparator();
            Files.writeString(audit, row, StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
            if (!"LOOKUP_ERROR".equals(status)) {
                completed.add(normalizedName);
            }
            processed++;
            if (processed < names.size()) {
                Thread.sleep(1000);
            }
        }
        return audit;
    }

    private Path latestAudit() throws IOException {
        Path directory = dataFolder.resolve("migration");
        Path current = directory.resolve("current-audit.txt");
        if (!Files.isRegularFile(current)) {
            return null;
        }
        String filename = Files.readString(current, StandardCharsets.UTF_8).trim();
        if (filename.isBlank() || filename.contains("/") || filename.contains("\\")) {
            throw new IOException("Invalid current audit filename");
        }
        Path candidate = directory.resolve(filename).normalize();
        if (!candidate.getParent().equals(directory.normalize()) || !Files.isRegularFile(candidate)) {
            throw new IOException("Current migration audit file is missing or outside the migration directory");
        }
        return candidate;
    }

    private Set<String> completedNames(Path audit) throws IOException {
        Set<String> names = new HashSet<>();
        List<String> lines = Files.readAllLines(audit, StandardCharsets.UTF_8);
        for (String line : lines.stream().skip(1).toList()) {
            String[] columns = parseCsvLine(line);
            if (columns.length >= 2 && !"LOOKUP_ERROR".equals(columns[1])) {
                names.add(columns[0].toLowerCase(Locale.ROOT));
            }
        }
        return names;
    }

    private String[] parseCsvLine(String line) {
        String[] columns = new String[6];
        int index = 0;
        StringBuilder value = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length() && index < columns.length; i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    value.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (ch == ',' && !quoted) {
                columns[index++] = value.toString();
                value.setLength(0);
            } else {
                value.append(ch);
            }
        }
        if (index < columns.length) {
            columns[index] = value.toString();
        }
        return columns;
    }

    private String csv(String value) {
        String safeValue = value == null ? "" : value;
        return "\"" + safeValue.replace("\"", "\"\"") + "\"";
    }

    @Override
    public void close() {
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS) && plugin != null) {
                plugin.getLogger().warning("Migration audit worker did not stop promptly.");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
