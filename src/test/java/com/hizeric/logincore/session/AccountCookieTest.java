package com.hizeric.logincore.session;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AccountCookieTest {

    @Test
    void encodeDecodeRoundTrip() {
        String token = "6f2c67e0-6a0e-4d3b-9b55-04c3f1a2d8e7";
        byte[] encoded = AccountCookie.encode(token);

        assertArrayEquals(token.getBytes(StandardCharsets.UTF_8), encoded);
        assertEquals(token, AccountCookie.decode(encoded));
    }

    @Test
    void encodeDecodeRoundTripMultibyte() {
        String token = "tökén-🪙";
        byte[] encoded = AccountCookie.encode(token);

        assertArrayEquals(token.getBytes(StandardCharsets.UTF_8), encoded);
        assertEquals(token, AccountCookie.decode(encoded));
    }

    @Test
    void decodeHandlesNullAndEmpty() {
        assertNull(AccountCookie.decode(null));
        assertNull(AccountCookie.decode(new byte[0]));
    }

    @Test
    void encodeHandlesNullAndEmpty() {
        assertNull(AccountCookie.encode(null));
        assertArrayEquals(new byte[0], AccountCookie.encode(""));
        assertNull(AccountCookie.decode(AccountCookie.encode("")));
    }
}