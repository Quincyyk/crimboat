package com.aquaticsmp.test.api;

import com.aquaticsmp.test.Cannons;
import com.aquaticsmp.test.Engine;
import com.aquaticsmp.test.Pos;
import com.aquaticsmp.test.ShipScanner;
import com.aquaticsmp.test.TestPlugin;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * Public API for other plugins (e.g. AquaticSMP) that want to use crimboat's ships, hull health and cannons
 * with their own ships and forts. Get it with {@link #get()} (null while crimboat is not enabled), and add
 * crimboat to "softdepend" in your plugin.yml.
 *
 * <p>Events: {@link ShipCannonHitEvent} (a cannonball hit a block), {@link ShipSailEvent} (someone takes a helm),
 * {@link CannonManEvent} (someone mans a cannon).
 */
public final class CrimboatApi {

    private static CrimboatApi instance;

    private final TestPlugin plugin;

    private CrimboatApi(TestPlugin plugin) {
        this.plugin = plugin;
    }

    /** The API, or null while crimboat is not enabled. */
    public static CrimboatApi get() {
        return instance;
    }

    public static void enable(TestPlugin plugin) {
        instance = new CrimboatApi(plugin);
    }

    public static void disable() {
        instance = null;
    }

    // ------------------------------------------------------------------ hull health

    /** Hull health of a ship with this many blocks and this engine tier (1-5), from the "combat" config. */
    public int hullHealth(int blocks, int tier) {
        return plugin.combat().maxHp(blocks, tier);
    }

    /** How many direct hits an engine of this tier (1-5) takes before it blows up and sinks its ship. */
    public int engineHealth(int tier) {
        return Engine.maxEngineHp(tier);
    }

    /**
     * Shows the hull health bar a little above a hit (a floating red bar by default, or a boss bar with
     * combat.health-bar-style: bossbar). One bar per key; it hides itself after a few seconds.
     */
    public void showHullBar(String key, Component label, int hp, int max, Location at) {
        plugin.combat().showBar(key, label, hp, max, at);
    }

    /** Splinters, smoke and a crack where a hull was hit. */
    public void hullHitEffects(Location at) {
        plugin.combat().hitEffects(at);
    }

    /** Sinks these blocks like a crimboat ship: down layer by layer, settling on the sea floor as a wreck. */
    public void sinkWreck(World world, Collection<Block> blocks, int waterTop, Location at) {
        Set<Pos> set = new HashSet<>();
        for (Block b : blocks) set.add(Pos.of(b));
        plugin.combat().sink(world, set, waterTop, at);
    }

    // ------------------------------------------------------------------ ships

    /** A ship found from one of its blocks. */
    public record ShipInfo(boolean ready, boolean hasEngine, int tier, Block engine, java.util.UUID owner,
                           Set<Block> blocks, String status) {}

    /** Finds the ship this block belongs to (every connected ship block). */
    public ShipInfo scanShip(Block block) {
        ShipScanner.Result r = ShipScanner.scan(block, plugin.ships().maxSize());
        Set<Block> blocks = new HashSet<>();
        Block engine = null;
        for (Pos p : r.blocks) {
            Block b = p.block(block.getWorld());
            blocks.add(b);
            if (engine == null && r.hasEngine && Engine.blockTier(b) == r.tier) engine = b;
        }
        return new ShipInfo(r.status == ShipScanner.Status.OK, r.hasEngine, r.tier, engine,
                engine == null ? null : Engine.getOwner(engine), blocks, r.status.name());
    }

    /** True while this block is part of a ship that someone is sailing or steering right now. */
    public boolean isCaptained(Block block) {
        return plugin.ships().shipAt(block.getWorld(), Pos.of(block)) != null;
    }

    /** Engine tier of a placed block (0 = not an engine). */
    public int engineTier(Block block) {
        return Engine.blockTier(block);
    }

    /** Who placed this engine (it moves with the ship), or null for engines from before owners were saved. */
    public java.util.UUID engineOwner(Block engine) {
        return Engine.getOwner(engine);
    }

    /** Engine tier of an item (0 = not an engine item). */
    public int engineTier(ItemStack item) {
        return Engine.itemTier(item);
    }

    /** True for entities crimboat spawns for moving ships, seats and cannon models (don't touch those). */
    public boolean isCrimboatEntity(Entity e) {
        return plugin.ships().isShipPart(e) || plugin.seats().isSeat(e) || plugin.cannons().isModel(e);
    }

    // ------------------------------------------------------------------ cannons

    public boolean isCannon(Block block) {
        return Cannons.isCannonBlock(block);
    }

    public boolean isCannonItem(ItemStack item) {
        return Cannons.isCannonItem(item);
    }

    /** Puts a working cannon on this block, its barrel turned to yaw. */
    public void placeCannon(Block block, float yaw) {
        plugin.cannons().placeCannon(block, yaw);
    }

    /** Removes a cannon placed with {@link #placeCannon}. */
    public void removeCannon(Block block) {
        plugin.cannons().removeCannon(block);
    }

    /** Direction to fire this cannon to hit the target (real ball flight), or null when out of range. */
    public Vector aimAt(Block cannon, Location target) {
        return plugin.cannons().aimAt(cannon, target);
    }

    /**
     * Fires a cannon without a gunner and without ammo. shooter (may be null) is who gets the blame for the
     * damage; the ball never damages the blocks in own (the ship or fort it was fired from).
     */
    public boolean fireCannon(Block cannon, Vector direction, Entity shooter, Collection<Block> own) {
        Set<Pos> set = new HashSet<>();
        if (own != null) for (Block b : own) set.add(Pos.of(b));
        return plugin.cannons().fireFrom(cannon, direction, shooter, set);
    }

    /** Reload time of a cannon in ticks (cannons.reload-ticks). */
    public int reloadTicks() {
        return plugin.getConfig().getInt("cannons.reload-ticks", 50);
    }
}
