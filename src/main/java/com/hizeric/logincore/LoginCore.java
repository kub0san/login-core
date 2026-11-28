package com.hizeric.logincore;

import com.hizeric.logincore.authme.AuthMeLoginListener;
import com.hizeric.logincore.command.AdminCommand;
import com.hizeric.logincore.command.ChangepasswordCommand;
import com.hizeric.logincore.command.LoginCommand;
import com.hizeric.logincore.command.RegisterCommand;
import com.hizeric.logincore.config.ConfigUpdater;
import com.hizeric.logincore.config.LegacyAuthMeImport;
import com.hizeric.logincore.handshake.LoginMetrics;
import com.hizeric.logincore.handshake.PremiumSupport;
import com.hizeric.logincore.handshake.PremiumSupportFactory;
import com.hizeric.logincore.mojang.LookupCache;
import com.hizeric.logincore.mojang.MojangClient;
import com.hizeric.logincore.mojang.RateBudget;
import com.hizeric.logincore.session.AuthState;
import com.hizeric.logincore.session.PreAuthRestrictionListener;
import com.hizeric.logincore.session.VerifiedSessions;
import com.hizeric.logincore.storage.Storage;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public final class LoginCore extends JavaPlugin {

    private PremiumSupport premiumSupport;
    private ExecutorService workers;
    private Storage storage;
    private boolean migrationEnabled = true;
    private boolean cookieEnabled = true;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        try {
            prepareConfiguration();
        } catch (IOException | RuntimeException ex) {
            getLogger().severe("login-core could not prepare configuration: " + ex.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        if (getServer().getOnlineMode()) {
            getLogger().severe("login-core requires an offline-mode server. Set online-mode=false in server.properties.");
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
        if (!validConfiguration()) {
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        try {
            storage = new Storage(getDataFolder().toPath().resolve("authme.db"));
            workers = new ThreadPoolExecutor(2, 4, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(256), runnable -> {
                    Thread thread = new Thread(runnable, "login-core-Worker");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());

            AuthState authState = new AuthState();
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
            LoginMetrics loginMetrics = new LoginMetrics();
            premiumSupport = PremiumSupportFactory.create(this, storage, verifiedSessions, mojang, workers, loginMetrics);
            premiumSupport.registerBukkitEvents();
            AuthMeLoginListener authListener =
                new AuthMeLoginListener(this, authState, storage, verifiedSessions,
                    premiumSupport.release()::releaseAfterAuth, migrationEnabled, cookieEnabled);
            getServer().getPluginManager().registerEvents(authListener, this);

            AdminCommand adminCommand =
                new AdminCommand(this, storage, mojang, workers, loginMetrics);
            getCommand("logincore").setExecutor(adminCommand);
            getCommand("logincore").setTabCompleter(adminCommand);

            LoginCommand loginCommand = new LoginCommand(this, workers, storage, authState,
                authListener::onPasswordLogin);
            RegisterCommand registerCommand = new RegisterCommand(this, workers, storage, authState,
                getConfig().getString("mode", "ENFORCE"), (name, ip) -> mojang.lookupName(name, ip));
            ChangepasswordCommand changepasswordCommand = new ChangepasswordCommand(this, workers, storage, authState);
            getCommand("login").setExecutor(loginCommand);
            getCommand("login").setTabCompleter(loginCommand);
            getCommand("register").setExecutor(registerCommand);
            getCommand("register").setTabCompleter(registerCommand);
            getCommand("changepassword").setExecutor(changepasswordCommand);
            getCommand("changepassword").setTabCompleter(changepasswordCommand);
            getServer().getPluginManager().registerEvents(
                new PreAuthRestrictionListener(this, authState, storage, workers, premiumSupport::available), this);
            getLogger().info("login-core login verification is active in " + getConfig().getString("mode") + " mode.");
        } catch (SQLException | IOException ex) {
            getLogger().severe("login-core could not initialize required storage or encryption: " + ex.getMessage());
            getServer().getPluginManager().disablePlugin(this);
        } catch (RuntimeException ex) {
            getLogger().severe("login-core initialization failed: " + ex.getMessage());
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    private void prepareConfiguration() throws IOException {
        byte[] defaults;
        try (InputStream bundled = getResource("config.yml")) {
            if (bundled == null) {
                throw new IOException("Bundled config.yml resource is missing");
            }
            defaults = bundled.readAllBytes();
        }

        var dataFolder = getDataFolder().toPath();
        LegacyAuthMeImport.ImportResult legacy = LegacyAuthMeImport.importIfNeeded(
            dataFolder, new ByteArrayInputStream(defaults), getLogger());
        if (legacy.imported()) {
            getLogger().info("Imported legacy AuthMe config: " + legacy.droppedLegacyKeys()
                + " legacy key(s) dropped, databaseCopied=" + legacy.databaseCopied()
                + ", backup=" + legacy.backup().getFileName());
        }
        ConfigUpdater.UpdateResult update = ConfigUpdater.reconcile(
            dataFolder.resolve("config.yml"), new ByteArrayInputStream(defaults), getLogger());
        if (update.changed()) {
            getLogger().info("Config updated: " + update.added() + " key(s) added, "
                + update.removed() + " key(s) removed.");
        }
        reloadConfig();
    }

    @Override
    public void onDisable() {
        // Order is a shutdown drain: connection cleanup finishes before workers stop, and
        // storage.close() is last because it waits for its single SQL worker to finish first.
        if (premiumSupport != null) {
            premiumSupport.shutdown();
        }
        if (workers != null) {
            workers.shutdownNow();
        }
        if (storage != null) {
            storage.close();
        }
        getLogger().info("login-core shut down.");
    }

    private boolean validConfiguration() {
        String mode = getConfig().getString("mode", "ENFORCE");
        if (!"ENFORCE".equalsIgnoreCase(mode) && !"SHADOW".equalsIgnoreCase(mode)) {
            getLogger().severe("Invalid mode '" + mode + "'. Expected ENFORCE or SHADOW.");
            return false;
        }
        String lookupFailure = getConfig().getString("on-lookup-failure", "DENY");
        if (!validLookupFailurePolicy(lookupFailure)) {
            getLogger().severe("Invalid on-lookup-failure value '" + lookupFailure + "'. Expected ALLOW or DENY.");
            return false;
        }
        if ("ALLOW".equalsIgnoreCase(lookupFailure)) {
            // Tolerated for compatibility only: UNKNOWN lookups are always denied.
            getLogger().warning("on-lookup-failure: ALLOW is deprecated and ignored; UNKNOWN lookups are denied.");
        }
        String policy = getConfig().getString("existing-account-policy", "REQUIRE_PASSWORD_ONCE");
        if (!validExistingAccountPolicy(policy)) {
            getLogger().severe("Invalid existing-account-policy value '" + policy + "'.");
            return false;
        }
        cookieEnabled = getConfig().getBoolean("cookie-enabled", true);
        boolean configuredMigration = getConfig().getBoolean("migration-enabled", true);
        // Q7: eviction without a cookie means an evicted cracked account can never be reclaimed.
        migrationEnabled = configuredMigration && cookieEnabled;
        if (configuredMigration && !cookieEnabled) {
            getLogger().warning("migration-enabled requires cookie-enabled: premium-rename migration is disabled.");
        }
        for (String key : new String[]{"mojang.lookup-url", "mojang.lookup-fallback-url", "mojang.session-url"}) {
            String url = getConfig().getString(key);
            if (url == null || url.isBlank()) {
                getLogger().severe("Missing required configuration value: " + key);
                return false;
            }
        }
        if (getCommand("logincore") == null) {
            getLogger().severe("The /logincore command is missing from plugin.yml.");
            return false;
        }
        return true;
    }

    static boolean validLookupFailurePolicy(String value) {
        return "ALLOW".equalsIgnoreCase(value) || "DENY".equalsIgnoreCase(value);
    }

    static boolean validExistingAccountPolicy(String policy) {
        return "REQUIRE_PASSWORD_ONCE".equalsIgnoreCase(policy) || "DENY".equalsIgnoreCase(policy);
    }

}
