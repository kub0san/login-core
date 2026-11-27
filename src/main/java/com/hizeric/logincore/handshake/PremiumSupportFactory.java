package com.hizeric.logincore.handshake;

import com.hizeric.logincore.mojang.MojangClient;
import com.hizeric.logincore.session.VerifiedSessions;
import com.hizeric.logincore.storage.Storage;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.util.concurrent.ExecutorService;

/**
 * Chooses the premium support implementation without referencing PacketEvents types, so a
 * server without the plugin never loads the PE-backed classes.
 */
public final class PremiumSupportFactory {

    private PremiumSupportFactory() {
    }

    public static PremiumSupport create(JavaPlugin plugin, Storage storage,
                                        VerifiedSessions verifiedSessions, MojangClient mojang,
                                        ExecutorService workers, LoginMetrics loginMetrics) {
        try {
            if (Bukkit.getPluginManager().getPlugin("packetevents") == null) {
                return new NoOpPremiumSupport();
            }
            Method create = Class.forName("com.hizeric.logincore.handshake.PremiumHandshakeSupport")
                .getMethod("create", JavaPlugin.class, Storage.class, VerifiedSessions.class,
                    MojangClient.class, ExecutorService.class, LoginMetrics.class);
            Object result = create.invoke(null, plugin, storage, verifiedSessions, mojang, workers, loginMetrics);
            if (result instanceof PremiumSupport support) {
                return support;
            }
            warn(plugin, "PacketEvents support could not be initialized: unexpected create result");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
            warn(plugin, "PacketEvents support could not be initialized: " + ex);
        }
        return new NoOpPremiumSupport();
    }

    private static void warn(JavaPlugin plugin, String message) {
        if (plugin != null) {
            plugin.getLogger().warning(message);
        }
    }
}
