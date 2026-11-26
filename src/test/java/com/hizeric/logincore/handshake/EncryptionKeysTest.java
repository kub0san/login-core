package com.hizeric.logincore.handshake;

import org.junit.jupiter.api.Test;

import java.security.interfaces.RSAPublicKey;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EncryptionKeysTest {

    @Test
    void generatedRsaKeyIs2048Bits() throws Exception {
        EncryptionKeys keys = new EncryptionKeys();
        RSAPublicKey publicKey = (RSAPublicKey) keys.keyPair().getPublic();
        assertEquals(2048, publicKey.getModulus().bitLength());
    }
}
