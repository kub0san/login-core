package com.hizeric.logincore;

import com.hizeric.logincore.mojang.MojangClient;
import com.hizeric.logincore.session.VerifiedSessions.VerifiedSession;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerifiedSessionTest {

    private static final UUID PREMIUM_UUID = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38a0e1");

    @Test
    void premiumUuidIsUsedOnlyWhenEnabledForVerifiedIdentity() {
        VerifiedSession verified = session(MojangClient.SessionResult.verified(PREMIUM_UUID, "Notch"));

        assertEquals(Optional.of(PREMIUM_UUID), verified.playerUuid(true));
        assertTrue(verified.playerUuid(false).isEmpty());
    }

    @Test
    void unverifiedSessionsNeverProvideAnOverrideUuid() {
        VerifiedSession unverified = session(MojangClient.SessionResult.notVerified());

        assertTrue(unverified.playerUuid(true).isEmpty());
        assertTrue(unverified.playerUuid(false).isEmpty());
    }

    private VerifiedSession session(MojangClient.SessionResult result) {
        return new VerifiedSession(
            "connection",
            "Notch",
            "127.0.0.1",
            result,
            Optional.empty(),
            false,
            false,
            false,
            false,
            "AUTO"
        );
    }
}
