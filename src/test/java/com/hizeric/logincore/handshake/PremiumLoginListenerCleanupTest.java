package com.hizeric.logincore.handshake;

import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.util.crypto.SignatureData;
import com.hizeric.logincore.TestPlugin;
import com.hizeric.logincore.mojang.MojangClient;
import com.hizeric.logincore.session.VerifiedSessions;
import com.hizeric.logincore.session.VerifiedSessions.VerifiedSession;
import com.hizeric.logincore.storage.Storage;
import io.netty.channel.Channel;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Case 15: a disconnect (channel close -> release) drops the pending attempt, throttle and session. */
class PremiumLoginListenerCleanupTest {

    private static final String IP = "10.0.0.7";
    private static final String NAME = "PendingGuy";

    @TempDir
    Path temporaryDirectory;

    @Test
    void releaseRemovesPendingAttemptAndThrottle() throws Exception {
        try (Storage storage = new Storage(temporaryDirectory.resolve("cleanup.db"))) {
            VerifiedSessions sessions = new VerifiedSessions();
            PremiumLoginListener listener = new PremiumLoginListener(TestPlugin.create(), null, storage,
                sessions, null, null, null, new LoginMetrics());
            EmbeddedChannel channel = new EmbeddedChannel();
            String connectionId = "channel-1:123";
            String channelId = "channel-1";

            Object attempt = newAttempt(connectionId, channelId, channel);
            setField(attempt, "nameReserved", true);
            setField(attempt, "ipCounted", true);
            attempts(listener).put(channelId, attempt);
            activeNames(listener).add(NAME.toLowerCase(java.util.Locale.ROOT));
            throttle(listener).reserve(IP, NAME.toLowerCase(java.util.Locale.ROOT), 2);
            sessions.put(session(connectionId));

            assertTrue(sessions.find(NAME, address()).isPresent());
            assertTrue(sessions.hasLiveAlias(NAME, IP));
            assertEquals(1, throttle(listener).count(IP));

            invokeRelease(listener, connectionId, true);

            assertTrue(attempts(listener).isEmpty(), "the pending attempt must be removed");
            assertEquals(0, throttle(listener).count(IP), "the per-IP name must be released");
            assertEquals(0, throttle(listener).size(), "an empty IP entry must be dropped");
            assertFalse(activeNames(listener).contains(NAME.toLowerCase(java.util.Locale.ROOT)));
            assertTrue(sessions.find(NAME, address()).isEmpty(), "find must return empty after disconnect");
            assertFalse(sessions.hasLiveAlias(NAME, IP), "hasLiveAlias must return false after disconnect");
            assertEquals(0, sessions.size());
        }
    }

    @Test
    void releaseWithoutAttemptStillClearsVerifiedSession() throws Exception {
        try (Storage storage = new Storage(temporaryDirectory.resolve("cleanup-no-attempt.db"))) {
            VerifiedSessions sessions = new VerifiedSessions();
            PremiumLoginListener listener = new PremiumLoginListener(TestPlugin.create(), null, storage,
                sessions, null, null, null, new LoginMetrics());
            String connectionId = "channel-2:456";
            sessions.put(session(connectionId));

            assertTrue(sessions.hasLiveAlias(NAME, IP));
            invokeRelease(listener, connectionId, true);

            assertTrue(sessions.find(NAME, address()).isEmpty());
            assertFalse(sessions.hasLiveAlias(NAME, IP));
            assertEquals(0, sessions.size());
        }
    }

    private static VerifiedSession session(String connectionId) {
        return new VerifiedSession(connectionId, NAME, IP,
            MojangClient.SessionResult.verified(UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38a0e1"), NAME),
            Optional.empty(), false, false, false, true, "AUTO");
    }

    private static InetSocketAddress address() {
        return new InetSocketAddress(IP, 25565);
    }

    private static Object newAttempt(String connectionId, String channelId, Channel channel) throws Exception {
        Class<?> attemptType = Class.forName("com.hizeric.logincore.handshake.PremiumLoginListener$Attempt");
        Constructor<?> constructor = attemptType.getDeclaredConstructor(String.class, String.class, String.class,
            String.class, UUID.class, SignatureData.class, ClientVersion.class, User.class, Channel.class);
        constructor.setAccessible(true);
        // release() never touches the user; constructing a real User needs a live PacketEvents API.
        return constructor.newInstance(connectionId, channelId, NAME, IP, null, null,
            ClientVersion.UNKNOWN, null, channel);
    }

    private static void invokeRelease(PremiumLoginListener listener, String connectionId, boolean removeVerified)
        throws Exception {
        Method release = PremiumLoginListener.class.getDeclaredMethod("release", String.class, boolean.class);
        release.setAccessible(true);
        release.invoke(listener, connectionId, removeVerified);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> attempts(PremiumLoginListener listener) throws Exception {
        Field field = PremiumLoginListener.class.getDeclaredField("attempts");
        field.setAccessible(true);
        return (Map<String, Object>) field.get(listener);
    }

    @SuppressWarnings("unchecked")
    private static Set<String> activeNames(PremiumLoginListener listener) throws Exception {
        Field field = PremiumLoginListener.class.getDeclaredField("activeNames");
        field.setAccessible(true);
        return (Set<String>) field.get(listener);
    }

    private static PendingHandshakeThrottle throttle(PremiumLoginListener listener) throws Exception {
        Field field = PremiumLoginListener.class.getDeclaredField("pendingByIp");
        field.setAccessible(true);
        return (PendingHandshakeThrottle) field.get(listener);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
