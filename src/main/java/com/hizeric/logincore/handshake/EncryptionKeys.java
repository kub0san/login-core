package com.hizeric.logincore.handshake;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Objects;

public final class EncryptionKeys {

    private final KeyPair keyPair;
    private final SecureRandom secureRandom = new SecureRandom();

    public EncryptionKeys() throws GeneralSecurityException {
        KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
        keyPairGenerator.initialize(2048);
        this.keyPair = keyPairGenerator.generateKeyPair();
    }

    public KeyPair keyPair() {
        return keyPair;
    }

    public byte[] generateToken() {
        byte[] token = new byte[16];
        secureRandom.nextBytes(token);
        return token;
    }

    public byte[] decrypt(byte[] encryptedData) {
        Objects.requireNonNull(encryptedData, "encryptedData");
        try {
            Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            cipher.init(Cipher.DECRYPT_MODE, keyPair.getPrivate());
            return cipher.doFinal(encryptedData);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Invalid encrypted login response", ex);
        }
    }

    public SecretKeySpec secretKeySpec(byte[] sharedSecret) {
        Objects.requireNonNull(sharedSecret, "sharedSecret");
        if (sharedSecret.length != 16) {
            throw new IllegalArgumentException("AES shared secret must be exactly 16 bytes");
        }
        return new SecretKeySpec(sharedSecret.clone(), "AES");
    }
}
