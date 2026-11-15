package com.aquaticsmp.test.api;

import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.Collections;
import java.util.Set;

/**
 * A player sat on a ship's Pirate Chair and is about to take the helm (the ship passed its check).
 * Cancel it to stop them, e.g. because the ship belongs to another crew; they stay seated.
 */
public final class ShipSailEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final Block helm;
    private final Block engine;
    private final Set<Block> blocks;
    private boolean cancelled;
    private String denyMessage;

    public ShipSailEvent(Player player, Block helm, Block engine, Set<Block> blocks) {
        this.player = player;
        this.helm = helm;
        this.engine = engine;
        this.blocks = Collections.unmodifiableSet(blocks);
    }

    public Player getPlayer() {
        return player;
    }

    /** The stair the player sits on. */
    public Block getHelm() {
        return helm;
    }

    /** The ship's engine block (the best one when there are several), or null. */
    public Block getEngine() {
        return engine;
    }

    /** Every block of the ship. */
    public Set<Block> getBlocks() {
        return blocks;
    }

    /** Message shown to the player when the event is cancelled (null = none). */
    public String getDenyMessage() {
        return denyMessage;
    }

    public void setDenyMessage(String message) {
        this.denyMessage = message;
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
