package org.authmePAL;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import org.authmePAL.authme.AuthMeBridge;
import org.authmePAL.authme.AuthMeLoginListener;
import org.authmePAL.command.AdminCommand;
import org.authmePAL.handshake.EncryptionKeys;
import org.authmePAL.handshake.LoginMetrics;
import org.authmePAL.handshake.PremiumLoginListener;
import org.authmePAL.migration.MigrationAudit;
import org.authmePAL.mojang.LookupCache;
import org.authmePAL.mojang.MojangClient;
import org.authmePAL.mojang.RateBudget;
import org.authmePAL.session.VerifiedSessions;
import org.authmePAL.storage.Storage;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.net.http.HttpClient;
import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public final class AuthMePAL extends JavaPlugin {

    private PacketListenerCommon packetListener;
    private PremiumLoginListener loginListener;
    private ExecutorService workers;
    private Storage storage;
    private MigrationAudit migrationAudit;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadConfig();

        Plugin authMePlugin = getServer().getPluginManager().getPlugin("AuthMe");
        if (authMePlugin == null || !authMePlugin.isEnabled()) {
            getLogger().severe("AuthMeReloaded is required; disabling AuthMePAL.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        if (authMePlugin.getConfig().getBoolean("settings.enablePremium", false)) {
            getLogger().severe("AuthMe premium login is enabled. Set settings.enablePremium: false in AuthMe's config before enabling AuthMePAL.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        if (getServer().getOnlineMode()) {
            getLogger().severe("AuthMePAL requires an offline-mode server. Set online-mode=false in server.properties.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        if (getConfig().getBoolean("use-premium-uuid", true)) {
            getLogger().warning("Premium UUID mode is enabled. Verified premium players will use Mojang UUIDs; "
                + "their existing offline-UUID inventories and plugin data will not be migrated.");
            if (getServer().getPluginManager().getPlugin("LuckPerms") != null) {
                getLogger().warning("LuckPerms stores permissions per UUID: grant premium players their groups "
                    + "under their Mojang UUID, or set use-premium-uuid: false to keep offline UUIDs.");
            }
        }
        if (!PacketEvents.getAPI().isInitialized()) {
            getLogger().severe("PacketEvents is not initialized; disabling AuthMePAL.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        if (!validConfiguration()) {
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        try {
            storage = new Storage(getDataFolder().toPath().resolve("data.db"));
            workers = new ThreadPoolExecutor(2, 4, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(256), runnable -> {
                    Thread thread = new Thread(runnable, "AuthMePAL-Worker");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());

            AuthMeBridge authMe = new AuthMeBridge();
            VerifiedSessions verifiedSessions = new VerifiedSessions();
            MojangClient mojang = new MojangClient(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build(),
                getConfig().getString("mojang.lookup-url"),
                getConfig().getString("mojang.lookup-fallback-url"),
                getConfig().getString("mojang.session-url"),
                new LookupCache(),
                new RateBudget(
                    getConfig().getInt("mojang.global-lookups-per-10min", 400),
                    Duration.ofMinutes(10),
                    getConfig().getInt("throttle.per-ip-new-names-per-minute", 5),
                    Duration.ofMinutes(1)
                )
            );
            EncryptionKeys encryptionKeys = new EncryptionKeys();
            LoginMetrics loginMetrics = new LoginMetrics();
            loginListener = new PremiumLoginListener(
                this, authMe, storage, verifiedSessions, mojang, workers, encryptionKeys, loginMetrics);
            packetListener = PacketEvents.getAPI().getEventManager().registerListener(loginListener);
            getServer().getPluginManager().registerEvents(loginListener, this);
            getServer().getPluginManager().registerEvents(
                new AuthMeLoginListener(this, authMe, storage, verifiedSessions, loginListener::releaseAfterAuth), this);

            migrationAudit = new MigrationAudit(this, authMe, storage, mojang);
            AdminCommand adminCommand =
                new AdminCommand(this, authMe, storage, mojang, migrationAudit, workers, loginMetrics);
            getCommand("authmepal").setExecutor(adminCommand);
            getCommand("authmepal").setTabCompleter(adminCommand);
            getLogger().info("AuthMePAL login verification is active in " + getConfig().getString("mode") + " mode.");
        } catch (SQLException | IOException | java.security.GeneralSecurityException ex) {
            getLogger().severe("AuthMePAL could not initialize required storage or encryption: " + ex.getMessage());
            getServer().getPluginManager().disablePlugin(this);
        } catch (RuntimeException ex) {
            getLogger().severe("AuthMePAL initialization failed: " + ex.getMessage());
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        if (packetListener != null && PacketEvents.getAPI() != null) {
            PacketEvents.getAPI().getEventManager().unregisterListener(packetListener);
        }
        if (loginListener != null) {
            loginListener.shutdown();
        }
        if (migrationAudit != null) {
            migrationAudit.close();
        }
        if (workers != null) {
            workers.shutdownNow();
        }
        if (storage != null) {
            storage.close();
        }
        getLogger().info("AuthMePAL shut down.");
    }

    private boolean validConfiguration() {
        String mode = getConfig().getString("mode", "ENFORCE");
        if (!"ENFORCE".equalsIgnoreCase(mode) && !"SHADOW".equalsIgnoreCase(mode)) {
            getLogger().severe("Invalid mode '" + mode + "'. Expected ENFORCE or SHADOW.");
            return false;
        }
        String lookupFailure = getConfig().getString("on-lookup-failure", "ALLOW");
        if (!"ALLOW".equalsIgnoreCase(lookupFailure) && !"DENY".equalsIgnoreCase(lookupFailure)) {
            getLogger().severe("Invalid on-lookup-failure value '" + lookupFailure + "'. Expected ALLOW or DENY.");
            return false;
        }
        String policy = getConfig().getString("existing-account-policy", "REQUIRE_PASSWORD_ONCE");
        if (!"REQUIRE_PASSWORD_ONCE".equalsIgnoreCase(policy)
            && !"ADOPT".equalsIgnoreCase(policy)
            && !"DENY".equalsIgnoreCase(policy)) {
            getLogger().severe("Invalid existing-account-policy value '" + policy + "'.");
            return false;
        }
        for (String key : new String[]{"mojang.lookup-url", "mojang.lookup-fallback-url", "mojang.session-url"}) {
            String url = getConfig().getString(key);
            if (url == null || url.isBlank()) {
                getLogger().severe("Missing required configuration value: " + key);
                return false;
            }
        }
        if (getCommand("authmepal") == null) {
            getLogger().severe("The /authmepal command is missing from plugin.yml.");
            return false;
        }
        return true;
    }

}
