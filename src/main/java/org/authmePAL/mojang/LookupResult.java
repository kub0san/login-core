package org.authmePAL.mojang;

import java.util.Objects;
import java.util.UUID;

public record LookupResult(Type type, UUID mojangUuid, String canonicalName, String reason) {

    public enum Type {
        PREMIUM,
        NOT_PREMIUM,
        UNKNOWN
    }

    public static LookupResult premium(UUID mojangUuid, String canonicalName) {
        return new LookupResult(Type.PREMIUM, Objects.requireNonNull(mojangUuid), canonicalName, null);
    }

    public static LookupResult notPremium() {
        return new LookupResult(Type.NOT_PREMIUM, null, null, "not-premium");
    }

    public static LookupResult unknown(String reason) {
        return new LookupResult(Type.UNKNOWN, null, null, reason);
    }

    public boolean isPremium() {
        return type == Type.PREMIUM;
    }

    public boolean isNotPremium() {
        return type == Type.NOT_PREMIUM;
    }

    public boolean isUnknown() {
        return type == Type.UNKNOWN;
    }
}
