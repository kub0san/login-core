package com.hizeric.logincore.command;

import com.hizeric.logincore.handshake.LoginMetrics;
import com.hizeric.logincore.migration.MigrationAudit;
import com.hizeric.logincore.mojang.MojangClient;
import com.hizeric.logincore.storage.Storage;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;

public final class AdminCommand implements CommandExecutor, TabCompleter {

    private static final List<String> ROOT_ARGUMENTS = List.of("status", "exempt", "migrate");
    private static final List<String> MIGRATION_ARGUMENTS = List.of("audit", "apply", "classify");
    private static final List<String> EXEMPT_ARGUMENTS = List.of("add", "remove", "list");

    private final JavaPlugin plugin;
    private final Storage storage;
    private final MojangClient mojang;
    private final MigrationAudit migrationAudit;
    private final ExecutorService workers;
    private final LoginMetrics loginMetrics;

    public AdminCommand(JavaPlugin plugin, Storage storage, MojangClient mojang,
                        MigrationAudit migrationAudit, ExecutorService workers, LoginMetrics loginMetrics) {
        this.plugin = plugin;
        this.storage = storage;
        this.mojang = mojang;
        this.migrationAudit = migrationAudit;
        this.workers = workers;
        this.loginMetrics = loginMetrics;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("logincore.admin")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to use this command.");
            return true;
        }
        if (args.length == 0 || "status".equalsIgnoreCase(args[0])) {
            if (args.length == 1) {
                showSummary(sender);
            } else if (args.length == 2) {
                showPlayerStatus(sender, args[1]);
            } else {
                usage(sender);
            }
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "exempt" -> handleExempt(sender, args);
            case "migrate" -> handleMigrate(sender, args);
            default -> usage(sender);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("logincore.admin")) {
            return List.of();
        }
        if (args.length == 1) {
            return matching(ROOT_ARGUMENTS, args[0]);
        }
        if (args.length == 2) {
            return switch (args[0].toLowerCase(Locale.ROOT)) {
                case "status" -> matching(knownPlayerNames(), args[1]);
                case "exempt" -> matching(EXEMPT_ARGUMENTS, args[1]);
                case "migrate" -> matching(MIGRATION_ARGUMENTS, args[1]);
                default -> List.of();
            };
        }
        if (args.length == 3 && "exempt".equalsIgnoreCase(args[0])
            && ("add".equalsIgnoreCase(args[1]) || "remove".equalsIgnoreCase(args[1]))) {
            return matching(knownPlayerNames(), args[2]);
        }
        if (args.length == 3 && "migrate".equalsIgnoreCase(args[0])
            && "apply".equalsIgnoreCase(args[1])) {
            return matching(List.of("--exempt-conflicts"), args[2]);
        }
        if (args.length == 4 && "migrate".equalsIgnoreCase(args[0])
            && "apply".equalsIgnoreCase(args[1])
            && "--exempt-conflicts".equalsIgnoreCase(args[2])) {
            return matching(List.of("--except"), args[3]);
        }
        if (args.length == 5 && "migrate".equalsIgnoreCase(args[0])
            && "apply".equalsIgnoreCase(args[1])
            && "--exempt-conflicts".equalsIgnoreCase(args[2])
            && "--except".equalsIgnoreCase(args[3])) {
            return matching(knownPlayerNames(), args[4]);
        }
        return List.of();
    }

    private List<String> knownPlayerNames() {
        List<String> names = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            names.add(player.getName());
        }
        return names;
    }

    private List<String> matching(List<String> candidates, String input) {
        String prefix = input.toLowerCase(Locale.ROOT);
        return candidates.stream()
            .filter(candidate -> candidate.toLowerCase(Locale.ROOT).startsWith(prefix))
            .distinct()
            .toList();
    }

    private void showSummary(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "login-core mode: " + plugin.getConfig().getString("mode"));
        storage.summary().whenComplete((summary, error) -> onMain(sender, () -> {
            if (error != null) {
                reportError(sender, error);
                return;
            }
            MojangClient.Metrics lookups = mojang.metrics();
            LoginMetrics.Snapshot handshakes = loginMetrics.snapshot();
            sender.sendMessage(ChatColor.GRAY + "Links: " + summary.links() + ", exempt names: " + summary.exemptNames());
            sender.sendMessage(ChatColor.GRAY + "Lookups: " + lookups.lookupCalls() + ", cache hits: "
                + lookups.cacheHits() + ", HTTP requests: " + lookups.httpRequests() + ", HTTP 429s: "
                + lookups.rateLimitedResponses());
            sender.sendMessage(ChatColor.GRAY + "Premium verifications: " + handshakes.verified()
                + ", failed: " + handshakes.failed() + ", throttled: " + handshakes.throttled());
        }));
    }

    private void showPlayerStatus(CommandSender sender, String name) {
        CompletableFuture<Boolean> registered = storage.isRegistered(name);
        CompletableFuture<Optional<String>> classification = storage.classification(name);
        CompletableFuture<Storage.AccountState> account = storage.account(name);
        // A renamed premium name has no link of its own; resolve its UUID to surface the stale link.
        CompletableFuture<Optional<Storage.Link>> uuidLink = CompletableFuture.supplyAsync(() -> {
            UUID uuid = mojang.lookupName(name).mojangUuid();
            return uuid == null ? Optional.empty() : storage.linkByUuid(uuid.toString()).join();
        }, workers);
        CompletableFuture.allOf(account, registered, classification, uuidLink)
            .thenApplyAsync(ignored -> statusLine(account.join(), registered.join(), classification.join(),
                uuidLink.join()), workers)
            .whenComplete((result, error) -> onMain(sender, () -> {
                if (error != null) {
                    reportError(sender, error);
                } else {
                    sender.sendMessage(ChatColor.GOLD + name + ChatColor.GRAY + " — " + result);
                }
            }));
    }

    /** Read-only status text; classification is reported, never written here. */
    static String statusLine(Storage.AccountState account, boolean registered, Optional<String> classification) {
        return statusLine(account, registered, classification, Optional.empty());
    }

    /** Read-only status text; a UUID linked under another name is shown, never rewritten. */
    static String statusLine(Storage.AccountState account, boolean registered, Optional<String> classification,
                             Optional<Storage.Link> uuidLink) {
        String link = account.link().map(value -> value.mojangUuid() + " (" + value.source() + ")").orElse("none");
        String conflict = uuidLink.filter(value -> account.link().isEmpty())
            .map(value -> ", linked-name: " + value.name()).orElse("");
        return "AuthMe registered: " + registered + ", link: " + link + conflict
            + ", exempt: " + account.exempt() + ", classification: " + classification.orElse("UNCLASSIFIED");
    }

    private void handleExempt(CommandSender sender, String[] args) {
        if (args.length == 2 && "list".equalsIgnoreCase(args[1])) {
            storage.exemptNames().whenComplete((names, error) -> onMain(sender, () -> {
                if (error != null) {
                    reportError(sender, error);
                } else if (names.isEmpty()) {
                    sender.sendMessage(ChatColor.GRAY + "No exempt names.");
                } else {
                    sender.sendMessage(ChatColor.GOLD + "Exempt names: " + String.join(", ", names));
                }
            }));
            return;
        }
        if (args.length != 3 || (!"add".equalsIgnoreCase(args[1]) && !"remove".equalsIgnoreCase(args[1]))) {
            usage(sender);
            return;
        }
        String name = args[2];
        if ("add".equalsIgnoreCase(args[1])) {
            storage.addExempt(name, "Added by administrator", sender.getName())
                .whenComplete((added, error) -> onMain(sender, () -> {
                    if (error != null) {
                        reportError(sender, error);
                    } else {
                        sender.sendMessage(ChatColor.GREEN + "Exemption saved for " + name + ".");
                    }
                }));
        } else {
            storage.removeExempt(name).whenComplete((removed, error) -> onMain(sender, () -> {
                if (error != null) {
                    reportError(sender, error);
                } else {
                    sender.sendMessage(removed ? ChatColor.GREEN + "Exemption removed for " + name + "."
                        : ChatColor.YELLOW + "No exemption was found for " + name + ".");
                }
            }));
        }
    }

    private void handleMigrate(CommandSender sender, String[] args) {
        if (args.length == 2 && "classify".equalsIgnoreCase(args[1])) {
            storage.migrateLegacyClassification().whenComplete((report, error) -> onMain(sender, () -> {
                if (error != null) {
                    reportError(sender, error);
                    return;
                }
                sender.sendMessage(ChatColor.GREEN + "Legacy classification: classified " + report.classifiedPremium()
                    + " PREMIUM, left " + report.leftUnclassified() + " unclassified, already classified "
                    + report.alreadyClassified() + ".");
                sender.sendMessage(ChatColor.GRAY + "Backup: "
                    + (report.backupPath() == null ? "none (empty database)" : report.backupPath()));
            }));
            return;
        }
        if (args.length == 2 && "audit".equalsIgnoreCase(args[1])) {
            sender.sendMessage(ChatColor.YELLOW + "Migration audit started. This may take about one second per AuthMe account.");
            migrationAudit.startAudit().whenComplete((path, error) -> onMain(sender, () -> {
                if (error != null) {
                    reportError(sender, error);
                } else {
                    sender.sendMessage(ChatColor.GREEN + "Migration audit saved to " + path + ".");
                }
            }));
            return;
        }
        if (args.length >= 3 && "apply".equalsIgnoreCase(args[1])
            && "--exempt-conflicts".equalsIgnoreCase(args[2])) {
            Set<String> exceptNames = Set.of();
            if (args.length == 5 && "--except".equalsIgnoreCase(args[3])) {
                exceptNames = Arrays.stream(args[4].split(","))
                    .map(value -> value.toLowerCase(Locale.ROOT))
                    .collect(Collectors.toUnmodifiableSet());
            } else if (args.length != 3) {
                usage(sender);
                return;
            }
            migrationAudit.applyPremiumNameExemptions(exceptNames, sender.getName())
                .whenComplete((report, error) -> onMain(sender, () -> {
                    if (error != null) {
                        reportError(sender, error);
                    } else {
                        sender.sendMessage(ChatColor.GREEN + "Added " + report.added()
                            + " migration exemptions; skipped " + report.skippedPremium()
                            + " premium-classified name(s).");
                    }
                }));
            return;
        }
        usage(sender);
    }

    private void onMain(CommandSender sender, Runnable action) {
        if (!plugin.isEnabled()) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (sender instanceof org.bukkit.entity.Player player && !player.isOnline()) {
                return;
            }
            action.run();
        });
    }

    private void reportError(CommandSender sender, Throwable error) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null
            ? error.getCause() : error;
        plugin.getLogger().warning("Admin command failed: " + cause.getMessage());
        sender.sendMessage(ChatColor.RED + "Operation failed: " + cause.getMessage());
    }

    private void usage(CommandSender sender) {
        sender.sendMessage(ChatColor.YELLOW + "/logincore status [name]");
        sender.sendMessage(ChatColor.YELLOW + "/logincore exempt add|remove <name> | exempt list");
        sender.sendMessage(ChatColor.YELLOW + "/logincore migrate audit | migrate apply --exempt-conflicts [--except name,name]");
        sender.sendMessage(ChatColor.YELLOW + "/logincore migrate classify");
    }
}
