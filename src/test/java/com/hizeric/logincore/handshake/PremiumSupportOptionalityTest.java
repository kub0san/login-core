package com.hizeric.logincore.handshake;

import org.mockbukkit.mockbukkit.MockBukkit;
import com.hizeric.logincore.LoginCore;
import com.hizeric.logincore.TestPlugin;
import com.hizeric.logincore.mojang.MojangClient;
import com.hizeric.logincore.session.VerifiedSessions;
import com.hizeric.logincore.storage.Storage;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** PacketEvents is optional: no PE types may leak into LoginCore and PE absence must degrade safely. */
class PremiumSupportOptionalityTest {

    @BeforeEach
    void startMockServer() {
        MockBukkit.mock();
    }

    @AfterEach
    void stopMockServer() {
        MockBukkit.unmock();
    }

    @Test
    void pluginYmlUsesSoftDepend() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        try (InputStream resource = getClass().getClassLoader().getResourceAsStream("plugin.yml")) {
            assertNotNull(resource);
            yaml.load(new InputStreamReader(resource, StandardCharsets.UTF_8));
        }

        assertTrue(yaml.getStringList("softdepend").contains("packetevents"),
            "packetevents must be a soft dependency");
        assertFalse(yaml.getStringList("depend").contains("packetevents"),
            "packetevents must not be a hard dependency");
    }

    @Test
    void loginCoreHasNoPacketEventsTypes() {
        for (Field field : LoginCore.class.getDeclaredFields()) {
            assertFalse(isPacketEventsType(field.getType()), "field " + field.getName());
        }
        for (Method method : LoginCore.class.getDeclaredMethods()) {
            assertFalse(isPacketEventsType(method.getReturnType()), "return of " + method.getName());
            for (Class<?> parameter : method.getParameterTypes()) {
                assertFalse(isPacketEventsType(parameter), "parameter of " + method.getName());
            }
        }
        for (Constructor<?> constructor : LoginCore.class.getDeclaredConstructors()) {
            for (Class<?> parameter : constructor.getParameterTypes()) {
                assertFalse(isPacketEventsType(parameter), "constructor parameter");
            }
        }
    }

    @Test
    void factoryReturnsNoOpWhenPacketEventsPluginIsAbsent() {
        PremiumSupport support = PremiumSupportFactory.create(
            TestPlugin.create(), null, null, null, null, null);

        assertInstanceOf(NoOpPremiumSupport.class, support);
        assertFalse(support.available());
        // No-op lifecycle methods must be safe to call even with null collaborators.
        support.registerBukkitEvents();
        support.release().releaseAfterAuth("missing-connection");
        support.release().shutdown();
        support.shutdown();
    }

    @Test
    void premiumHandshakeSupportExposesFactoryCompatibleCreate() throws Exception {
        assertTrue(PremiumSupport.class.isAssignableFrom(PremiumHandshakeSupport.class));

        Method create = PremiumHandshakeSupport.class.getMethod("create", JavaPlugin.class, Storage.class,
            VerifiedSessions.class, MojangClient.class, ExecutorService.class, LoginMetrics.class);
        assertTrue(Modifier.isStatic(create.getModifiers()));
        assertEquals(PremiumSupport.class, create.getReturnType());
    }

    private static boolean isPacketEventsType(Class<?> type) {
        return type.getName().startsWith("com.github.retrooper");
    }
}
