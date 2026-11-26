package com.hizeric.logincore.command;

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

public final class ChangepasswordCommand implements CommandExecutor, TabCompleter {

    private final JavaPlugin plugin;
    private final Executor workers;
    private final Storage storage;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    public ChangepasswordCommand(JavaPlugin plugin, Executor workers, Storage storage) {
        this.plugin = plugin;
        this.workers = workers;
        this.storage = storage;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            message(sender, "<red>Players only.");
            return true;
        }
        if (args.length != 2) {
            message(sender, "<yellow>Usage: /changepassword <old> <new>");
            return true;
        }
        if (!PasswordPolicy.valid(args[1])) {
            message(sender, "<red>Password must be between 6 and 72 bytes.");
            return true;
        }
        workers.execute(() -> changePassword(sender, player.getName(), args[0], args[1]));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return List.of();
    }

    private void changePassword(CommandSender sender, String name, String oldPassword, String newPassword) {
        try {
            if ("PREMIUM".equals(storage.classification(name).get().orElse(null))) {
                onMain(() -> message(sender, "<red>Premium accounts do not use passwords."));
                return;
            }
            if (storage.changePassword(name, oldPassword, newPassword).get()) {
                onMain(() -> message(sender, "<green>Your password changed."));
            } else {
                onMain(() -> message(sender, "<red>Current password is incorrect."));
            }
        } catch (Exception ex) {
            onMain(() -> message(sender, "<red>Password change failed: " + ex.getMessage()));
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
