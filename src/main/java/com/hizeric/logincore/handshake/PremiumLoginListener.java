package com.hizeric.logincore.handshake;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.protocol.ConnectionState;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.util.crypto.SignatureData;
import com.github.retrooper.packetevents.wrapper.login.client.WrapperLoginClientEncryptionResponse;
import com.github.retrooper.packetevents.wrapper.login.client.WrapperLoginClientLoginStart;
import com.github.retrooper.packetevents.wrapper.login.server.WrapperLoginServerDisconnect;
import com.github.retrooper.packetevents.wrapper.login.server.WrapperLoginServerEncryptionRequest;
import io.netty.channel.Channel;
import net.kyori.adventure.text.minimessage.MiniMessage;
import com.hizeric.logincore.authme.AuthMeBridge;
import com.hizeric.logincore.decision.LoginDecisionEngine;
import com.hizeric.logincore.mojang.LookupResult;
import com.hizeric.logincore.mojang.MojangClient;
import com.hizeric.logincore.session.VerifiedSessions;
import com.hizeric.logincore.session.VerifiedSessions.VerifiedSession;
import com.hizeric.logincore.storage.Storage;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import javax.crypto.spec.SecretKeySpec;
import java.net.InetSocketAddress;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

public final class PremiumLoginListener extends PacketListenerAbstract implements Listener {

    private static final Pattern VALID_NAME = Pattern.compile("^[A-Za-z0-9_]{3,16}$");
    private final JavaPlugin plugin;
    private final AuthMeBridge authMe;
    private final Storage storage;
    private final VerifiedSessions verifiedSessions;
    private final MojangClient mojang;
    private final ExecutorService workers;
    private final EncryptionKeys encryptionKeys;
    private final LoginMetrics metrics;
    private final Map<String, Attempt> attempts = new ConcurrentHashMap<>();
    private final Set<String> activeNames = ConcurrentHashMap.newKeySet();
    private final Map<String, AtomicInteger> pendingByIp = new ConcurrentHashMap<>();
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    public PremiumLoginListener(JavaPlugin plugin, AuthMeBridge authMe, Storage storage,
                                VerifiedSessions verifiedSessions, MojangClient mojang,
                                ExecutorService workers, EncryptionKeys encryptionKeys, LoginMetrics metrics) {
        this.plugin = plugin;
        this.authMe = authMe;
        this.storage = storage;
        this.verifiedSessions = verifiedSessions;
        this.mojang = mojang;
        this.workers = workers;
        this.encryptionKeys = encryptionKeys;
        this.metrics = metrics;
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getConnectionState() != ConnectionState.LOGIN) {
            return;
        }
        if (event.getPacketType() == PacketType.Login.Client.LOGIN_START) {
            handleLoginStart(event);
        } else if (event.getPacketType() == PacketType.Login.Client.ENCRYPTION_RESPONSE) {
            handleEncryptionResponse(event);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        verifiedSessions.find(player.getName(), player.getAddress())
            .ifPresent(session -> releasePendingCapacity(session.connectionId()));
    }

    private void handleLoginStart(PacketReceiveEvent event) {
        WrapperLoginClientLoginStart packet = new WrapperLoginClientLoginStart(event);
        String name = packet.getUsername();
        if (name == null || !VALID_NAME.matcher(name).matches()) {
            return;
        }
        Channel channel = channel(event.getChannel());
        if (channel == null || !channel.isActive()) {
            return;
        }
        User user = event.getUser();
        InetSocketAddress address = user.getAddress();
        String remoteIp = address.getAddress() == null ? address.getHostString() : address.getAddress().getHostAddress();
        String connectionId = channel.id().asLongText() + ":" + System.nanoTime();
        Attempt attempt = new Attempt(connectionId, channel.id().asLongText(), name, remoteIp,
            packet.getPlayerUUID().orElse(null), packet.getSignatureData().orElse(null),
            user.getClientVersion(), user, channel);
        event.setCancelled(true);
        attempts.put(attempt.channelId, attempt);
        channel.closeFuture().addListener(ignored -> release(connectionId, true));

        int timeoutSeconds = Math.max(5, getConfigInt("handshake-timeout-seconds", 10));
        attempt.timeout = channel.eventLoop().schedule(
            () -> disconnect(attempt, "lookup-unavailable"), timeoutSeconds, TimeUnit.SECONDS);
        try {
            CompletableFuture<Boolean> registered = CompletableFuture.supplyAsync(
                () -> authMe.isRegistered(name), workers);
            storage.account(name)
                .thenCombineAsync(registered, (account, isRegistered) -> new AccountContext(account, isRegistered), workers)
                .thenApplyAsync(context -> {
                    context.lookupResult = mojang.lookupName(name, remoteIp);
                    return context;
                }, workers)
                .whenComplete((context, error) -> runOnChannel(attempt, () -> {
                    if (error != null) {
                        handleLookupFailure(attempt, error);
                        return;
                    }
                    decide(attempt, context);
                }));
        } catch (RejectedExecutionException ex) {
            plugin.getLogger().warning("Login worker queue is full; rejecting connection for " + name);
            disconnect(attempt, "throttled");
        }
    }

