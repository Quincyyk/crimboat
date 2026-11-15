package com.aquaticsmp.test;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Ship health, damage from cannonballs, the health bar, and sinking. */
public final class ShipCombat {

    private final TestPlugin plugin;
    private final Map<String, BossBar> bars = new HashMap<>();
    private final Map<String, Set<UUID>> barViewers = new HashMap<>();
    private final Map<String, BukkitTask> barTimers = new HashMap<>();

    public ShipCombat(TestPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------ health

    public int maxHp(int size, int tier) {
        double t = plugin.ships().sizeT(size);
        double small = plugin.getConfig().getDouble("combat.health-small-ship", 5);
        double big = plugin.getConfig().getDouble("combat.health-big-ship", 20);
        int perTier = plugin.getConfig().getInt("combat.extra-health-per-engine-tier", 2);
        return (int) Math.round(small + (big - small) * t) + (Math.max(1, tier) - 1) * perTier;
    }

    /** A cannonball hit a real block. Returns true if that block belongs to a ship (engine on board). */
    public boolean hitBlock(Block b, int damage, Location at) {
        ShipScanner.Result r = ShipScanner.scan(b, plugin.ships().maxSize());
        if (!r.hasEngine) return false;
        World w = b.getWorld();
        Block engine = null;
        for (Pos p : r.blocks) {
            Block eb = p.block(w);
            if (Engine.isEngineBlock(eb)) {
                engine = eb;
                break;
            }
        }
        if (engine == null) return false;
        int max = maxHp(r.blocks.size(), r.tier);
        int hp = Engine.getHp(engine);
        if (hp < 0 || hp > max) hp = max;
        hp = Math.max(0, hp - damage);
        String key = "block:" + engine.getWorld().getName() + ":" + engine.getX() + "," + engine.getY() + "," + engine.getZ();
        hitEffects(at);
        showBar(key, hp, max, at);
        if (hp <= 0) {
            ShipManager.Ship session = plugin.ships().shipAt(w, Pos.of(b));
            if (session != null) plugin.ships().endWithoutAnchor(session);
            int waterTop = r.waterTop == Integer.MIN_VALUE ? w.getSeaLevel() - 1 : r.waterTop;
            sink(w, r.blocks, waterTop, at);
        } else {
            BlockState st = engine.getState();
            Engine.setHp(st, hp);
            st.update(true, false);
        }
        return true;
    }

    /** A cannonball hit a ship that is sailing right now. */
    public void hitSailing(ShipManager.Ship s, int damage, Location at) {
        int max = maxHp(s.parts == null ? s.blocks.size() : s.parts.size(), s.tier);
        if (s.hp < 0 || s.hp > max) s.hp = max;
        s.hp = Math.max(0, s.hp - damage);
        hitEffects(at);
        showBar("ship:" + s.driver, s.hp, max, at);
        if (s.hp <= 0) plugin.ships().sinkSession(s, at);
    }

    private void hitEffects(Location at) {
        World w = at.getWorld();
        w.spawnParticle(Particle.BLOCK, at, 30, 0.6, 0.6, 0.6, 0.1, Material.OAK_PLANKS.createBlockData());
        w.spawnParticle(Particle.LARGE_SMOKE, at, 10, 0.4, 0.4, 0.4, 0.03);
        w.playSound(at, Sound.BLOCK_WOOD_BREAK, 2f, 0.6f);
        w.playSound(at, Sound.ENTITY_ZOMBIE_BREAK_WOODEN_DOOR, 1.2f, 0.8f);
    }

    private void showBar(String key, int hp, int max, Location at) {
        float progress = max <= 0 ? 0f : Math.max(0f, Math.min(1f, hp / (float) max));
        BossBar.Color color = progress > 0.6f ? BossBar.Color.GREEN : progress > 0.3f ? BossBar.Color.YELLOW : BossBar.Color.RED;
        Component title = Component.text("⚓ Ship Hull  ", NamedTextColor.WHITE)
                .append(Component.text(hp + " / " + max, progress > 0.3f ? NamedTextColor.YELLOW : NamedTextColor.RED))
                .append(hp <= 0 ? Component.text("  SINKING!", NamedTextColor.DARK_RED) : Component.empty());
        BossBar bar = bars.computeIfAbsent(key, k -> BossBar.bossBar(title, progress, color, BossBar.Overlay.NOTCHED_10));
        bar.name(title);
        bar.progress(progress);
        bar.color(color);
        Set<UUID> viewers = barViewers.computeIfAbsent(key, k -> new HashSet<>());
        double range = plugin.getConfig().getDouble("combat.health-bar-range", 48);
        for (Player p : at.getWorld().getPlayers()) {
            if (p.getLocation().distanceSquared(at) <= range * range && viewers.add(p.getUniqueId())) p.showBossBar(bar);
        }
        BukkitTask old = barTimers.remove(key);
        if (old != null) old.cancel();
        barTimers.put(key, Bukkit.getScheduler().runTaskLater(plugin, () -> hideBar(key), 120L));
    }

    private void hideBar(String key) {
        BossBar bar = bars.remove(key);
        Set<UUID> viewers = barViewers.remove(key);
        barTimers.remove(key);
        if (bar == null || viewers == null) return;
        for (UUID id : viewers) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) p.hideBossBar(bar);
        }
    }

    public void shutdown() {
        for (String k : new ArrayList<>(bars.keySet())) hideBar(k);
    }

    // ------------------------------------------------------------------ sinking

    /** The ship goes down one layer at a time with splashes and bubbles, and settles on the sea floor as a wreck. */
    public void sink(World w, Set<Pos> blocks, int waterTop, Location at) {
        // everyone off: seats removed (players drop into the water)
        for (Map.Entry<UUID, Location> e : plugin.seats().allSeats().entrySet()) {
            Location l = e.getValue();
            if (w.equals(l.getWorld()) && blocks.contains(Pos.of(l.getBlock()))) plugin.seats().removeSeat(e.getKey());
        }
        // loot floats up, cannons and engines are lost
        Location loot = null;
        for (Pos p : blocks) {
            Block b = p.block(w);
            if (Cannons.isCannonBlock(b)) plugin.cannons().removeModels(b);
            BlockState st = b.getState();
            boolean changed = false;
            if (st instanceof Container c) {
                Location drop = new Location(w, p.x() + 0.5, waterTop + 1.2, p.z() + 0.5);
                for (ItemStack it : c.getInventory().getContents()) {
                    if (it != null && !it.getType().isAir()) w.dropItemNaturally(drop, it.clone());
                }
                c.getInventory().clear();
                loot = drop;
            }
            if (Engine.isEngineBlock(b)) {
                Engine.setEngineTag(st, 0);
                Engine.setHp(st, -1);
                changed = true;
            }
            if (Cannons.isCannonBlock(b)) {
                Cannons.setCannonTag(st, false);
                changed = true;
            }
            if (changed) st.update(true, false);
        }

        for (Player p : w.getPlayers()) {
            if (p.getLocation().distanceSquared(at) < 64 * 64) {
                p.sendMessage(Component.text("⚓ A ship is going down!", NamedTextColor.DARK_RED));
                p.playSound(at, Sound.ENTITY_ELDER_GUARDIAN_CURSE, 1.2f, 0.6f);
            }
        }
        if (loot != null) w.playSound(loot, Sound.ENTITY_ITEM_PICKUP, 1f, 0.5f);

        Map<Pos, BlockData> pieces = new HashMap<>();
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (Pos p : blocks) {
            pieces.put(p, p.block(w).getBlockData());
            minX = Math.min(minX, p.x());
            maxX = Math.max(maxX, p.x());
            minZ = Math.min(minZ, p.z());
            maxZ = Math.max(maxZ, p.z());
        }
        final int fMinX = minX, fMaxX = maxX, fMinZ = minZ, fMaxZ = maxZ;
        int interval = Math.max(2, plugin.getConfig().getInt("combat.sink-step-ticks", 8));

        new BukkitRunnable() {
            int steps = 0;

            @Override
            public void run() {
                steps++;
                List<Map.Entry<Pos, BlockData>> order = new ArrayList<>(pieces.entrySet());
                order.sort(Comparator.comparingInt(e -> e.getKey().y()));
                Set<Pos> taken = new HashSet<>();
                Map<Pos, BlockData> next = new HashMap<>();
                boolean moved = false;
                for (Map.Entry<Pos, BlockData> e : order) {
                    Pos p = e.getKey();
                    Pos down = p.add(0, -1, 0);
                    boolean blocked;
                    if (down.y() <= w.getMinHeight()) blocked = true;
                    else if (pieces.containsKey(down)) blocked = taken.contains(down);
                    else {
                        Material t = down.block(w).getType();
                        blocked = !(Materials.isAirLike(t) || Materials.isWaterLike(t));
                    }
                    Pos to = blocked ? p : down;
                    if (!blocked) moved = true;
                    taken.add(to);
                    next.put(to, e.getValue());
                }
                // clear spots the ship left, then place it lower
                for (Pos p : pieces.keySet()) {
                    if (!next.containsKey(p)) p.block(w).setType(p.y() <= waterTop ? Material.WATER : Material.AIR, false);
                }
                for (Map.Entry<Pos, BlockData> e : next.entrySet()) {
                    if (pieces.containsKey(e.getKey()) && pieces.get(e.getKey()).equals(e.getValue())) continue;
                    BlockData d = e.getValue().clone();
                    if (d instanceof Waterlogged wl) wl.setWaterlogged(e.getKey().y() <= waterTop);
                    e.getKey().block(w).setBlockData(d, false);
                }
                pieces.clear();
                pieces.putAll(next);

                // spray and bubbles on the surface
                ThreadLocalRandom r = ThreadLocalRandom.current();
                for (int i = 0; i < 12; i++) {
                    Location s = new Location(w, fMinX + r.nextDouble() * (fMaxX - fMinX + 1), waterTop + 1.1,
                            fMinZ + r.nextDouble() * (fMaxZ - fMinZ + 1));
                    w.spawnParticle(Particle.SPLASH, s, 12, 0.6, 0.2, 0.6, 0.1);
                    w.spawnParticle(Particle.BUBBLE_POP, s, 6, 0.5, 0.2, 0.5, 0.02);
                    w.spawnParticle(Particle.CLOUD, s, 2, 0.4, 0.3, 0.4, 0.02);
                }
                Location c = new Location(w, (fMinX + fMaxX) / 2.0, waterTop + 1, (fMinZ + fMaxZ) / 2.0);
                w.playSound(c, steps % 3 == 0 ? Sound.BLOCK_WOOD_BREAK : Sound.ENTITY_GENERIC_SPLASH, 2f, 0.6f);

                if (!moved || steps > 80) {
                    w.playSound(c, Sound.AMBIENT_UNDERWATER_EXIT, 2f, 0.7f);
                    w.spawnParticle(Particle.BUBBLE_COLUMN_UP, c.clone().add(0, -3, 0), 200, 3, 2, 3, 0.1);
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 30L, interval);
    }
}
