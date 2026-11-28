package com.hizeric.logincore.command;

import com.hizeric.logincore.events.RegisterEvent;
import com.hizeric.logincore.mojang.LookupResult;
import com.hizeric.logincore.session.AccountCookie;
import com.hizeric.logincore.session.AuthState;
import com.hizeric.logincore.storage.Storage;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.BiFunction;

public final class RegisterCommand implements CommandExecutor, TabCompleter {

    private final JavaPlugin plugin;
    private final Executor workers;
    private final Storage storage;
    private final AuthState authState;
    private final AccountCookie accountCookie;
    private final String mode;
    private final BiFunction<String, String, LookupResult> nameLookup;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    public RegisterCommand(JavaPlugin plugin, Executor workers, Storage storage, AuthState authState,
                           String mode, BiFunction<String, String, LookupResult> nameLookup) {
        this.plugin = plugin;
        this.workers = workers;
        this.storage = storage;
        this.authState = authState;
        this.accountCookie = new AccountCookie(plugin);
        this.mode = mode;
        this.nameLookup = nameLookup;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            message(sender, "<red>Players only.");
            return true;
        }
        if (args.length != 2) {
            message(sender, "<yellow>Usage: /register <password> <password>");
            return true;
        }
        if (!args[0].equals(args[1])) {
            message(sender, "<red>Passwords do not match.");
            return true;
        }
        if (!PasswordPolicy.valid(args[0])) {
            message(sender, "<red>Password must be between 6 and 72 bytes.");
            return true;
        }
        workers.execute(() -> register(sender, player, args[0]));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return List.of();
    }

    private void register(CommandSender sender, Player player, String password) {
        String name = player.getName();
        try {
            String classification = storage.classification(name).get().orElse(null);
            if ("PREMIUM".equalsIgnoreCase(classification)) {
                onMain(() -> message(sender, "<red>Premium accounts do not use passwords."));
                return;
            }
            if ("ENFORCE".equalsIgnoreCase(mode) && !"OFFLINE".equalsIgnoreCase(classification)) {
                LookupResult result = nameLookup.apply(name, remoteIp(player));
                if (result == null || !result.isNotPremium()) {
                    String denial = result != null && result.isPremium()
                        ? "<red>Premium accounts do not use passwords."
                        : "<red>Unable to verify your account name. Try again later.";
                    onMain(() -> message(sender, denial));
                    return;
                }
                storage.setClassification(name, "OFFLINE").get();
            }
            if (!storage.registerPlayer(name, password).get()) {
                onMain(() -> message(sender, "<red>That account is already registered. Use /login."));
                return;
            }
            storage.setLoggedIn(name).get();
            storeAccountCookie(player);
            onMain(() -> {
                authState.authenticate(name);
                plugin.getServer().getPluginManager().callEvent(new RegisterEvent(player));
                message(sender, "<green>Account registered. You are now logged in.");
            });
        } catch (Exception ex) {
            onMain(() -> message(sender, "<red>Registration failed: " + ex.getMessage()));
        }
    }

    /** Best-effort: a missing token or cookie failure must never fail registration. */
    private void storeAccountCookie(Player player) {
        if (!plugin.getConfig().getBoolean("cookie-enabled", true)) {
            return;
        }
        try {
            storage.authmeToken(player.getName()).whenComplete((found, error) -> {
                if (error != null) {
                    plugin.getLogger().warning("Could not look up account token: " + error.getMessage());
                    return;
                }
                if (found == null || found.isEmpty()) {
                    return;
                }
                String token = found.get();
                if (token == null || token.isBlank()) {
                    return;
                }
                onMain(() -> {
                    try {
                        accountCookie.store(player, token);
                    } catch (RuntimeException ex) {
                        plugin.getLogger().warning("Could not store account cookie: " + ex.getMessage());
                    }
                });
            });
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("Could not look up account token: " + ex.getMessage());
        }
    }

    private static String remoteIp(Player player) {
        InetSocketAddress address = player.getAddress();
        if (address == null) {
            return null;
        }
        InetAddress inet = address.getAddress();
        return inet == null ? address.getHostString() : inet.getHostAddress();
    }

    private void onMain(Runnable action) {
        if (!plugin.isEnabled()) {
            action.run();
            return;
        }
        Bukkit.getScheduler().runTask(plugin, action);
    }

    private void message(CommandSender sender, String text) {
        sender.sendMessage(miniMessage.deserialize(text));
    }
}