    private void decide(Attempt attempt, AccountContext context) {
        if (!isCurrent(attempt)) {
            return;
        }
        if (context.account().exempt()) {
            reinjectOriginal(attempt);
            return;
        }

        Optional<Storage.Link> link = context.account().link();
        UUID linkedUuid = link.map(value -> parseUuid(value.mojangUuid(), attempt)).orElse(null);
        if (link.isPresent() && linkedUuid == null) {
            disconnect(attempt, "account-conflict");
            return;
        }
        LoginDecisionEngine.DecisionInput input = new LoginDecisionEngine.DecisionInput(
            attempt.name,
            false,
            true,
            context.lookupResult,
            link.isPresent(),
            linkedUuid,
            attempt.uuidHint,
            false,
            isShadowMode(),
            getConfigBoolean("on-lookup-failure", "DENY").equalsIgnoreCase("ALLOW")
        );
        LoginDecisionEngine.Decision decision = LoginDecisionEngine.decide(input);

        if (isShadowMode()) {
            plugin.getLogger().info("SHADOW login decision for " + attempt.name + ": "
                + decision + " (" + context.lookupResult.type() + ")");
            reinjectOriginal(attempt);
            return;
        }
        switch (decision) {
            case PASS_THROUGH -> reinjectOriginal(attempt);
            case DISCONNECT_NAME_RESERVED -> disconnect(attempt, "name-reserved");
            case KICK_ACCOUNT_CONFLICT -> disconnect(attempt, "account-conflict");
            case KICK_VERIFY_FAILED -> disconnect(attempt, "lookup-unavailable");
            case HANDSHAKE_REQUIRED -> beginHandshake(attempt, context, link);
        }
    }

    private void beginHandshake(Attempt attempt, AccountContext context, Optional<Storage.Link> link) {
        if (!activeNames.add(attempt.normalizedName)) {
            disconnect(attempt, "throttled");
            return;
        }
        attempt.nameReserved = true;
        AtomicInteger pending = pendingByIp.computeIfAbsent(attempt.remoteIp, ignored -> new AtomicInteger());
        if (pending.incrementAndGet() > getConfigInt("throttle.max-pending-handshakes-per-ip", 2)) {
            pendingByIp.computeIfPresent(attempt.remoteIp,
                (ignored, count) -> count.decrementAndGet() <= 0 ? null : count);
            disconnect(attempt, "throttled");
            return;
        }
        attempt.ipCounted = true;
        attempt.lookupResult = context.lookupResult;
        attempt.link = link;
        attempt.linkedUuid = link.map(value -> parseUuid(value.mojangUuid(), attempt)).orElse(null);
        attempt.registeredBeforeLogin = context.registered();
        attempt.expectedUuid = context.lookupResult.isPremium()
            ? context.lookupResult.mojangUuid()
            : attempt.linkedUuid;
        if (link.isPresent() && attempt.linkedUuid == null) {
            disconnect(attempt, "account-conflict");
            return;
        }
        if (attempt.expectedUuid == null) {
            disconnect(attempt, "lookup-unavailable");
            return;
        }
        if (attempt.timeout != null) {
            attempt.timeout.cancel(false);
        }
        attempt.token = encryptionKeys.generateToken();
        attempt.phase = LoginFlowState.HANDSHAKE_PENDING;
        int timeoutSeconds = Math.max(1, getConfigInt("handshake-timeout-seconds", 10));
        attempt.timeout = attempt.channel.eventLoop().schedule(
            () -> disconnect(attempt, "verify-failed"), timeoutSeconds, TimeUnit.SECONDS);
        try {
            attempt.user.sendPacket(new WrapperLoginServerEncryptionRequest(
                "", encryptionKeys.keyPair().getPublic(), attempt.token, true));
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("Could not send encryption request for " + attempt.name + ": " + ex.getMessage());
            disconnect(attempt, "verify-failed");
        }
    }

