package com.hizeric.logincore.session;

import com.hizeric.logincore.storage.Storage;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerAttemptPickupItemEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPickupItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/** Restricts players until they are authenticated or classified PREMIUM. */
public final class PreAuthRestrictionListener implements Listener {

    private static final Set<String> ALLOWED_COMMANDS =
        Set.of("login", "register", "changepassword", "logincore");
    private static final long TIMEOUT_MILLIS = 60_000L;
    private static final long CHECK_PERIOD_TICKS = 100L; // 5 seconds at 20 TPS
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private static final Component COMMAND_DENIED =
        MINI_MESSAGE.deserialize("<red>Log in first: /login or /register.");
    private static final Component MOVE_DENIED =
        MINI_MESSAGE.deserialize("<yellow>Log in to move.");
    private static final Component TIMEOUT_KICK =
        MINI_MESSAGE.deserialize("<red>Login timed out. Reconnect and log in.");
    private static final Component UNAVAILABLE_KICK =
        MINI_MESSAGE.deserialize("<red>Premium login is unavailable. Please try again later.");

    private final JavaPlugin plugin;
    private final AuthState authState;
    private final Storage storage;
    private final AccountCookie accountCookie;
    private final Executor workers;
    private final Supplier<Boolean> premiumAvailable;
    private final ConcurrentMap<String, Long> joinedAt = new ConcurrentHashMap<>();
    private final Set<String> premiumNames = ConcurrentHashMap.newKeySet();
    private final Set<String> moveWarned = ConcurrentHashMap.newKeySet();
    private volatile BukkitTask timeoutTask;

    public PreAuthRestrictionListener(JavaPlugin plugin, AuthState authState, Storage storage, Executor workers,
                                      Supplier<Boolean> premiumAvailable) {
        this.plugin = plugin;
        this.authState = authState;
        this.storage = storage;
        this.accountCookie = new AccountCookie(plugin);
        this.workers = workers;
        this.premiumAvailable = premiumAvailable;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerJoin(PlayerJoinEvent event) {
        onJoin(event.getPlayer());
    }

    void onJoin(Player player) {
        if (player == null) {
            return;
        }
        String name = normalize(player.getName());
        joinedAt.put(name, System.currentTimeMillis());
        moveWarned.remove(name);
        scheduleTimeoutCheck();
        // Fail closed until the lookup says PREMIUM; never block a server thread on storage.
        try {
            storage.classification(name).whenCompleteAsync((result, error) -> onMain(() -> {
                if (error != null || result == null || !joinedAt.containsKey(name)) {
                    return;
                }
                if ("PREMIUM".equalsIgnoreCase(result.orElse(null))) {
                    if (Boolean.TRUE.equals(premiumAvailable.get())) {
                        premiumNames.add(name);
                        authState.authenticate(name);
                    } else {
                        onMain(() -> player.kick(UNAVAILABLE_KICK));
                        authState.logout(name);
                    }
                }
            }), workers);
        } catch (RuntimeException ex) {
            // Storage unavailable during shutdown: the player stays restricted.
        }
        scheduleRestorePrompt(player, name);
    }

    /** Post-join best-effort account restore and login/register prompt; no storage work on the join thread. */
    private void scheduleRestorePrompt(Player player, String name) {
        try {
            Runnable task = () -> restoreAndPrompt(player, name);
            if (plugin.isEnabled()) {
                Bukkit.getScheduler().runTaskLater(plugin, task, 1L);
            } else {
                task.run();
            }
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("Could not schedule account restore for " + name + ": " + ex.getMessage());
        }
    }

    private void restoreAndPrompt(Player player, String name) {
        if (!player.isOnline() || !joinedAt.containsKey(name) || authState.isAuthenticated(name)) {
            return;
        }
        try {
            accountCookie.read(player).whenCompleteAsync((token, error) -> {
                if (!player.isOnline() || !joinedAt.containsKey(name) || authState.isAuthenticated(name)) {
                    return;
                }
                if (error != null) {
                    plugin.getLogger().warning("Could not read account cookie for " + name + ": " + error.getMessage());
                    return;
                }
                try {
                    String archivedToken = token == null || token.isBlank() ? null : token;
                    if (archivedToken != null && storage.findEvicted(archivedToken).join().isPresent()) {
                        Storage.RestoreResult result = storage.restoreEvicted(archivedToken, name).join();
                        if (result == Storage.RestoreResult.RESTORED) {
                            plugin.getLogger().info("restored evicted account for " + name);
                        } else if (result == Storage.RestoreResult.COLLISION) {
                            // Another account owns the name: keep the evicted row untouched.
                            plugin.getLogger().warning("Account restore for " + name
                                + " collided; the archived account was kept.");
                        }
                    }
                    boolean registered = Boolean.TRUE.equals(storage.isRegistered(name).join());
                    showPrompt(player, name, registered);
                } catch (RuntimeException ex) {
                    plugin.getLogger().warning("Could not restore account for " + name + ": " + ex.getMessage());
                }
            }, workers);
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("Could not read account cookie for " + name + ": " + ex.getMessage());
        }
    }

    private void showPrompt(Player player, String name, boolean registered) {
        if (!AccountDialogs.isSupported() || !AccountDialogs.canBuild() || !AccountDialogs.canShow()) {
            return;
        }
        onMain(() -> {
            try {
                if (!player.isOnline() || !joinedAt.containsKey(name) || authState.isAuthenticated(name)) {
                    return;
                }
                var dialog = registered
                    ? AccountDialogs.buildLoginDialog()
                    : AccountDialogs.buildRegisterDialog();
                AccountDialogs.show(player, dialog);
            } catch (RuntimeException ex) {
                plugin.getLogger().warning("Could not show account dialog for " + name + ": " + ex.getMessage());
            }
        });
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerQuit(PlayerQuitEvent event) {
        onQuit(event.getPlayer());
    }

    void onQuit(Player player) {
        if (player == null) {
            return;
        }
        String name = normalize(player.getName());
        joinedAt.remove(name);
        premiumNames.remove(name);
        moveWarned.remove(name);
        authState.logout(name);
        if (joinedAt.isEmpty() && timeoutTask != null) {
            timeoutTask.cancel();
            timeoutTask = null;
        }
    }

    boolean shouldRestrict(String name) {
        if (name == null) {
            return true;
        }
        String key = normalize(name);
        return !authState.isAuthenticated(key) && !premiumNames.contains(key);
    }

    boolean cancelIfRestricted(Player player, Cancellable event) {
        if (player == null || !shouldRestrict(player.getName())) {
            return false;
        }
        event.setCancelled(true);
        return true;
    }

    boolean hasPendingLogin(String name) {
        return name != null && joinedAt.containsKey(normalize(name));
    }

    static boolean isCommandAllowed(String message) {
        if (message == null) {
            return false;
        }
        String trimmed = message.stripLeading();
        if (!trimmed.startsWith("/")) {
            return false;
        }
        String label = trimmed.substring(1).split("\\s", 2)[0];
        return ALLOWED_COMMANDS.contains(label.toLowerCase(Locale.ROOT));
    }

    static boolean isTimedOut(long joinedAtMillis, long nowMillis, long timeoutMillis) {
        return nowMillis - joinedAtMillis >= timeoutMillis;
    }

    private void scheduleTimeoutCheck() {
        if (!plugin.isEnabled() || timeoutTask != null) {
            return;
        }
        timeoutTask = Bukkit.getScheduler().runTaskTimer(
            plugin, this::checkTimeouts, CHECK_PERIOD_TICKS, CHECK_PERIOD_TICKS);
    }

    private void checkTimeouts() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Long> entry : joinedAt.entrySet()) {
            String name = entry.getKey();
            if (!isTimedOut(entry.getValue(), now, TIMEOUT_MILLIS) || !shouldRestrict(name)) {
                continue;
            }
            Player player = Bukkit.getPlayer(name);
            joinedAt.remove(name);
            premiumNames.remove(name);
            moveWarned.remove(name);
            authState.logout(name);
            if (player != null && player.isOnline()) {
                player.kick(TIMEOUT_KICK);
            }
        }
        if (joinedAt.isEmpty() && timeoutTask != null) {
            timeoutTask.cancel();
            timeoutTask = null;
        }
    }

