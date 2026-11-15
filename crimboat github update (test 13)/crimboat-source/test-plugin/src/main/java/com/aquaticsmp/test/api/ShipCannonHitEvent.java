package com.aquaticsmp.test.api;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * A cannonball hit a block (not water, not your own ship). Fired before the hull damage is worked out.
 * Cancel it to skip the normal ship damage, e.g. because another plugin handles that block itself
 * (its own ships or forts). The explosion still happens either way.
 */
public final class ShipCannonHitEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Block block;
    private final Location location;
    private final Entity shooter;
    private int damage;
    private boolean cancelled;

    public ShipCannonHitEvent(Block block, Location location, Entity shooter, int damage) {
        this.block = block;
        this.location = location;
        this.shooter = shooter;
        this.damage = damage;
    }

    /** The block the ball hit. */
    public Block getBlock() {
        return block;
    }

    /** Where exactly it hit. */
    public Location getLocation() {
        return location.clone();
    }

    /** Who fired: a player, a mob (cannons fired through {@link CrimboatApi#fireCannon}), or null. */
    public Entity getShooter() {
        return shooter;
    }

    /** Hull damage this hit does (cannons.ship-damage). */
    public int getDamage() {
        return damage;
    }

    public void setDamage(int damage) {
        this.damage = Math.max(0, damage);
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
