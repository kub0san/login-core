package com.hizeric.logincore.command;

import org.bukkit.command.CommandExecutor;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandRegistrationTest {

    @Test
    void pluginYmlDeclaresAllPasswordCommands() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        try (InputStream resource = getClass().getClassLoader().getResourceAsStream("plugin.yml")) {
            assertNotNull(resource);
            yaml.load(new InputStreamReader(resource, StandardCharsets.UTF_8));
        }

        ConfigurationSection commands = yaml.getConfigurationSection("commands");
        assertNotNull(commands);
        assertTrue(commands.contains("login"));
        assertTrue(commands.contains("register"));
        assertTrue(commands.contains("changepassword"));
        assertEquals("/login <password>", yaml.getString("commands.login.usage"));
        assertEquals("/register <password> <password>", yaml.getString("commands.register.usage"));
        assertEquals("/changepassword <old> <new>", yaml.getString("commands.changepassword.usage"));
    }

    @Test
    void everyPasswordCommandIsAnExecutorAndTabCompleter() {
        for (Class<?> type : new Class<?>[]{LoginCommand.class, RegisterCommand.class, ChangepasswordCommand.class}) {
            assertTrue(CommandExecutor.class.isAssignableFrom(type), type.getName());
            assertTrue(TabCompleter.class.isAssignableFrom(type), type.getName());
        }
    }
}
