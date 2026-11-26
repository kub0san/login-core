package com.hizeric.logincore.command;

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

import java.util.List;
import java.util.concurrent.Executor;

public final class RegisterCommand implements CommandExecutor, TabCompleter {

    private final JavaPlugin plugin;
    private final Executor workers;
    private final Storage storage;
    private final AuthState authState;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    public RegisterCommand(JavaPlugin plugin, Executor workers, Storage storage, AuthState authState) {
        this.plugin = plugin;
        this.workers = workers;
        this.storage = storage;
        this.authState = authState;
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
        workers.execute(() -> register(sender, player.getName(), args[0]));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return List.of();
    }

    private void register(CommandSender sender, String name, String password) {
        try {
            if ("PREMIUM".equals(storage.classification(name).get().orElse(null))) {
                onMain(() -> message(sender, "<red>Premium accounts do not use passwords."));
                return;
            }
            if (!storage.registerPlayer(name, password).get()) {
                onMain(() -> message(sender, "<red>That account is already registered. Use /login."));
                return;
            }
            storage.setLoggedIn(name).get();
            onMain(() -> {
                authState.authenticate(name);
                message(sender, "<green>Account registered. You are now logged in.");
            });
        } catch (Exception ex) {
            onMain(() -> message(sender, "<red>Registration failed: " + ex.getMessage()));
        }
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
