package com.hizeric.logincore.session;

import com.hizeric.logincore.TestPlugin;
import com.hizeric.logincore.storage.Storage;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PreAuthRestrictionListenerTest {

    @TempDir
    Path temporaryDirectory;

    private Storage storage;
    private AuthState state;
    private PreAuthRestrictionListener listener;

    @BeforeEach
    void setUp() throws Exception {
        storage = new Storage(temporaryDirectory.resolve("restrictions.db"));
        state = new AuthState();
        listener = new PreAuthRestrictionListener(TestPlugin.create(), state, storage, Runnable::run);
    }

    @AfterEach
    void tearDown() {
        storage.close();
    }

    @Test
    void onlyExactAllowlistedCommandLabelsAreAllowed() {
        assertTrue(PreAuthRestrictionListener.isCommandAllowed("/login secret"));
        assertTrue(PreAuthRestrictionListener.isCommandAllowed("/LOGIN secret"));
        assertTrue(PreAuthRestrictionListener.isCommandAllowed("  /register pass pass"));
        assertTrue(PreAuthRestrictionListener.isCommandAllowed("/changepassword old new"));
        assertTrue(PreAuthRestrictionListener.isCommandAllowed("/logincore status"));

        assertFalse(PreAuthRestrictionListener.isCommandAllowed("/logout"));
        assertFalse(PreAuthRestrictionListener.isCommandAllowed("/loginanything pass"));
        assertFalse(PreAuthRestrictionListener.isCommandAllowed("login"));
        assertFalse(PreAuthRestrictionListener.isCommandAllowed(null));
    }

    @Test
    void unclassifiedAndOfflineNamesFailClosed() throws Exception {
        listener.onJoin(player("UnknownGuy"));
        assertTrue(listener.shouldRestrict("UnknownGuy"));

        storage.setClassification("OfflineGuy", "OFFLINE").get();
        listener.onJoin(player("OfflineGuy"));
        assertTrue(listener.shouldRestrict("OfflineGuy"));

        state.authenticate("offlineguy");
        assertFalse(listener.shouldRestrict("OfflineGuy"));
    }

    @Test
    void premiumClassificationBypassesRestrictions() throws Exception {
        storage.setClassification("PremiumGuy", "PREMIUM").get();

        listener.onJoin(player("PremiumGuy"));
        await(() -> state.isAuthenticated("premiumguy"));

        assertTrue(state.isAuthenticated("premiumguy"));
        assertFalse(listener.shouldRestrict("PREMIUMGUY"));
    }

    @Test
    void failedClassificationLookupLeavesPlayerRestricted() {
        storage.close();

        listener.onJoin(player("UnknownGuy"));

        assertFalse(state.isAuthenticated("unknownguy"));
        assertTrue(listener.shouldRestrict("UnknownGuy"));
    }

    @Test
    void timeoutStartsAtExactlySixtySeconds() {
        long joined = 1_000L;
        assertFalse(PreAuthRestrictionListener.isTimedOut(joined, joined + 59_999L, 60_000L));
        assertTrue(PreAuthRestrictionListener.isTimedOut(joined, joined + 60_000L, 60_000L));
    }

    @Test
    void quitClearsPendingLoginPremiumCacheAndAuthState() throws Exception {
        storage.setClassification("PremiumGuy", "PREMIUM").get();
        listener.onJoin(player("PremiumGuy"));
        assertTrue(listener.hasPendingLogin("PremiumGuy"));
        await(() -> state.isAuthenticated("premiumguy"));
        assertTrue(state.isAuthenticated("premiumguy"));

        listener.onQuit(player("PremiumGuy"));

        assertFalse(listener.hasPendingLogin("premiumguy"));
        assertFalse(state.isAuthenticated("PremiumGuy"));
        assertTrue(listener.shouldRestrict("PremiumGuy"));
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 2_000L;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10L);
        }
    }

    private static Player player(String name) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
            (self, method, args) -> switch (method.getName()) {
                case "getName" -> name;
                case "isOnline" -> Boolean.TRUE;
                case "equals" -> self == args[0];
                case "hashCode" -> System.identityHashCode(self);
                case "toString" -> "Player[" + name + "]";
                default -> {
                    Class<?> type = method.getReturnType();
                    if (!type.isPrimitive()) {
                        yield null;
                    }
                    if (type == boolean.class) {
                        yield false;
                    }
                    if (type == char.class) {
                        yield '\0';
                    }
                    if (type == byte.class) {
                        yield (byte) 0;
                    }
                    if (type == short.class) {
                        yield (short) 0;
                    }
                    if (type == int.class) {
                        yield 0;
                    }
                    if (type == long.class) {
                        yield 0L;
                    }
                    if (type == float.class) {
                        yield 0f;
                    }
                    yield 0d;
                }
            });
    }
}
