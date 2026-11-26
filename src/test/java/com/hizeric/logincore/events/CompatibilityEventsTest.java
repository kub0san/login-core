package com.hizeric.logincore.events;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertSame;

/** Downstream compatibility events must use Bukkit's HandlerList model and carry the player. */
class CompatibilityEventsTest {

    @Test
    void loginEventUsesBukkitHandlerList() {
        Player player = player();
        LoginEvent event = new LoginEvent(player);

        assertSame(player, event.getPlayer());
        assertSame(LoginEvent.getHandlerList(), event.getHandlers());
    }

    @Test
    void registerEventUsesBukkitHandlerList() {
        Player player = player();
        RegisterEvent event = new RegisterEvent(player);

        assertSame(player, event.getPlayer());
        assertSame(RegisterEvent.getHandlerList(), event.getHandlers());
    }

    private static Player player() {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
            (proxy, method, args) -> null);
    }
}
