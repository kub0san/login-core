package com.hizeric.logincore.handshake;

import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.util.crypto.SignatureData;
import com.hizeric.logincore.TestPlugin;
import com.hizeric.logincore.mojang.MojangClient;
import com.hizeric.logincore.session.VerifiedSessions;
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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A spoofed UUID hint cannot claim a premium name when the session server says NOT_VERIFIED. */
class PremiumSessionVerificationTest {

    private static final String IP = "127.0.0.1";
    private static final String NAME = "PremiumGuy";
    private static final UUID PREMIUM_UUID = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38a0e1");

    @TempDir
    Path temporaryDirectory;

    @Test
    void notVerifiedSessionRejectsSpoofedPremiumIdentity() throws Exception {
        try (Storage storage = new Storage(temporaryDirectory.resolve("premium-session.db"))) {
            VerifiedSessions sessions = new VerifiedSessions();
            LoginMetrics metrics = new LoginMetrics();
            PremiumLoginListener listener = new PremiumLoginListener(TestPlugin.create(), storage,
                sessions, null, null, null, metrics);
            EmbeddedChannel channel = new EmbeddedChannel();
            String connectionId = "channel-1:123";
            String channelId = "channel-1";

            Object attempt = newAttempt(connectionId, channelId, channel, PREMIUM_UUID);
            setField(attempt, "phase", LoginFlowState.SESSION_CHECK_PENDING);
            setField(attempt, "expectedUuid", PREMIUM_UUID);
            attempts(listener).put(channelId, attempt);

            // UUID and canonical name match; NOT_VERIFIED is the sole rejection reason.
            MojangClient.SessionResult result = new MojangClient.SessionResult(
                MojangClient.SessionStatus.NOT_VERIFIED, PREMIUM_UUID, NAME, "not-verified");
            invokeCompleteSessionVerification(listener, attempt, result);

            assertEquals(0, sessions.size(), "an unverified session must never be stored");
            assertTrue(sessions.find(NAME, new InetSocketAddress(IP, 25565)).isEmpty());
            assertTrue(attempts(listener).isEmpty(), "the rejected attempt must be released");
            assertEquals(LoginFlowState.DISCONNECTED, field(attempt, "phase"));
            assertEquals(1, metrics.snapshot().failed(), "NOT_VERIFIED counts as a verification failure");
        }
    }

    private static Object newAttempt(String connectionId, String channelId, Channel channel, UUID uuidHint)
        throws Exception {
        Class<?> attemptType = Class.forName("com.hizeric.logincore.handshake.PremiumLoginListener$Attempt");
        Constructor<?> constructor = attemptType.getDeclaredConstructor(String.class, String.class, String.class,
            String.class, UUID.class, SignatureData.class, ClientVersion.class, User.class, Channel.class);
        constructor.setAccessible(true);
        // disconnect() catches the NPE from the null user; creating a real User needs a live PacketEvents API.
        return constructor.newInstance(connectionId, channelId, NAME, IP, uuidHint, null,
            ClientVersion.UNKNOWN, null, channel);
    }

    private static void invokeCompleteSessionVerification(PremiumLoginListener listener, Object attempt,
                                                          MojangClient.SessionResult result) throws Exception {
        Class<?> attemptType = Class.forName("com.hizeric.logincore.handshake.PremiumLoginListener$Attempt");
        Method method = PremiumLoginListener.class.getDeclaredMethod("completeSessionVerification",
            attemptType, MojangClient.SessionResult.class);
        method.setAccessible(true);
        method.invoke(listener, attempt, result);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> attempts(PremiumLoginListener listener) throws Exception {
        Field field = PremiumLoginListener.class.getDeclaredField("attempts");
        field.setAccessible(true);
        return (Map<String, Object>) field.get(listener);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
}
