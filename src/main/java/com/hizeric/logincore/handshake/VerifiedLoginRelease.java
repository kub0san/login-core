package com.hizeric.logincore.handshake;

/** PacketEvents-free hook used after a verified login is granted. */
public interface VerifiedLoginRelease {

    void releaseAfterAuth(String connectionId);

    void shutdown();
}
