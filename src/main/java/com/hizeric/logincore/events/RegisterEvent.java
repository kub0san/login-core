package com.hizeric.logincore.events;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Login-core-owned event fired after {@code /register} or premium auto-registration. Intended for source-level consumers compiled against login-core; provides no binary compatibility with {@code fr.xephi.authme.events.*}. */
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
