package com.hizeric.logincore.handshake;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PendingHandshakeThrottleTest {

    @Test
    void throttleCountsDistinctNamesPerIp() {
        PendingHandshakeThrottle throttle = new PendingHandshakeThrottle();

        assertTrue(throttle.reserve("10.0.0.1", "alpha", 2));
        assertTrue(throttle.reserve("10.0.0.1", "beta", 2));
        assertEquals(2, throttle.count("10.0.0.1"));
        assertFalse(throttle.reserve("10.0.0.1", "gamma", 2),
            "a third distinct name from one IP must be throttled");

        assertTrue(throttle.reserve("10.0.0.2", "gamma", 2), "other IPs keep their own budget");
        assertTrue(throttle.reserve("10.0.0.2", "gamma", 2), "the same name is not counted twice");
        assertTrue(throttle.reserve("10.0.0.2", "delta", 2));
        assertFalse(throttle.reserve("10.0.0.2", "epsilon", 2));
    }

    @Test
    void throttleReleasesNameOnDisconnect() {
        PendingHandshakeThrottle throttle = new PendingHandshakeThrottle();

        assertTrue(throttle.reserve("10.0.0.1", "alpha", 2));
        throttle.release("10.0.0.1", "alpha");

        assertEquals(0, throttle.count("10.0.0.1"));
        assertEquals(0, throttle.size(), "an empty IP entry must be dropped");
        assertTrue(throttle.reserve("10.0.0.1", "alpha", 2),
            "the released name is allowed to handshake again");
    }
}
