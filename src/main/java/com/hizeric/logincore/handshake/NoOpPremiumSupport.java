package com.hizeric.logincore.handshake;

/** Safe fallback when PacketEvents is not installed or cannot be initialized. */
public final class NoOpPremiumSupport implements PremiumSupport {

    private static final VerifiedLoginRelease NOOP_RELEASE = new VerifiedLoginRelease() {
        @Override
        public void releaseAfterAuth(String connectionId) {
        }

        @Override
        public void shutdown() {
        }
    };

    @Override
    public boolean available() {
        return false;
    }

    @Override
    public void registerBukkitEvents() {
    }

    @Override
    public VerifiedLoginRelease release() {
        return NOOP_RELEASE;
    }

    @Override
    public void shutdown() {
    }
}
