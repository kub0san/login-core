package com.hizeric.logincore.command;

import java.nio.charset.StandardCharsets;

final class PasswordPolicy {

    private static final int MIN_BYTES = 6;
    private static final int MAX_BYTES = 72; // BCrypt truncates beyond 72 bytes.

    private PasswordPolicy() {
    }

    static boolean valid(String password) {
        if (password == null) {
            return false;
        }
        int length = password.getBytes(StandardCharsets.UTF_8).length;
        return length >= MIN_BYTES && length <= MAX_BYTES;
    }
}
