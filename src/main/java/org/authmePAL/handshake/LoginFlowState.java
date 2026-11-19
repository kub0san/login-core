package org.authmePAL.handshake;

public enum LoginFlowState {
    LOOKUP_PENDING,
    HANDSHAKE_PENDING,
    SESSION_CHECK_PENDING,
    REINJECTED,
    PASSTHROUGH,
    DISCONNECTED,
    CLOSED
}
