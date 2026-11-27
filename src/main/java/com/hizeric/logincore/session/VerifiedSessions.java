package com.hizeric.logincore.session;

import com.hizeric.logincore.mojang.MojangClient;
import com.hizeric.logincore.storage.Storage;

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

    public boolean put(VerifiedSession session) {
        String sessionKey = key(session.name(), session.remoteIp());
        String existing = connectionByNameAndIp.get(sessionKey);
        if (existing != null) {
            Entry live = byConnection.get(existing);
            if (live != null && !live.expiresAt().isBefore(Instant.now())) {
                return false;
            }
        }
        Entry entry = new Entry(session, Instant.now().plus(TTL));
        byConnection.put(session.connectionId(), entry);
        connectionByNameAndIp.put(sessionKey, session.connectionId());
        return true;
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

    public boolean hasLiveAlias(String name, String remoteIp) {
        String connectionId = connectionByNameAndIp.get(key(name, remoteIp));
        if (connectionId == null) {
            return false;
        }
        Entry entry = byConnection.get(connectionId);
        return entry != null && !entry.expiresAt().isBefore(Instant.now());
    }

    public Optional<VerifiedSession> consume(String name, InetSocketAddress remoteAddress) {
        if (remoteAddress == null) {
            return Optional.empty();
        }
        InetAddress inet = remoteAddress.getAddress();
        return consume(name, inet == null ? remoteAddress.getHostString() : inet.getHostAddress());
    }

    public Optional<VerifiedSession> consume(String name, InetAddress remoteAddress) {
        if (remoteAddress == null) {
            return Optional.empty();
        }
        return consume(name, remoteAddress.getHostAddress());
    }

    private Optional<VerifiedSession> consume(String name, String remoteIp) {
        Optional<VerifiedSession> session = find(name, remoteIp);
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

    public int pendingCount() {
        return size();
    }

    private void purgeExpired() {
        Instant now = Instant.now();
        byConnection.forEach((connectionId, entry) -> {
            if (entry.expiresAt().isBefore(now)) {
                remove(connectionId);
            }
        });
    }

    // shortcut: name@ip cannot distinguish two simultaneous connections from the same IP; fail closed instead. Upgrade to a channel-keyed handoff if Bukkit ever exposes one.
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
