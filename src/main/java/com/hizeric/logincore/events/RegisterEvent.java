package com.hizeric.logincore.events;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Fired after a successful registration, for downstream plugins that listened for AuthMe's register event. */
public final class RegisterEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;

    public RegisterEvent(Player player) {
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
