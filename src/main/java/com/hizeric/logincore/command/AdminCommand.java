package com.hizeric.logincore.command;

import com.hizeric.logincore.handshake.LoginMetrics;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;

public final class AdminCommand implements CommandExecutor, TabCompleter {

    private static final List<String> ROOT_ARGUMENTS = List.of("status");

    private final JavaPlugin plugin;
    private final Storage storage;
    private final MojangClient mojang;
    private final ExecutorService workers;
    private final LoginMetrics loginMetrics;

    public AdminCommand(JavaPlugin plugin, Storage storage, MojangClient mojang,
                        ExecutorService workers, LoginMetrics loginMetrics) {
        this.plugin = plugin;
        this.storage = storage;
        this.mojang = mojang;
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
        usage(sender);
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
                default -> List.of();
            };
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
            sender.sendMessage(ChatColor.GRAY + "Links: " + summary.links());
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
        return "Registered: " + registered + ", link: " + link + conflict
            + ", classification: " + classification.orElse("UNCLASSIFIED");
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
    }
}