    private void handleEncryptionResponse(PacketReceiveEvent event) {
        Channel channel = channel(event.getChannel());
        if (channel == null) {
            return;
        }
        Attempt attempt = attempts.get(channel.id().asLongText());
        if (attempt == null || attempt.phase != LoginFlowState.HANDSHAKE_PENDING) {
            return;
        }
        event.setCancelled(true);
        Runnable responseHandler = () -> verifyEncryptionResponse(attempt, event);
        if (channel.eventLoop().inEventLoop()) {
            responseHandler.run();
        } else {
            channel.eventLoop().execute(responseHandler);
        }
    }

    private void verifyEncryptionResponse(Attempt attempt, PacketReceiveEvent event) {
        if (!isCurrent(attempt)) {
            return;
        }
        WrapperLoginClientEncryptionResponse response = new WrapperLoginClientEncryptionResponse(event);
        byte[] encryptedToken = response.getEncryptedVerifyToken().orElse(null);
        if (encryptedToken == null) {
            disconnect(attempt, "verify-failed");
            return;
        }
        try {
            byte[] sharedSecret = encryptionKeys.decrypt(response.getEncryptedSharedSecret());
            byte[] verifyToken = encryptionKeys.decrypt(encryptedToken);
            if (sharedSecret.length != 16 || !MessageDigest.isEqual(attempt.token, verifyToken)) {
                disconnect(attempt, "verify-failed");
                return;
            }
            SecretKeySpec key = encryptionKeys.secretKeySpec(sharedSecret);
            CipherInstaller.install(attempt.channel, key);
            attempt.phase = LoginFlowState.SESSION_CHECK_PENDING;
            String serverHash = ServerHash.compute("", sharedSecret, encryptionKeys.keyPair().getPublic().getEncoded());
            mojang.hasJoinedAsync(attempt.name, serverHash, workers)
                .whenComplete((result, error) -> runOnChannel(attempt, () -> {
                    if (error != null) {
                        handleLookupFailure(attempt, error);
                        return;
                    }
                    completeSessionVerification(attempt, result);
                }));
        } catch (Exception ex) {
            plugin.getLogger().warning("Login encryption verification failed for " + attempt.name + ": " + ex.getMessage());
            disconnect(attempt, "verify-failed");
        }
    }

    private void completeSessionVerification(Attempt attempt, MojangClient.SessionResult result) {
        if (!isCurrent(attempt) || attempt.phase != LoginFlowState.SESSION_CHECK_PENDING) {
            return;
        }
        if (result.status() != MojangClient.SessionStatus.VERIFIED
            || !attempt.name.equalsIgnoreCase(result.canonicalName())
            || !attempt.expectedUuid.equals(result.uuid())) {
            disconnect(attempt, "verify-failed");
            return;
        }
        LoginDecisionEngine.PostVerifyAction action = LoginDecisionEngine.decidePostVerify(
            true,
            result.uuid(),
            attempt.registeredBeforeLogin,
            attempt.linkedUuid,
            LoginDecisionEngine.ExistingAccountPolicy.valueOf(
                getConfigBoolean("existing-account-policy", "REQUIRE_PASSWORD_ONCE").toUpperCase(Locale.ROOT))
        );
        if (action == LoginDecisionEngine.PostVerifyAction.ACCOUNT_CONFLICT) {
            disconnect(attempt, "account-conflict");
            return;
        }
        if (action == LoginDecisionEngine.PostVerifyAction.DENY) {
            disconnect(attempt, "account-conflict");
            return;
        }
        if (action == LoginDecisionEngine.PostVerifyAction.VERIFY_FAILED) {
            disconnect(attempt, "verify-failed");
            return;
        }
        boolean shouldRegister = action == LoginDecisionEngine.PostVerifyAction.REGISTER_AND_AUTO_LOGIN;
        boolean requirePasswordOnce = action == LoginDecisionEngine.PostVerifyAction.REQUIRE_PASSWORD_ONCE;
        boolean autoLogin = !requirePasswordOnce;
        String source = shouldRegister || action == LoginDecisionEngine.PostVerifyAction.LINK_AND_AUTO_LOGIN
            ? "AUTO" : "PASSWORD_ONCE";
        if (!verifiedSessions.put(new VerifiedSession(attempt.connectionId, attempt.name, attempt.remoteIp, result,
            attempt.link, attempt.registeredBeforeLogin, shouldRegister, requirePasswordOnce, autoLogin, source))) {
            plugin.getLogger().warning("Refused a second verified session for " + attempt.name + " from "
                + attempt.remoteIp + "; a live session already exists.");
            disconnect(attempt, "throttled");
            return;
        }
        attempt.phase = LoginFlowState.REINJECTED;
        metrics.verified();
        if (attempt.timeout != null) {
            attempt.timeout.cancel(false);
        }
        attempt.expiry = attempt.channel.eventLoop().schedule(
            () -> release(attempt.connectionId, true), 60, TimeUnit.SECONDS);
        attempt.user.receivePacketSilently(new WrapperLoginClientLoginStart(
            attempt.clientVersion,
            result.canonicalName(),
            attempt.signatureData,
            attempt.uuidHint
        ));
        if (getConfigBoolean("debug", false)) {
            plugin.getLogger().info("Verified premium session for " + attempt.name + " (UUID " + result.uuid() + ")");
        }
    }

