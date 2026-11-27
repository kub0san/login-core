package com.hizeric.logincore.handshake;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import com.hizeric.logincore.mojang.MojangClient;
import com.hizeric.logincore.session.VerifiedSessions;
import com.hizeric.logincore.storage.Storage;
import org.bukkit.plugin.java.JavaPlugin;

import java.security.GeneralSecurityException;
import java.util.concurrent.ExecutorService;

/** The PacketEvents-backed implementation, loaded only when the plugin is present. */
public final class PremiumHandshakeSupport implements PremiumSupport {

    private final JavaPlugin plugin;
    private final PremiumLoginListener loginListener;
    private final VerifiedLoginRelease release;
    private PacketListenerCommon packetListener;

    private PremiumHandshakeSupport(JavaPlugin plugin, PremiumLoginListener loginListener) {
        this.plugin = plugin;
        this.loginListener = loginListener;
        this.release = new VerifiedLoginRelease() {
            @Override
            public void releaseAfterAuth(String connectionId) {
                loginListener.releaseAfterAuth(connectionId);
            }

            @Override
            public void shutdown() {
                loginListener.shutdown();
            }
        };
    }

    public static PremiumSupport create(JavaPlugin plugin, Storage storage,
                                        VerifiedSessions verifiedSessions, MojangClient mojang,
                                        ExecutorService workers, LoginMetrics loginMetrics) {
        if (plugin == null || PacketEvents.getAPI() == null || !PacketEvents.getAPI().isInitialized()) {
            return new NoOpPremiumSupport();
        }
        try {
            EncryptionKeys encryptionKeys = new EncryptionKeys();
            PremiumLoginListener listener = new PremiumLoginListener(
                plugin, storage, verifiedSessions, mojang, workers, encryptionKeys, loginMetrics);
            return new PremiumHandshakeSupport(plugin, listener);
        } catch (GeneralSecurityException | RuntimeException ex) {
            plugin.getLogger().warning("Could not initialize premium login support: " + ex.getMessage());
            return new NoOpPremiumSupport();
        }
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public void registerBukkitEvents() {
        packetListener = PacketEvents.getAPI().getEventManager().registerListener(loginListener);
        plugin.getServer().getPluginManager().registerEvents(loginListener, plugin);
    }

    @Override
    public VerifiedLoginRelease release() {
        return release;
    }

    @Override
    public void shutdown() {
        if (packetListener != null) {
            PacketEvents.getAPI().getEventManager().unregisterListener(packetListener);
            packetListener = null;
        }
        release.shutdown();
    }
}
