package org.authmePAL.session;

import org.authmePAL.mojang.MojangClient;
import org.authmePAL.storage.Storage;

import java.net.InetSocketAddress;
import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class VerifiedSessions {

    private static final Duration TTL = Duration.ofSeconds(60);
    private final Map<String, Entry> byConnection = new ConcurrentHashMap<>();
    private final Map<String, String> connectionByNameAndIp = new ConcurrentHashMap<>();

    public void put(VerifiedSession session) {
        Entry entry = new Entry(session, Instant.now().plus(TTL));
        byConnection.put(session.connectionId(), entry);
        connectionByNameAndIp.put(key(session.name(), session.remoteIp()), session.connectionId());
    }

    public Optional<VerifiedSession> find(String name, InetSocketAddress remoteAddress) {
        if (remoteAddress == null) {
            return Optional.empty();
        }
        String remoteIp = remoteAddress.getAddress() == null
            ? remoteAddress.getHostString() : remoteAddress.getAddress().getHostAddress();
        return find(name, remoteIp);
    }

    public Optional<VerifiedSession> find(String name, InetAddress remoteAddress) {
        if (remoteAddress == null) {
            return Optional.empty();
        }
        return find(name, remoteAddress.getHostAddress());
    }

    private Optional<VerifiedSession> find(String name, String remoteIp) {
        String sessionKey = key(name, remoteIp);
        String connectionId = connectionByNameAndIp.get(sessionKey);
        if (connectionId == null) {
            return Optional.empty();
        }
        Entry entry = byConnection.get(connectionId);
        if (entry == null) {
            connectionByNameAndIp.remove(sessionKey, connectionId);
            return Optional.empty();
        }
        if (entry.expiresAt().isBefore(Instant.now())) {
            remove(connectionId);
            return Optional.empty();
        }
        return Optional.of(entry.session());
    }

    public Optional<VerifiedSession> consume(String name, InetSocketAddress remoteAddress) {
        Optional<VerifiedSession> session = find(name, remoteAddress);
        session.ifPresent(verifiedSession -> remove(verifiedSession.connectionId()));
        return session;
    }

    public void remove(String connectionId) {
        Entry removed = byConnection.remove(connectionId);
        if (removed != null) {
            connectionByNameAndIp.remove(key(removed.session().name(), removed.session().remoteIp()), connectionId);
        }
    }

    public int size() {
        purgeExpired();
        return byConnection.size();
    }

    private void purgeExpired() {
        Instant now = Instant.now();
        byConnection.forEach((connectionId, entry) -> {
            if (entry.expiresAt().isBefore(now)) {
                remove(connectionId);
            }
        });
    }

    private static String key(String name, String remoteIp) {
        return name.toLowerCase(Locale.ROOT) + "@" + remoteIp;
    }

    private record Entry(VerifiedSession session, Instant expiresAt) {
    }

    public record VerifiedSession(
        String connectionId,
        String name,
        String remoteIp,
        MojangClient.SessionResult mojangSession,
        Optional<Storage.Link> link,
        boolean registeredBeforeLogin,
        boolean shouldRegister,
        boolean requiresPasswordOnce,
        boolean autoLogin,
        String linkSource
    ) {
        public Optional<UUID> playerUuid(boolean usePremiumUuid) {
            if (!usePremiumUuid || mojangSession.status() != MojangClient.SessionStatus.VERIFIED) {
                return Optional.empty();
            }
            return Optional.ofNullable(mojangSession.uuid());
        }
    }
}
