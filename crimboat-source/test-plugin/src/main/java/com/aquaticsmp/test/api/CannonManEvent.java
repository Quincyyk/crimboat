package com.aquaticsmp.test.api;

import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** A player right-clicked a cannon to man it. Cancel it to keep them off (e.g. not their crew's ship). */
public final class CannonManEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final Block cannon;
    private boolean cancelled;

    public CannonManEvent(Player player, Block cannon) {
        this.player = player;
        this.cannon = cannon;
    }

    public Player getPlayer() {
        return player;
    }

    public Block getCannon() {
        return cannon;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
