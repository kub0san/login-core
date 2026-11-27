package com.hizeric.logincore;

import org.bukkit.Server;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.Event;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

/**
 * Disabled JavaPlugin stub for seam tests. Paper's no-arg constructor needs a plugin
 * classloader, so the instance is allocated without running the constructor. A recording
 * Server is injected into the private field so event dispatch can be asserted without
 * MockBukkit or a live server.
 */
public final class TestPlugin extends JavaPlugin {

    private static final List<Event> FIRED_EVENTS = new CopyOnWriteArrayList<>();

    private FileConfiguration config;

    public static TestPlugin create() {
        try {
            Field field = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
            field.setAccessible(true);
            Object unsafe = field.get(null);
            Method allocate = unsafe.getClass().getMethod("allocateInstance", Class.class);
            TestPlugin plugin = (TestPlugin) allocate.invoke(unsafe, TestPlugin.class);
            Field server = JavaPlugin.class.getDeclaredField("server");
            server.setAccessible(true);
            FIRED_EVENTS.clear();
            server.set(plugin, recordingServer());
            return plugin;
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Cannot allocate a test JavaPlugin", ex);
        }
    }

    /** Events dispatched through this plugin's server since create(). */
    public static List<Event> firedEvents() {
        return List.copyOf(FIRED_EVENTS);
    }

    private static Server recordingServer() {
        PluginManager manager = (PluginManager) Proxy.newProxyInstance(PluginManager.class.getClassLoader(),
            new Class<?>[]{PluginManager.class}, (proxy, method, args) -> {
                if ("callEvent".equals(method.getName())) {
                    FIRED_EVENTS.add((Event) args[0]);
                }
                return null;
            });
        return (Server) Proxy.newProxyInstance(Server.class.getClassLoader(), new Class<?>[]{Server.class},
            (proxy, method, args) -> "getPluginManager".equals(method.getName()) ? manager : null);
    }

    @Override
    public FileConfiguration getConfig() {
        if (config == null) {
            config = new YamlConfiguration();
        }
        return config;
    }

    @Override
    public Logger getLogger() {
        return Logger.getLogger("logincore-test");
    }
}