    private void onMain(Runnable action) {
        if (!plugin.isEnabled()) {
            action.run();
            return;
        }
        Bukkit.getScheduler().runTask(plugin, action);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        if (cancelIfRestricted(event.getPlayer(), event)) {
            event.getPlayer().sendMessage(COMMAND_DENIED);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (isCommandAllowed(event.getMessage())) {
            return;
        }
        if (cancelIfRestricted(event.getPlayer(), event)) {
            event.getPlayer().sendMessage(COMMAND_DENIED);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (!cancelIfRestricted(player, event)) {
            return;
        }
        // Movement fires every tick; warn once per join rather than flooding chat.
        if (moveWarned.add(normalize(player.getName()))) {
            player.sendMessage(MOVE_DENIED);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            cancelIfRestricted(player, event);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            cancelIfRestricted(player, event);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player) {
            cancelIfRestricted(player, event);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        cancelIfRestricted(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        cancelIfRestricted(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player) {
            cancelIfRestricted(player, event);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player) {
            cancelIfRestricted(player, event);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDropItem(PlayerDropItemEvent event) {
        cancelIfRestricted(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPickupItem(PlayerPickupItemEvent event) {
        cancelIfRestricted(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        cancelIfRestricted(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        cancelIfRestricted(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onSwapHandItems(PlayerSwapHandItemsEvent event) {
        cancelIfRestricted(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onVehicleEnter(VehicleEnterEvent event) {
        if (event.getEntered() instanceof Player player) {
            cancelIfRestricted(player, event);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onAttemptPickupItem(PlayerAttemptPickupItemEvent event) {
        cancelIfRestricted(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onProjectileDamage(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player && cancelIfRestricted(player, event)) {
            return;
        }
        if (event.getDamager() instanceof Projectile projectile
            && projectile.getShooter() instanceof Player shooter) {
            cancelIfRestricted(shooter, event);
        }
    }

    private static String normalize(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
