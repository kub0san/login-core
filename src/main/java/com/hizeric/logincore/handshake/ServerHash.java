package com.hizeric.logincore.handshake;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

public final class ServerHash {

    private ServerHash() {
    }

    public static String compute(String username) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("username must not be blank");
        }
        return signedHex(sha1(username.getBytes(StandardCharsets.UTF_8)));
    }

    public static String compute(String serverId, byte[] sharedSecret, byte[] publicKey) {
        Objects.requireNonNull(serverId, "serverId");
        Objects.requireNonNull(sharedSecret, "sharedSecret");
        Objects.requireNonNull(publicKey, "publicKey");
        byte[] serverIdBytes = serverId.getBytes(StandardCharsets.ISO_8859_1);
        byte[] input = new byte[serverIdBytes.length + sharedSecret.length + publicKey.length];
        System.arraycopy(serverIdBytes, 0, input, 0, serverIdBytes.length);
        System.arraycopy(sharedSecret, 0, input, serverIdBytes.length, sharedSecret.length);
        System.arraycopy(publicKey, 0, input, serverIdBytes.length + sharedSecret.length, publicKey.length);
        return signedHex(sha1(input));
    }

    private static byte[] sha1(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-1").digest(value);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-1 is required by the protocol", ex);
        }
    }

    private static String signedHex(byte[] digest) {
        return new BigInteger(digest).toString(16);
    }
}
