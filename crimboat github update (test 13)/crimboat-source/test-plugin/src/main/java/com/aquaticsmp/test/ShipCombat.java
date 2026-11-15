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
import org.bukkit.entity.TextDisplay;
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
        int top = Integer.MIN_VALUE;
        double cx = 0, cz = 0;
        for (Pos p : r.blocks) {
            Block eb = p.block(w);
            if (engine == null && Engine.isEngineBlock(eb)) engine = eb;
            top = Math.max(top, p.y());
            cx += p.x();
            cz += p.z();
        }
        if (engine == null) return false;
        int tier = Math.max(1, Engine.blockTier(engine));
        ShipManager.Ship session = plugin.ships().shipAt(w, Pos.of(b));

        // direct hit on the engine: double damage, engine health, power loss
        boolean engineHit = engine.getLocation().add(0.5, 0.5, 0.5).distance(at) <= engineHitRadius();
        int engineMax = Engine.maxEngineHp(tier);
        int engineHp = Engine.getEngineHp(engine);
        if (engineHp < 0 || engineHp > engineMax) engineHp = engineMax;
        if (engineHit) {
            damage *= engineMultiplier();
            engineHp = Math.max(0, engineHp - 1);
            engineHitEffects(engine.getLocation().add(0.5, 0.5, 0.5), engineHp <= 0);
            if (session != null && engineHp > 0) plugin.ships().stun(session);
        }

        int max = maxHp(r.blocks.size(), tier);
        int hp = Engine.getHp(engine);
        if (hp < 0 || hp > max) hp = max;
        hp = Math.max(0, hp - damage);
        if (engineHp <= 0) hp = 0; // engine destroyed: the ship goes down
        String key = "block:" + engine.getWorld().getName() + ":" + engine.getX() + "," + engine.getY() + "," + engine.getZ();
        hitEffects(at);
        Location anchor = new Location(w, cx / r.blocks.size() + 0.5, top + 2.6, cz / r.blocks.size() + 0.5);
        showBar(key, null, hp, max, engineHp, engineMax, anchor, null);
        if (hp <= 0) {
            if (session != null) plugin.ships().endWithoutAnchor(session);
            int waterTop = r.waterTop == Integer.MIN_VALUE ? w.getSeaLevel() - 1 : r.waterTop;
            sink(w, r.blocks, waterTop, at);
        } else {
            BlockState st = engine.getState();
            Engine.setHp(st, hp);
            Engine.setEngineHp(st, engineHp);
            st.update(true, false);
        }
        return true;
    }

    /** A cannonball hit a ship that is sailing right now. */
    public void hitSailing(ShipManager.Ship s, int damage, Location at) {
        int max = maxHp(s.parts == null ? s.blocks.size() : s.parts.size(), s.tier);
        if (s.hp < 0 || s.hp > max) s.hp = max;
        int engineMax = Engine.maxEngineHp(s.tier);
        if (s.engineHp < 0 || s.engineHp > engineMax) s.engineHp = engineMax;

        Location eng = plugin.ships().engineSpot(s);
        boolean engineHit = eng != null && eng.distance(at) <= engineHitRadius();
        if (engineHit) {
            damage *= engineMultiplier();
            s.engineHp = Math.max(0, s.engineHp - 1);
            engineHitEffects(eng, s.engineHp <= 0);
            if (s.engineHp > 0) plugin.ships().stun(s);
        }
        s.hp = Math.max(0, s.hp - damage);
        if (s.engineHp <= 0) s.hp = 0;
        hitEffects(at);
        showBar("ship:" + s.driver, null, s.hp, max, s.engineHp, engineMax, plugin.ships().barSpot(s),
                () -> plugin.ships().barSpot(s));
        if (s.hp <= 0) plugin.ships().sinkSession(s, at);
    }

    private double engineHitRadius() {
        return plugin.getConfig().getDouble("combat.engine-hit-radius", 1.4);
    }

    private int engineMultiplier() {
        return Math.max(1, plugin.getConfig().getInt("combat.engine-hit-multiplier", 2));
    }

    /** Sparks, fire and a metal clang when the engine itself is hit. A big blast if it's destroyed. */
    private void engineHitEffects(Location at, boolean destroyed) {
        World w = at.getWorld();
        w.spawnParticle(Particle.LAVA, at, destroyed ? 30 : 10, 0.4, 0.4, 0.4, 0);
        w.spawnParticle(Particle.ELECTRIC_SPARK, at, 25, 0.5, 0.5, 0.5, 0.2);
        w.spawnParticle(Particle.LARGE_SMOKE, at, destroyed ? 60 : 20, 0.5, 0.6, 0.5, 0.06);
        w.playSound(at, Sound.BLOCK_ANVIL_LAND, 2f, 0.5f);
        w.playSound(at, Sound.ENTITY_IRON_GOLEM_DAMAGE, 2f, 0.6f);
        if (destroyed) {
            w.spawnParticle(Particle.EXPLOSION_EMITTER, at, 2, 0.5, 0.5, 0.5, 0);
            w.spawnParticle(Particle.FLAME, at, 60, 0.8, 0.8, 0.8, 0.1);
            w.playSound(at, Sound.ENTITY_GENERIC_EXPLODE, 4f, 0.5f);
            for (Player p : w.getPlayers()) {
                if (p.getLocation().distanceSquared(at) < 64 * 64) {
                    p.sendMessage(Component.text("✹ ENGINE DESTROYED! The ship is going down!", NamedTextColor.DARK_RED));
                }
            }
        }
    }

    /** Wood splinters, smoke and a crack where a hull was hit. */
    public void hitEffects(Location at) {
        World w = at.getWorld();
        w.spawnParticle(Particle.BLOCK, at, 30, 0.6, 0.6, 0.6, 0.1, Material.OAK_PLANKS.createBlockData());
        w.spawnParticle(Particle.LARGE_SMOKE, at, 10, 0.4, 0.4, 0.4, 0.03);
        w.playSound(at, Sound.BLOCK_WOOD_BREAK, 2f, 0.6f);
        w.playSound(at, Sound.ENTITY_ZOMBIE_BREAK_WOODEN_DOOR, 1.2f, 0.8f);
    }

    // ------------------------------------------------------------------ health bar (floating red bar above the ship)

    /** A floating bar above a ship. */
    private static final class Floating {
        TextDisplay display;
        BukkitTask hide;
        java.util.function.Supplier<Location> follow;
    }

    private final Map<String, Floating> floating = new HashMap<>();
    private BukkitTask followTask;

    /**
     * Shows the hull health bar near a hit for a few seconds. key: one bar per ship.
     * label: what is shown before the numbers (null = "Ship Hull"). Used by other plugins through the API.
     */
    public void showBar(String key, Component label, int hp, int max, Location at) {
        showBar(key, label, hp, max, -1, -1, at == null ? null : at.clone().add(0, 3, 0), null);
    }

    private void showBar(String key, Component label, int hp, int max, int engineHp, int engineMax,
                         Location anchor, java.util.function.Supplier<Location> follow) {
        if (anchor == null) return;
        if ("bossbar".equalsIgnoreCase(plugin.getConfig().getString("combat.health-bar-style", "floating"))) {
            showBossBar(key, label, hp, max, anchor);
            return;
        }
        Component text = barText(label, hp, max, engineHp, engineMax);
        Floating f = floating.get(key);
        if (f == null || f.display == null || !f.display.isValid()) {
            f = new Floating();
            Location spawn = anchor.clone();
            f.display = anchor.getWorld().spawn(spawn, TextDisplay.class, e -> {
                e.setPersistent(false);
                e.setBillboard(org.bukkit.entity.Display.Billboard.CENTER);
                e.setTeleportDuration(2);
                e.setShadowed(true);
                e.setSeeThrough(false);
                e.setLineWidth(400);
                e.setBackgroundColor(org.bukkit.Color.fromARGB(110, 0, 0, 0));
                e.setBrightness(new org.bukkit.entity.Display.Brightness(15, 15));
                e.setViewRange(1.5f);
                plugin.ships().tag(e);
            });
            floating.put(key, f);
        } else {
            f.display.teleport(anchor);
        }
        f.display.text(text);
        f.follow = follow;
        if (f.hide != null) f.hide.cancel();
        int ticks = plugin.getConfig().getInt("combat.health-bar-seconds", 8) * 20;
        if (hp <= 0) ticks = Math.min(ticks, 60);
        f.hide = Bukkit.getScheduler().runTaskLater(plugin, () -> removeFloating(key), ticks);
        if (followTask == null) followTask = Bukkit.getScheduler().runTaskTimer(plugin, this::followTick, 1L, 1L);
    }

    /** "⚓ Ship Hull" / "██████████░░░░" in red / "12 / 20"  (+ an engine line once the engine is damaged). */
    private Component barText(Component label, int hp, int max, int engineHp, int engineMax) {
        int segments = 20;
        float frac = max <= 0 ? 0f : Math.max(0f, Math.min(1f, hp / (float) max));
        int filled = Math.round(frac * segments);
        if (hp > 0 && filled == 0) filled = 1;
        Component out = Component.text("⚓ ", NamedTextColor.GRAY)
                .append(label == null ? Component.text("Ship Hull", NamedTextColor.WHITE) : label)
                .append(Component.newline())
                .append(Component.text("▌".repeat(filled), frac > 0.3f ? NamedTextColor.RED : NamedTextColor.DARK_RED))
                .append(Component.text("▌".repeat(segments - filled), NamedTextColor.DARK_GRAY))
                .append(Component.text("  " + hp + "/" + max, NamedTextColor.WHITE));
        if (engineMax > 0 && engineHp >= 0 && engineHp < engineMax) {
            out = out.append(Component.newline())
                    .append(Component.text("⚙ Engine ", NamedTextColor.GOLD))
                    .append(Component.text("■".repeat(engineHp), NamedTextColor.GOLD))
                    .append(Component.text("■".repeat(engineMax - engineHp), NamedTextColor.DARK_GRAY));
        }
        if (hp <= 0) out = out.append(Component.newline()).append(Component.text("SINKING!", NamedTextColor.DARK_RED));
        return out;
    }

    private void followTick() {
        if (floating.isEmpty()) {
            followTask.cancel();
            followTask = null;
            return;
        }
        for (Map.Entry<String, Floating> e : new ArrayList<>(floating.entrySet())) {
            Floating f = e.getValue();
            if (f.display == null || !f.display.isValid()) {
                floating.remove(e.getKey());
                continue;
            }
            if (f.follow != null) {
                Location to = f.follow.get();
                if (to != null) f.display.teleport(to);
            }
        }
    }

    private void removeFloating(String key) {
        Floating f = floating.remove(key);
        if (f == null) return;
        if (f.hide != null) f.hide.cancel();
        if (f.display != null) f.display.remove();
    }

    /** Old style: a boss bar at the top of the screen (combat.health-bar-style: bossbar). */
    private void showBossBar(String key, Component label, int hp, int max, Location at) {
        float progress = max <= 0 ? 0f : Math.max(0f, Math.min(1f, hp / (float) max));
        BossBar.Color color = progress > 0.6f ? BossBar.Color.GREEN : progress > 0.3f ? BossBar.Color.YELLOW : BossBar.Color.RED;
        Component title = Component.text("⚓ ", NamedTextColor.WHITE)
                .append(label == null ? Component.text("Ship Hull", NamedTextColor.WHITE) : label)
                .append(Component.text("  "))
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
        for (String k : new ArrayList<>(floating.keySet())) removeFloating(k);
        if (followTask != null) followTask.cancel();
        followTask = null;
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
                Engine.setEngineHp(st, -1);
                Engine.setOwner(st, null);
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
