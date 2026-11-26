package com.hizeric.logincore;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.logging.Logger;

/**
 * Disabled JavaPlugin stub for seam tests. Paper's no-arg constructor needs a plugin
 * classloader, so the instance is allocated without running the constructor; nothing
 * in these tests touches the lifecycle manager. No server, no MockBukkit bootstrap.
 */
public final class TestPlugin extends JavaPlugin {

    private FileConfiguration config;

    public static TestPlugin create() {
        try {
            Field field = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
            field.setAccessible(true);
            Object unsafe = field.get(null);
            Method allocate = unsafe.getClass().getMethod("allocateInstance", Class.class);
            return (TestPlugin) allocate.invoke(unsafe, TestPlugin.class);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Cannot allocate a test JavaPlugin", ex);
        }
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
