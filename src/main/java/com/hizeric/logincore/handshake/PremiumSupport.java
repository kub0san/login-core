package com.hizeric.logincore.handshake;

/** PacketEvents-free seam for premium login verification. */
public interface PremiumSupport {

    boolean available();

    void registerBukkitEvents();

    VerifiedLoginRelease release();

    void shutdown();
}
