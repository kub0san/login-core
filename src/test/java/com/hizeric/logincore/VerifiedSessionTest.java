package com.hizeric.logincore;

import com.hizeric.logincore.mojang.MojangClient;
import com.hizeric.logincore.session.VerifiedSessions;
import com.hizeric.logincore.session.VerifiedSessions.VerifiedSession;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.net.InetAddress;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerifiedSessionTest {

    private static final UUID PREMIUM_UUID = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38a0e1");
    private static final InetAddress LOOPBACK = InetAddress.getLoopbackAddress();

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

    @Test
    void secondConnectionSameNameAndIpCannotReplaceLiveSession() {
        VerifiedSessions sessions = new VerifiedSessions();
        VerifiedSession first = session("connection-a", verified());
        VerifiedSession second = session("connection-b", verified());

        assertTrue(sessions.put(first));
        assertFalse(sessions.put(second));
        assertEquals("connection-a", sessions.find("Notch", LOOPBACK).orElseThrow().connectionId());
        assertTrue(sessions.hasLiveAlias("Notch", first.remoteIp()));
    }

    @Test
    void expiredAliasCanBeReplaced() throws Exception {
        VerifiedSessions sessions = new VerifiedSessions();
        VerifiedSession first = session("connection-a", verified());
        VerifiedSession second = session("connection-b", verified());

        assertTrue(sessions.put(first));
        expire(sessions, "connection-a");

        assertTrue(sessions.put(second));
        assertEquals("connection-b", sessions.find("Notch", LOOPBACK).orElseThrow().connectionId());
    }

    @Test
    void consumeIsOneShotAndClearsBothIndexes() {
        VerifiedSessions sessions = new VerifiedSessions();
        VerifiedSession first = session("connection-a", verified());

        assertTrue(sessions.put(first));
        assertEquals(Optional.of(first), sessions.consume("Notch", LOOPBACK));
        assertTrue(sessions.consume("Notch", LOOPBACK).isEmpty());
        assertFalse(sessions.hasLiveAlias("Notch", first.remoteIp()));
        assertEquals(0, sessions.pendingCount());
    }

    @Test
    void consumeAfterRemovalReturnsEmpty() {
        VerifiedSessions sessions = new VerifiedSessions();
        VerifiedSession first = session("connection-a", verified());

        assertTrue(sessions.put(first));
        sessions.remove("connection-a");

        assertTrue(sessions.consume("Notch", LOOPBACK).isEmpty());
    }

    private MojangClient.SessionResult verified() {
        return MojangClient.SessionResult.verified(PREMIUM_UUID, "Notch");
    }

    private VerifiedSession session(MojangClient.SessionResult result) {
        return session("connection", result);
    }

    private VerifiedSession session(String connectionId, MojangClient.SessionResult result) {
        return new VerifiedSession(
            connectionId,
            "Notch",
            LOOPBACK.getHostAddress(),
            result,
            Optional.empty(),
            false,
            false,
            false,
            false,
            "AUTO"
        );
    }

    private void expire(VerifiedSessions sessions, String connectionId) throws Exception {
        Field byConnection = VerifiedSessions.class.getDeclaredField("byConnection");
        byConnection.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> entries = (Map<String, Object>) byConnection.get(sessions);
        Object entry = entries.get(connectionId);
        assertNotNull(entry);
        Field session = entry.getClass().getDeclaredField("session");
        session.setAccessible(true);
        var constructor = entry.getClass().getDeclaredConstructor(VerifiedSession.class, Instant.class);
        constructor.setAccessible(true);
        entries.put(connectionId, constructor.newInstance(session.get(entry), Instant.now().minusSeconds(1)));
    }
}
