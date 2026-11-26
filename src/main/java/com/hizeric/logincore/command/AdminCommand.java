package com.hizeric.logincore.command;

import com.hizeric.logincore.authme.AuthMeBridge;
import com.hizeric.logincore.handshake.LoginMetrics;
import com.hizeric.logincore.migration.MigrationAudit;
import com.hizeric.logincore.mojang.LookupResult;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;

public final class AdminCommand implements CommandExecutor, TabCompleter {

    private static final List<String> ROOT_ARGUMENTS = List.of("status", "exempt", "link", "unlink", "migrate");
    private static final List<String> MIGRATION_ARGUMENTS = List.of("audit", "apply");
    private static final List<String> EXEMPT_ARGUMENTS = List.of("add", "remove", "list");

    private final JavaPlugin plugin;
    private final AuthMeBridge authMe;
    private final Storage storage;
    private final MojangClient mojang;
    private final MigrationAudit migrationAudit;
    private final ExecutorService workers;
    private final LoginMetrics loginMetrics;

    public AdminCommand(JavaPlugin plugin, AuthMeBridge authMe, Storage storage, MojangClient mojang,
                        MigrationAudit migrationAudit, ExecutorService workers, LoginMetrics loginMetrics) {
        this.plugin = plugin;
        this.authMe = authMe;
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
            case "link" -> handleLink(sender, args);
            case "unlink" -> handleUnlink(sender, args);
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
                case "status", "link", "unlink" -> matching(knownPlayerNames(), args[1]);
                case "exempt" -> matching(EXEMPT_ARGUMENTS, args[1]);
                case "migrate" -> matching(MIGRATION_ARGUMENTS, args[1]);
                default -> List.of();
            };
        }
        if (args.length == 3 && "exempt".equalsIgnoreCase(args[0])
            && ("add".equalsIgnoreCase(args[1]) || "remove".equalsIgnoreCase(args[1]))) {
            return matching(knownPlayerNames(), args[2]);
        }
        if (args.length == 3 && "link".equalsIgnoreCase(args[0])) {
            return matching(List.of("auto"), args[2]);
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
        CompletableFuture<Boolean> registered = CompletableFuture.supplyAsync(() -> authMe.isRegistered(name), workers);
        storage.account(name).thenCombineAsync(registered, (account, isRegistered) -> {
            String link = account.link().map(value -> value.mojangUuid() + " (" + value.source() + ")").orElse("none");
            return "AuthMe registered: " + isRegistered + ", link: " + link + ", exempt: " + account.exempt();
        }, workers).whenComplete((result, error) -> onMain(sender, () -> {
            if (error != null) {
                reportError(sender, error);
            } else {
                sender.sendMessage(ChatColor.GOLD + name + ChatColor.GRAY + " — " + result);
            }
        }));
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

    private void handleLink(CommandSender sender, String[] args) {
        if (args.length != 3) {
            usage(sender);
            return;
        }
        String name = args[1];
        if ("auto".equalsIgnoreCase(args[2])) {
            CompletableFuture.supplyAsync(() -> mojang.lookupName(name), workers)
                .thenCompose(result -> saveAdminLink(name, result))
                .whenComplete((ignored, error) -> onMain(sender, () -> {
                    if (error != null) {
                        reportError(sender, error);
                    } else {
                        sender.sendMessage(ChatColor.GREEN + "Premium account link saved for " + name + ".");
                    }
                }));
            return;
        }
        try {
            UUID uuid = UUID.fromString(args[2]);
            storage.putLink(name, uuid.toString(), name, "ADMIN").whenComplete((ignored, error) -> onMain(sender, () -> {
                if (error != null) {
                    reportError(sender, error);
                } else {
                    sender.sendMessage(ChatColor.GREEN + "Account link saved for " + name + ".");
                }
            }));
        } catch (IllegalArgumentException ex) {
            sender.sendMessage(ChatColor.RED + "The UUID is invalid; use /logincore link <name> <uuid|auto>.");
        }
    }

    private CompletableFuture<Void> saveAdminLink(String name, LookupResult result) {
        if (!result.isPremium()) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                "Mojang lookup did not confirm a premium account (" + result.type() + ")."));
        }
        return storage.putLink(name, result.mojangUuid().toString(), result.canonicalName(), "ADMIN");
    }

    private void handleUnlink(CommandSender sender, String[] args) {
        if (args.length != 2) {
            usage(sender);
            return;
        }
        storage.unlink(args[1]).whenComplete((removed, error) -> onMain(sender, () -> {
            if (error != null) {
                reportError(sender, error);
            } else {
                sender.sendMessage(removed ? ChatColor.GREEN + "Account link removed for " + args[1] + "."
                    : ChatColor.YELLOW + "No account link was found for " + args[1] + ".");
            }
        }));
    }

    private void handleMigrate(CommandSender sender, String[] args) {
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
                .whenComplete((count, error) -> onMain(sender, () -> {
                    if (error != null) {
                        reportError(sender, error);
                    } else {
                        sender.sendMessage(ChatColor.GREEN + "Added " + count + " migration exemptions.");
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
        sender.sendMessage(ChatColor.YELLOW + "/logincore link <name> <uuid|auto> | unlink <name>");
        sender.sendMessage(ChatColor.YELLOW + "/logincore migrate audit | migrate apply --exempt-conflicts [--except name,name]");
    }
}
