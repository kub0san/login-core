package com.hizeric.logincore.events;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Fired after a successful login, for downstream plugins that listened for AuthMe's login event. */
public final class LoginEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;

    public LoginEvent(Player player) {
        this.player = player;
    }

    public Player getPlayer() {
        return player;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
