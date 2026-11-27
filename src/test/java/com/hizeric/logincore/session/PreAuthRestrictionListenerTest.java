package com.hizeric.logincore.session;

import com.hizeric.logincore.TestPlugin;
import com.hizeric.logincore.storage.Storage;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        listener = new PreAuthRestrictionListener(TestPlugin.create(), state, storage, Runnable::run, () -> true);
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
    void storedPremiumIsKickedWhenPremiumSupportUnavailable() throws Exception {
        listener = new PreAuthRestrictionListener(TestPlugin.create(), state, storage, Runnable::run, () -> false);
        storage.setClassification("PremiumGuy", "PREMIUM").get();
        List<String> kicks = new CopyOnWriteArrayList<>();

        listener.onJoin(player("PremiumGuy", kicks));
        await(() -> !kicks.isEmpty());

        assertFalse(kicks.isEmpty(), "stored PREMIUM must be kicked when premium support is unavailable");
        assertTrue(kicks.get(0).toLowerCase().contains("unavailable"), kicks.toString());
        assertFalse(state.isAuthenticated("premiumguy"));
        assertTrue(listener.shouldRestrict("PremiumGuy"));
    }

    @Test
    void storedPremiumBypassesWhenPremiumSupportAvailable() throws Exception {
        storage.setClassification("PremiumGuy", "PREMIUM").get();
        List<String> kicks = new CopyOnWriteArrayList<>();

        listener.onJoin(player("PremiumGuy", kicks));
        await(() -> state.isAuthenticated("premiumguy"));

        assertTrue(state.isAuthenticated("premiumguy"));
        assertFalse(listener.shouldRestrict("PREMIUMGUY"));
        assertTrue(kicks.isEmpty(), kicks.toString());
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

    @Test
    void cancelIfRestrictedCancelsOnlyRestrictedPlayers() {
        Player restricted = player("RestrictedGuy");
        state.authenticate("AuthGuy");
        Player authenticated = player("AuthGuy");
        RecordingCancellable restrictedEvent = new RecordingCancellable();
        RecordingCancellable authenticatedEvent = new RecordingCancellable();
        RecordingCancellable noPlayerEvent = new RecordingCancellable();

        assertTrue(listener.cancelIfRestricted(restricted, restrictedEvent));
        assertTrue(restrictedEvent.isCancelled());

        assertFalse(listener.cancelIfRestricted(authenticated, authenticatedEvent));
        assertFalse(authenticatedEvent.isCancelled());

        assertFalse(listener.cancelIfRestricted(null, noPlayerEvent));
        assertFalse(noPlayerEvent.isCancelled());
    }

    @Test
    void allPreAuthHandlersAreRegistered() {
        Set<String> required = Set.of("onBlockBreak", "onBlockPlace", "onSwapHandItems",
            "onVehicleEnter", "onAttemptPickupItem", "onProjectileDamage");
        Set<String> found = new HashSet<>();
        for (Method method : PreAuthRestrictionListener.class.getDeclaredMethods()) {
            EventHandler annotation = method.getAnnotation(EventHandler.class);
            if (annotation == null || !required.contains(method.getName())) {
                continue;
            }
            found.add(method.getName());
            assertEquals(EventPriority.LOWEST, annotation.priority(), method.getName());
            assertTrue(annotation.ignoreCancelled(), method.getName());
        }
        assertEquals(required, found);
    }

    @Test
    void projectileShotByRestrictedPlayerIsCancelled() {
        Player shooter = player("RestrictedShooter");
        Player victim = player("Victim");
        state.authenticate("victim");
        Projectile projectile = projectile(shooter);
        // The 4-arg overload builds a DamageSource via Bukkit.getUnsafe(), which is unset in unit tests.
        EntityDamageByEntityEvent event =
            new EntityDamageByEntityEvent(projectile, victim, DamageCause.PROJECTILE, null, 1.0D);

        listener.onProjectileDamage(event);

        assertTrue(event.isCancelled());
    }

    private static final class RecordingCancellable implements Cancellable {
        private boolean cancelled;

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public void setCancelled(boolean cancel) {
            cancelled = cancel;
        }
    }

    private static Projectile projectile(Player shooter) {
        return (Projectile) Proxy.newProxyInstance(Projectile.class.getClassLoader(), new Class<?>[]{Projectile.class},
            (self, method, args) -> switch (method.getName()) {
                case "getShooter" -> shooter;
                case "equals" -> self == args[0];
                case "hashCode" -> System.identityHashCode(self);
                case "toString" -> "Projectile[" + shooter + "]";
                default -> defaultValue(method.getReturnType());
            });
    }

    private static String kickText(Object value) {
        if (value instanceof Component component) {
            return PlainTextComponentSerializer.plainText().serialize(component);
        }
        return String.valueOf(value);
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 2_000L;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10L);
        }
    }

    private static Player player(String name) {
        return player(name, null);
    }

    private static Player player(String name, List<String> kicks) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
            (self, method, args) -> switch (method.getName()) {
                case "getName" -> name;
                case "isOnline" -> Boolean.TRUE;
                case "kick", "kickPlayer" -> {
                    if (kicks != null) {
                        kicks.add(kickText(args[0]));
                    }
                    yield null;
                }
                case "equals" -> self == args[0];
                case "hashCode" -> System.identityHashCode(self);
                case "toString" -> "Player[" + name + "]";
                default -> defaultValue(method.getReturnType());
            });
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0f;
        }
        return 0d;
    }
}