    private void reinjectOriginal(Attempt attempt) {
        if (!isCurrent(attempt)) {
            return;
        }
        attempt.phase = LoginFlowState.PASSTHROUGH;
        attempt.user.receivePacketSilently(new WrapperLoginClientLoginStart(
            attempt.clientVersion,
            attempt.name,
            attempt.signatureData,
            attempt.uuidHint
        ));
        removeAttempt(attempt, false);
    }

    private void handleLookupFailure(Attempt attempt, Throwable error) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null
            ? error.getCause() : error;
        plugin.getLogger().warning("Login processing failed for " + attempt.name + ": " + cause.getMessage());
        if (isShadowMode()) {
            reinjectOriginal(attempt);
            return;
        }
        disconnect(attempt, "verify-failed");
    }

    private void disconnect(Attempt attempt, String messageKey) {
        if (!attempt.channel.eventLoop().inEventLoop()) {
            attempt.channel.eventLoop().execute(() -> disconnect(attempt, messageKey));
            return;
        }
        if (attempt.phase == LoginFlowState.CLOSED || attempt.phase == LoginFlowState.DISCONNECTED) {
            return;
        }
        attempt.phase = LoginFlowState.DISCONNECTED;
        if ("throttled".equals(messageKey)) {
            metrics.throttled();
        } else {
            metrics.failed();
        }
        if (attempt.timeout != null) {
            attempt.timeout.cancel(false);
        }
        try {
            attempt.user.sendPacket(new WrapperLoginServerDisconnect(message(messageKey)));
            attempt.channel.eventLoop().schedule(() -> attempt.channel.close(), 250, TimeUnit.MILLISECONDS);
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("Could not send login disconnect for " + attempt.name + ": " + ex.getMessage());
            attempt.channel.close();
        }
        verifiedSessions.remove(attempt.connectionId);
        removeAttempt(attempt, false);
    }

    private void runOnChannel(Attempt attempt, Runnable runnable) {
        if (!attempt.channel.isActive()) {
            release(attempt.connectionId, true);
            return;
        }
        if (attempt.channel.eventLoop().inEventLoop()) {
            runnable.run();
        } else {
            attempt.channel.eventLoop().execute(runnable);
        }
    }

    private boolean isCurrent(Attempt attempt) {
        return attempt.channel.isActive() && attempts.get(attempt.channelId) == attempt;
    }

    private void release(String connectionId, boolean removeVerified) {
        Attempt attempt = findAttempt(connectionId);
        if (attempt == null) {
            if (removeVerified) {
                verifiedSessions.remove(connectionId);
            }
            return;
        }
        attempt.phase = LoginFlowState.CLOSED;
        if (attempt.timeout != null) {
            attempt.timeout.cancel(false);
        }
        if (removeVerified) {
            verifiedSessions.remove(connectionId);
        }
        removeAttempt(attempt, removeVerified);
    }

    private void releasePendingCapacity(String connectionId) {
        Attempt attempt = findAttempt(connectionId);
        if (attempt == null || !attempt.ipCounted) {
            return;
        }
        pendingByIp.computeIfPresent(attempt.remoteIp,
            (ignored, count) -> count.decrementAndGet() <= 0 ? null : count);
        attempt.ipCounted = false;
        if (attempt.timeout != null) {
            attempt.timeout.cancel(false);
            attempt.timeout = null;
        }
    }

    private Attempt findAttempt(String connectionId) {
        for (Attempt attempt : attempts.values()) {
            if (attempt.connectionId.equals(connectionId)) {
                return attempt;
            }
        }
        return null;
    }

    private void removeAttempt(Attempt attempt, boolean removeVerified) {
        attempts.remove(attempt.channelId, attempt);
        if (attempt.timeout != null) {
            attempt.timeout.cancel(false);
        }
        if (attempt.expiry != null) {
            attempt.expiry.cancel(false);
        }
        if (attempt.nameReserved) {
            activeNames.remove(attempt.normalizedName);
            attempt.nameReserved = false;
        }
        if (attempt.ipCounted) {
            pendingByIp.computeIfPresent(attempt.remoteIp, (ignored, count) -> count.decrementAndGet() <= 0 ? null : count);
            attempt.ipCounted = false;
        }
        if (removeVerified) {
            verifiedSessions.remove(attempt.connectionId);
        }
    }

    public void releaseAfterAuth(String connectionId) {
        Attempt attempt = findAttempt(connectionId);
        if (attempt != null) {
            attempt.phase = LoginFlowState.CLOSED;
            removeAttempt(attempt, false);
        }
    }

    public void shutdown() {
        for (Attempt attempt : attempts.values()) {
            Runnable cleanup = () -> {
                attempt.phase = LoginFlowState.CLOSED;
                verifiedSessions.remove(attempt.connectionId);
                removeAttempt(attempt, true);
                attempt.channel.close();
            };
            try {
                if (attempt.channel.eventLoop().inEventLoop()) {
                    cleanup.run();
                } else {
                    attempt.channel.eventLoop().execute(cleanup);
                }
            } catch (RejectedExecutionException ex) {
                plugin.getLogger().warning("Could not schedule connection cleanup for "
                    + attempt.name + ": " + ex.getMessage());
                cleanup.run();
            }
        }
    }

    private UUID parseUuid(String value, Attempt attempt) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ex) {
            plugin.getLogger().warning("Ignoring invalid stored premium UUID for " + attempt.name);
            return null;
        }
    }

    private net.kyori.adventure.text.Component message(String key) {
        String text = plugin.getConfig().getString("messages." + key);
        if (text == null || text.isBlank()) {
            text = "<red>Login verification failed.";
        }
        return miniMessage.deserialize(text);
    }

    private boolean isShadowMode() {
        return getConfigBoolean("mode", "ENFORCE").equalsIgnoreCase("SHADOW");
    }

    private String getConfigBoolean(String key, String fallback) {
        return plugin.getConfig().getString(key, fallback);
    }

    private boolean getConfigBoolean(String key, boolean fallback) {
        return plugin.getConfig().getBoolean(key, fallback);
    }

    private int getConfigInt(String key, int fallback) {
        return plugin.getConfig().getInt(key, fallback);
    }

    private Channel channel(Object channel) {
        return channel instanceof Channel nettyChannel ? nettyChannel : null;
    }

    private static final class AccountContext {
        private final Storage.AccountState account;
        private final boolean registered;
        private LookupResult lookupResult;

        private AccountContext(Storage.AccountState account, boolean registered) {
            this.account = account;
            this.registered = registered;
        }

        private Storage.AccountState account() {
            return account;
        }

        private boolean registered() {
            return registered;
        }
    }

    private static final class Attempt {
        private final String connectionId;
        private final String channelId;
        private final String name;
        private final String normalizedName;
        private final String remoteIp;
        private final UUID uuidHint;
        private final SignatureData signatureData;
        private final ClientVersion clientVersion;
        private final User user;
        private final Channel channel;
        private volatile LoginFlowState phase = LoginFlowState.LOOKUP_PENDING;
        private volatile boolean nameReserved;
        private volatile boolean ipCounted;
        private volatile byte[] token;
        private volatile UUID expectedUuid;
        private volatile LookupResult lookupResult;
        private volatile Optional<Storage.Link> link = Optional.empty();
        private volatile UUID linkedUuid;
        private volatile boolean registeredBeforeLogin;
        private volatile ScheduledFuture<?> timeout;
        private volatile ScheduledFuture<?> expiry;

        private Attempt(String connectionId, String channelId, String name, String remoteIp, UUID uuidHint,
                        SignatureData signatureData, ClientVersion clientVersion, User user, Channel channel) {
            this.connectionId = connectionId;
            this.channelId = channelId;
            this.name = name;
            this.normalizedName = name.toLowerCase(Locale.ROOT);
            this.remoteIp = remoteIp;
            this.uuidHint = uuidHint;
            this.signatureData = signatureData;
            this.clientVersion = clientVersion;
            this.user = user;
            this.channel = channel;
        }
    }
}
