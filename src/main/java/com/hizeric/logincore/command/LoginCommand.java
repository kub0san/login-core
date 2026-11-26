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

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.function.Predicate;

public final class LoginCommand implements CommandExecutor, TabCompleter {

    private static final int MAX_FAILURES = 5;
    private static final Duration WINDOW = Duration.ofSeconds(60);

    private final JavaPlugin plugin;
    private final Executor workers;
    private final Storage storage;
    private final AuthState authState;
    private final Predicate<Player> onPasswordLogin;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final ConcurrentMap<String, Failures> failures = new ConcurrentHashMap<>();

    public LoginCommand(JavaPlugin plugin, Executor workers, Storage storage, AuthState authState,
                        Predicate<Player> onPasswordLogin) {
        this.plugin = plugin;
        this.workers = workers;
        this.storage = storage;
        this.authState = authState;
        this.onPasswordLogin = onPasswordLogin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            message(sender, "<red>Players only.");
            return true;
        }
        if (args.length != 1) {
            message(sender, "<yellow>Usage: /login <password>");
            return true;
        }
        workers.execute(() -> login(sender, player, normalize(player.getName()), args[0]));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return List.of();
    }

    private void login(CommandSender sender, Player player, String name, String password) {
        try {
            if ("PREMIUM".equals(storage.classification(name).get().orElse(null))) {
                onMain(() -> message(sender, "<red>Premium accounts do not use passwords."));
                return;
            }
            if (storage.verifyPassword(name, password).get()) {
                storage.setLoggedIn(name).get();
                onMain(() -> {
                    if (!onPasswordLogin.test(player)) {
                        return;
                    }
                    failures.remove(name);
                    authState.authenticate(name);
                    message(sender, "<green>You are now logged in.");
                });
                return;
            }
            Failures updated = failures.compute(name, (key, previous) -> {
                Instant now = Instant.now();
                if (previous == null || !previous.firstAt().plus(WINDOW).isAfter(now)) {
                    return new Failures(1, now);
                }
                return new Failures(previous.count() + 1, previous.firstAt());
            });
            if (updated.count() >= MAX_FAILURES && failures.remove(name, updated)) {
                onMain(() -> player.kick(miniMessage.deserialize(
                    "<red>Too many failed login attempts. Reconnect and try again.")));
            } else {
                int remaining = Math.max(0, MAX_FAILURES - updated.count());
                onMain(() -> message(sender, "<red>Incorrect password. " + remaining + " attempts remaining."));
            }
        } catch (Exception ex) {
            onMain(() -> message(sender, "<red>Login failed: " + ex.getMessage()));
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

    private static String normalize(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    private record Failures(int count, Instant firstAt) {
    }
}
