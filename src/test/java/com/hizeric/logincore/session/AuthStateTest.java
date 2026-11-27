package com.hizeric.logincore.session;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthStateTest {

    @Test
    void authenticationIsCaseInsensitiveAndLogoutClearsIt() {
        AuthState state = new AuthState();
        assertFalse(state.isAuthenticated("NoTcH"));

        state.authenticate("NoTcH");
        assertTrue(state.isAuthenticated("notch"));

        state.logout("NOTCH");
        assertFalse(state.isAuthenticated("NoTcH"));
    }

    @Test
    void concurrentAuthenticationsAreAllVisible() throws Exception {
        AuthState state = new AuthState();
        ExecutorService workers = Executors.newFixedThreadPool(4);
        CountDownLatch done = new CountDownLatch(40);
        try {
            for (int i = 0; i < 40; i++) {
                String name = "Player" + i;
                workers.execute(() -> {
                    state.authenticate(name);
                    done.countDown();
                });
            }
            done.await();
            for (int i = 0; i < 40; i++) {
                assertTrue(state.isAuthenticated("player" + i));
            }
        } finally {
            workers.shutdownNow();
        }
    }
}
