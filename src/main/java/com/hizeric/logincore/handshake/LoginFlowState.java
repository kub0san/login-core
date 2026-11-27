package com.hizeric.logincore.handshake;

public enum LoginFlowState {
    LOOKUP_PENDING,
    HANDSHAKE_PENDING,
    SESSION_CHECK_PENDING,
    REINJECTED,
    PASSTHROUGH,
    DISCONNECTED,
    CLOSED
}
