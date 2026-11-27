package com.hizeric.logincore.authme;

/**
 * AuthMe password-hash compatibility for login-core.
 *
 * <p>Only BCrypt ({@code $2a$}, {@code $2b$}, {@code $2y$}) is verified. Argon2
 * and every other hash format are deliberately unsupported: they fail closed so
 * an unknown hash is never mistaken for "no password". The vendored algorithm
 * and its provenance are in {@link BCrypt}.
 */
public final class AuthMePasswords {

    private AuthMePasswords() {
    }

    /** True only for a BCrypt hash login-core knows how to verify. */
    public static boolean isSupported(String hash) {
        return hash != null
            && (hash.startsWith("$2a$") || hash.startsWith("$2b$") || hash.startsWith("$2y$"));
    }

    /** True only when {@code plain} matches a supported BCrypt hash. */
    public static boolean verify(String plain, String hash) {
        if (plain == null || !isSupported(hash)) {
            return false;
        }
        try {
            return BCrypt.checkpw(plain, hash);
        } catch (RuntimeException ex) {
            // A truncated or malformed hash must fail closed, not throw into the login flow.
            return false;
        }
    }
}
