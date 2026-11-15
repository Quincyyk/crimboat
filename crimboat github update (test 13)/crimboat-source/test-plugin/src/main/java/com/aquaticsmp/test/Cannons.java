package com.aquaticsmp.test;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.TileState;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Ship Cannons. Place one on your ship, right-click it to man it, aim with your mouse
 * (a dotted line shows where the ball will land, like aiming a crossbow) and click to fire.
 * Ammo is the AquaticSMP Cannonball (or this plugin's own cannonball if AquaticSMP isn't installed).
 */
public final class Cannons {

    public static final Material BLOCK = Material.BARREL;
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private static NamespacedKey cannonKey;   // on the item and the placed barrel block
    private static NamespacedKey modelKey;    // on the barrel model entities: "world:x,y,z" of their cannon block
    private static NamespacedKey ownBallKey;  // this plugin's own cannonball
    private static final NamespacedKey AQUATIC_ID = new NamespacedKey("aquaticsmp", "item_id");

    private final TestPlugin plugin;

    /** Someone manning a cannon. */
    static final class Gunner {
        final UUID player;
        final UUID seat;
        final List<UUID> models;
        int cooldown;
        int ticks;
        int lastTry = -1;

        Gunner(UUID player, UUID seat, List<UUID> models) {
            this.player = player;
            this.seat = seat;
            this.models = models;
        }
    }

    /** A flying cannonball. */
    static final class Ball {
        final UUID shooter;
        final ItemDisplay visual;
        final ShipManager.Ship ownSailing;
        java.util.Set<Pos> ownBlocks = java.util.Set.of();
        Vector pos, vel;
        int life;

        Ball(UUID shooter, ItemDisplay visual, ShipManager.Ship ownSailing, Vector pos, Vector vel) {
            this.shooter = shooter;
            this.visual = visual;
            this.ownSailing = ownSailing;
            this.pos = pos;
            this.vel = vel;
        }
    }

    private final Map<UUID, Gunner> gunners = new HashMap<>(); // by player
    private final List<Ball> balls = new ArrayList<>();
    private BukkitTask task;

    public Cannons(TestPlugin plugin) {
        this.plugin = plugin;
        cannonKey = new NamespacedKey(plugin, "cannon");
        modelKey = new NamespacedKey(plugin, "cannon_model");
        ownBallKey = new NamespacedKey(plugin, "cannonball");
    }

    public void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    public void stop() {
        if (task != null) task.cancel();
        for (Ball b : balls) b.visual.remove();
        balls.clear();
        gunners.clear();
    }

    private double cfg(String k, double def) {
        return plugin.getConfig().getDouble("cannons." + k, def);
    }

    // ------------------------------------------------------------------ items

    public static ItemStack createCannonItem(int amount) {
        ItemStack item = new ItemStack(BLOCK, amount);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(MM.deserialize("<!italic><bold><gradient:#5A5A5A:#C9C9C9>✹ " + Engine.smallCaps("ship cannon")
                + "</gradient></bold>"));
        meta.lore(List.of(
                MM.deserialize("<!italic><gray>Place it on your ship's deck."),
                Component.empty(),
                MM.deserialize("<!italic><gray>" + Engine.smallCaps("right-click") + " <white>man the cannon"),
                MM.deserialize("<!italic><gray>" + Engine.smallCaps("mouse") + " <white>aim (follow the dotted line)"),
                MM.deserialize("<!italic><gray>" + Engine.smallCaps("click") + " <white>fire a Cannonball"),
                MM.deserialize("<!italic><gray>" + Engine.smallCaps("shift") + " <white>step away")));
        meta.getPersistentDataContainer().set(cannonKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    /** Our own cannonball (only used if AquaticSMP isn't installed; looks like theirs with their resource pack). */
    public static ItemStack createOwnCannonball(int amount) {
        ItemStack item = new ItemStack(Material.FIREWORK_STAR, amount);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(MM.deserialize("<!italic><dark_gray><bold>Cannonball</bold>"));
        meta.lore(List.of(MM.deserialize("<!italic><gray>Ammunition for ship cannons.")));
        try {
            meta.setItemModel(NamespacedKey.fromString("aquatic:cannonball"));
        } catch (Throwable ignored) {
            // older API
        }
        meta.getPersistentDataContainer().set(ownBallKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    public static boolean isCannonItem(ItemStack it) {
        return it != null && it.getType() == BLOCK && it.hasItemMeta()
                && it.getItemMeta().getPersistentDataContainer().has(cannonKey, PersistentDataType.BYTE);
    }

    public static boolean isCannonball(ItemStack it) {
        if (it == null || it.getType().isAir() || !it.hasItemMeta()) return false;
        var pdc = it.getItemMeta().getPersistentDataContainer();
        if ("cannonball".equals(pdc.get(AQUATIC_ID, PersistentDataType.STRING))) return true;
        return pdc.has(ownBallKey, PersistentDataType.BYTE);
    }

    public static boolean isCannonBlock(Block b) {
        if (b.getType() != BLOCK) return false;
        BlockState st = b.getState();
        return st instanceof TileState ts && ts.getPersistentDataContainer().has(cannonKey, PersistentDataType.BYTE);
    }

    public static void setCannonTag(BlockState st, boolean cannon) {
        if (!(st instanceof TileState ts)) return;
        if (cannon) ts.getPersistentDataContainer().set(cannonKey, PersistentDataType.BYTE, (byte) 1);
        else ts.getPersistentDataContainer().remove(cannonKey);
    }

    public void registerRecipes() {
        ShapedRecipe r = new ShapedRecipe(new NamespacedKey(plugin, "ship_cannon"), createCannonItem(1));
        r.shape("III", "IGI", "LBL");
        r.setIngredient('I', Material.IRON_INGOT);
        r.setIngredient('G', Material.GUNPOWDER);
        r.setIngredient('L', new RecipeChoice.MaterialChoice(Tag.LOGS));
        r.setIngredient('B', Material.BARREL);
        Bukkit.addRecipe(r);
        // Our own cannonball recipe only when AquaticSMP (which has its own) isn't installed.
        if (Bukkit.getPluginManager().getPlugin("AquaticSMP") == null) {
            ItemStack out = createOwnCannonball(4);
            ShapedRecipe b = new ShapedRecipe(new NamespacedKey(plugin, "cannonball"), out);
            b.shape(" I ", "IGI", " I ");
            b.setIngredient('I', Material.IRON_INGOT);
            b.setIngredient('G', Material.GUNPOWDER);
            Bukkit.addRecipe(b);
        }
    }

    public void unregisterRecipes() {
        Bukkit.removeRecipe(new NamespacedKey(plugin, "ship_cannon"));
        Bukkit.removeRecipe(new NamespacedKey(plugin, "cannonball"));
    }

    // ------------------------------------------------------------------ the barrel model

    static String tagOf(Block b) {
        return b.getWorld().getName() + ":" + b.getX() + "," + b.getY() + "," + b.getZ();
    }

    /** Where the barrel model sits on a cannon block. */
    static Location modelSpot(Block b) {
        return b.getLocation().add(0.5, 1.15, 0.5);
    }

    public void spawnModels(Block b, float yaw) {
        Location at = modelSpot(b);
        at.setYaw(yaw);
        at.setPitch(-8f);
        String tag = tagOf(b);
        String itemModel = plugin.getConfig().getString("cannons.barrel-item-model", "");
        if (itemModel != null && !itemModel.isBlank()) {
            // A textured cannon from a resource pack (e.g. AquaticSMP's "aquatic:hand_cannon").
            float sc = (float) cfg("barrel-item-scale", 2.2);
            b.getWorld().spawn(at, ItemDisplay.class, e -> {
                ItemStack it = new ItemStack(Material.IRON_HORSE_ARMOR);
                ItemMeta m = it.getItemMeta();
                try {
                    m.setItemModel(NamespacedKey.fromString(itemModel));
                } catch (Throwable ignored) {
                    // older API
                }
                it.setItemMeta(m);
                e.setItemStack(it);
                e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
                e.setTransformation(new Transformation(new Vector3f(0, 0, 0.4f),
                        new Quaternionf().rotateY((float) Math.PI), new Vector3f(sc, sc, sc), new Quaternionf()));
                setupModel(e, tag);
            });
            return;
        }
        // Built from blocks: iron-dark barrel with rings and a knob at the back.
        piece(at, tag, Material.COAL_BLOCK, -0.21f, -0.21f, -0.30f, 0.42f, 0.42f, 1.45f);
        piece(at, tag, Material.POLISHED_BLACKSTONE, -0.27f, -0.27f, 1.05f, 0.54f, 0.54f, 0.16f);
        piece(at, tag, Material.POLISHED_BLACKSTONE, -0.26f, -0.26f, 0.35f, 0.52f, 0.52f, 0.10f);
        piece(at, tag, Material.POLISHED_BLACKSTONE, -0.25f, -0.25f, -0.36f, 0.50f, 0.50f, 0.14f);
        piece(at, tag, Material.POLISHED_BLACKSTONE, -0.10f, -0.10f, -0.52f, 0.20f, 0.20f, 0.18f);
        piece(at, tag, Material.BLACK_CONCRETE, -0.13f, -0.13f, 1.16f, 0.26f, 0.26f, 0.02f); // the bore
    }

    private void piece(Location at, String tag, Material m, float x, float y, float z, float sx, float sy, float sz) {
        at.getWorld().spawn(at, BlockDisplay.class, e -> {
            e.setBlock(m.createBlockData());
            e.setTransformation(new Transformation(new Vector3f(x, y, z), new Quaternionf(),
                    new Vector3f(sx, sy, sz), new Quaternionf()));
            setupModel(e, tag);
        });
    }

    private void setupModel(Display e, String tag) {
        e.setPersistent(true);
        e.setTeleportDuration(2);
        e.setBrightness(new Display.Brightness(0, 15));
        e.getPersistentDataContainer().set(modelKey, PersistentDataType.STRING, tag);
    }

    public List<Entity> findModels(Block b) {
        String tag = tagOf(b);
        List<Entity> out = new ArrayList<>();
        for (Entity e : b.getWorld().getNearbyEntities(modelSpot(b), 2.5, 2.5, 2.5)) {
            if (tag.equals(e.getPersistentDataContainer().get(modelKey, PersistentDataType.STRING))) out.add(e);
        }
        return out;
    }

    public void retag(Entity e, Block newBlock) {
        e.getPersistentDataContainer().set(modelKey, PersistentDataType.STRING, tagOf(newBlock));
    }

    public void removeModels(Block b) {
        for (Entity e : findModels(b)) e.remove();
    }

    public boolean isModel(Entity e) {
        return e.getPersistentDataContainer().has(modelKey, PersistentDataType.STRING);
    }

    // ------------------------------------------------------------------ manning

    public boolean isGunner(Player p) {
        return gunners.containsKey(p.getUniqueId());
    }

    public boolean isGunnerSeat(UUID seatId) {
        for (Gunner g : gunners.values()) if (g.seat.equals(seatId)) return true;
        return false;
    }

    public void man(Player p, Block cannon) {
        if (plugin.seats().isOccupied(cannon)) {
            p.sendActionBar(Component.text("Someone is already on this cannon.", NamedTextColor.YELLOW));
            return;
        }
        List<Entity> models = findModels(cannon);
        if (models.isEmpty()) spawnModels(cannon, p.getLocation().getYaw());
        models = findModels(cannon);
        ItemDisplay seat = plugin.seats().sit(p, cannon, 1.0);
        List<UUID> ids = new ArrayList<>();
        for (Entity e : models) ids.add(e.getUniqueId());
        Gunner g = new Gunner(p.getUniqueId(), seat.getUniqueId(), ids);
        g.cooldown = 20; // the click that sat you down doesn't fire - 1 second to get settled
        gunners.put(p.getUniqueId(), g);
        p.playSound(p.getLocation(), Sound.BLOCK_IRON_TRAPDOOR_CLOSE, 1f, 0.7f);
        p.sendMessage(MM.deserialize("<gray>⚓ You man the cannon. <white>Aim with your mouse</white> - follow the "
                + "<gold>dotted line</gold>. <white>Click</white> to fire, <white>Shift</white> to step away."));
    }

    /** Fire if this player is on a cannon. Returns true if they are a gunner. */
    public boolean tryFire(Player p) {
        Gunner g = gunners.get(p.getUniqueId());
        if (g == null) return false;
        if (g.ticks < 20) return true; // just sat down: ignore clicks for a moment (no surprise shot)
        int now = Bukkit.getCurrentTick();
        if (g.lastTry == now) return true; // one click can send two events - only count it once
        g.lastTry = now;
        if (g.cooldown > 0) {
            p.playSound(p.getLocation(), Sound.BLOCK_DISPENSER_FAIL, 0.6f, 1.4f);
            return true;
        }
        ItemStack ammo = findAmmo(p);
        if (ammo == null && p.getGameMode() != GameMode.CREATIVE) {
            p.sendActionBar(Component.text("No Cannonballs! Craft some (iron + gunpowder).", NamedTextColor.RED));
            p.playSound(p.getLocation(), Sound.BLOCK_DISPENSER_FAIL, 1f, 0.8f);
            g.cooldown = 10;
            return true;
        }
        ItemStack visualItem = ammo != null ? ammo.clone() : createOwnCannonball(1);
        visualItem.setAmount(1);
        if (ammo != null && p.getGameMode() != GameMode.CREATIVE) ammo.setAmount(ammo.getAmount() - 1);

        Location eye = aimOrigin(g, p);
        Vector dir = aimDir(p);
        Vector muzzle = eye.toVector().add(dir.clone().multiply(1.35));
        kickModels(g, p);
        ShipManager.Ship own = plugin.ships().sailingShipWithSeat(g.seat);
        java.util.Set<Pos> ownBlocks = java.util.Set.of();
        if (own == null) {
            // cannon on an anchored ship: never damage your own ship
            org.bukkit.Location seatBlock = plugin.seats().blockOf(g.seat);
            if (seatBlock != null) {
                ShipScanner.Result r = ShipScanner.scan(seatBlock.getBlock(), plugin.ships().maxSize());
                if (r.hasEngine) ownBlocks = r.blocks;
            }
        }
        launch(p.getWorld(), muzzle, dir, p.getUniqueId(), visualItem, own, ownBlocks);
        g.cooldown = (int) cfg("reload-ticks", 50);
        return true;
    }

    /** BOOM: flash, smoke and sound at the muzzle, then the ball flies. */
    private void launch(World w, Vector muzzle, Vector dir, UUID shooter, ItemStack visualItem,
                        ShipManager.Ship own, java.util.Set<Pos> ownBlocks) {
        Location mz = muzzle.toLocation(w);
        w.spawnParticle(Particle.EXPLOSION, mz, 1);
        w.spawnParticle(Particle.FLAME, mz, 12, 0.15, 0.15, 0.15, 0.06);
        w.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, mz, 14, 0.25, 0.25, 0.25, 0.04);
        w.spawnParticle(Particle.LARGE_SMOKE, mz, 20, 0.3, 0.3, 0.3, 0.05);
        w.playSound(mz, Sound.ENTITY_GENERIC_EXPLODE, 2.5f, 1.25f);
        w.playSound(mz, Sound.ENTITY_FIREWORK_ROCKET_BLAST_FAR, 2f, 0.6f);
        w.playSound(mz, Sound.ITEM_CROSSBOW_SHOOT, 1.5f, 0.6f);

        ItemDisplay visual = w.spawn(mz, ItemDisplay.class, e -> {
            e.setItemStack(visualItem);
            e.setPersistent(false);
            e.setTeleportDuration(1);
            float s = (float) cfg("ball-size", 0.7);
            e.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(s, s, s), new Quaternionf()));
            e.setBrightness(new Display.Brightness(15, 15));
        });
        Ball ball = new Ball(shooter, visual, own, muzzle, dir.clone().multiply(cfg("ball-speed", 2.4)));
        ball.ownBlocks = ownBlocks;
        balls.add(ball);
    }

    // ------------------------------------------------------------------ cannons used by other plugins (see api.CrimboatApi)

    /** Puts a cannon (tagged barrel plus its barrel model) on this block. */
    public void placeCannon(Block b, float yaw) {
        removeModels(b);
        b.setType(BLOCK, false);
        BlockState st = b.getState();
        setCannonTag(st, true);
        st.update(true, false);
        spawnModels(b, yaw);
    }

    /** Takes a cannon away again (the block becomes air). */
    public void removeCannon(Block b) {
        removeModels(b);
        if (isCannonBlock(b)) b.setType(Material.AIR, false);
    }

    private Location barrelOrigin(Block cannon) {
        return modelSpot(cannon).add(0, 0.15, 0);
    }

    /**
     * Direction to fire from this cannon so the ball lands on the target, following the real flight of the ball
     * (speed, gravity and drag from the config, the same pitch limits a gunner has). Null when out of range.
     */
    public Vector aimAt(Block cannon, Location target) {
        Location from = barrelOrigin(cannon);
        if (target.getWorld() != from.getWorld()) return null;
        double dx = target.getX() - from.getX(), dz = target.getZ() - from.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist < 2) return null;
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        double speed = cfg("ball-speed", 2.4), g = cfg("gravity", 0.045), drag = cfg("drag", 0.995);
        Vector best = null;
        double bestErr = Double.MAX_VALUE;
        for (float pitch = (float) cfg("min-pitch", -40); pitch <= cfg("max-pitch", 20); pitch += 0.5f) {
            Location l = from.clone();
            l.setYaw(yaw);
            l.setPitch(pitch);
            Vector dir = l.getDirection();
            Vector pos = from.toVector().add(dir.clone().multiply(1.35));
            Vector v = dir.clone().multiply(speed);
            for (int i = 0; i < 200; i++) {
                Vector next = pos.clone().add(v);
                if (next.getY() <= target.getY() && v.getY() < 0) {
                    double hx = next.getX() - target.getX(), hz = next.getZ() - target.getZ();
                    double err = Math.sqrt(hx * hx + hz * hz);
                    if (err < bestErr) {
                        bestErr = err;
                        best = dir;
                    }
                    break;
                }
                pos = next;
                v.setY(v.getY() - g).multiply(drag);
            }
        }
        return bestErr <= Math.max(3, dist * 0.08) ? best : null;
    }

    /**
     * Fires this cannon without a gunner and without ammo (e.g. a mob crew). The barrel model turns to the aim
     * first. The ball never damages the blocks in ownBlocks (the ship or fort it was fired from).
     */
    public boolean fireFrom(Block cannon, Vector dir, Entity shooter, java.util.Set<Pos> ownBlocks) {
        if (!isCannonBlock(cannon) || dir == null) return false;
        Vector d = dir.clone().normalize();
        Location model = modelSpot(cannon);
        model.setDirection(d);
        List<Entity> models = findModels(cannon);
        if (models.isEmpty()) {
            spawnModels(cannon, model.getYaw());
            models = findModels(cannon);
        }
        for (Entity e : models) e.teleport(model);
        launch(cannon.getWorld(), barrelOrigin(cannon).toVector().add(d.clone().multiply(1.35)), d,
                shooter == null ? new UUID(0, 0) : shooter.getUniqueId(), createOwnCannonball(1), null,
                ownBlocks == null ? java.util.Set.of() : ownBlocks);
        return true;
    }

    private ItemStack findAmmo(Player p) {
        ItemStack off = p.getInventory().getItemInOffHand();
        if (isCannonball(off)) return off;
        for (ItemStack it : p.getInventory().getContents()) if (isCannonball(it)) return it;
        return null;
    }

    private int countAmmo(Player p) {
        int n = 0;
        for (ItemStack it : p.getInventory().getContents()) if (isCannonball(it)) n += it.getAmount();
        return n;
    }

    private Location aimOrigin(Gunner g, Player p) {
        Entity seat = Bukkit.getEntity(g.seat);
        Location base = seat != null ? seat.getLocation() : p.getLocation();
        return base.clone().add(0, 0.15, 0);
    }

    private Vector aimDir(Player p) {
        float pitch = Math.max((float) cfg("min-pitch", -40), Math.min((float) cfg("max-pitch", 20), p.getLocation().getPitch()));
        Location l = p.getLocation().clone();
        l.setPitch(pitch);
        return l.getDirection().normalize();
    }

    private void kickModels(Gunner g, Player p) {
        // the barrel jumps back a little then settles (the next tick puts it back)
        for (UUID id : g.models) {
            Entity e = Bukkit.getEntity(id);
            if (e == null) continue;
            Location l = e.getLocation().clone().add(aimDir(p).multiply(-0.25));
            l.setPitch(l.getPitch() - 6f);
            e.teleport(l);
        }
    }

    // ------------------------------------------------------------------ tick

    private void tick() {
        // gunners: aim the barrel, show the dotted line
        for (Iterator<Gunner> it = gunners.values().iterator(); it.hasNext(); ) {
            Gunner g = it.next();
            Player p = Bukkit.getPlayer(g.player);
            Entity seat = Bukkit.getEntity(g.seat);
            if (p == null || seat == null || !seat.isValid() || p.getVehicle() != seat) {
                it.remove();
                continue;
            }
            g.ticks++;
            if (g.cooldown > 0) g.cooldown--;

            Location origin = aimOrigin(g, p);
            Vector dir = aimDir(p);
            Location model = origin.clone();
            model.setDirection(dir);
            for (UUID id : g.models) {
                Entity e = Bukkit.getEntity(id);
                if (e != null && (g.cooldown < (int) cfg("reload-ticks", 50) - 4)) e.teleport(model);
            }
            if (g.ticks % 2 == 0) drawPreview(p, origin.toVector().add(dir.clone().multiply(1.35)), dir.clone().multiply(cfg("ball-speed", 2.4)));
            if (g.ticks % 4 == 0) {
                int ammo = countAmmo(p);
                Component bar = g.cooldown > 0
                        ? Component.text("⟳ Reloading " + String.format("%.1fs", g.cooldown / 20.0), NamedTextColor.GRAY)
                        : Component.text("✔ READY - click to fire", NamedTextColor.GREEN);
                p.sendActionBar(bar.append(Component.text("   |   Cannonballs: " + (p.getGameMode() == GameMode.CREATIVE ? "∞" : ammo),
                        ammo > 0 || p.getGameMode() == GameMode.CREATIVE ? NamedTextColor.WHITE : NamedTextColor.RED)));
            }
        }

        // flying cannonballs
        for (Iterator<Ball> it = balls.iterator(); it.hasNext(); ) {
            Ball b = it.next();
            if (!b.visual.isValid() || ++b.life > 200 || stepBall(b)) {
                b.visual.remove();
                it.remove();
            }
        }
    }

    /** Dotted arc showing where the cannonball will go (only the gunner sees it). */
    private void drawPreview(Player p, Vector start, Vector vel) {
        World w = p.getWorld();
        Vector pos = start.clone();
        Vector v = vel.clone();
        double g = cfg("gravity", 0.045), drag = cfg("drag", 0.995);
        Particle.DustOptions dot = new Particle.DustOptions(Color.fromRGB(0xFFB347), 0.7f);
        for (int i = 0; i < 90; i++) {
            Vector next = pos.clone().add(v);
            RayTraceResult hit = w.rayTraceBlocks(pos.toLocation(w), v.clone().normalize(), v.length(),
                    FluidCollisionMode.ALWAYS, true);
            if (hit != null) {
                Location h = hit.getHitPosition().toLocation(w);
                Particle.DustOptions mark = new Particle.DustOptions(Color.fromRGB(0xFF3B3B), 1.3f);
                for (int k = 0; k < 10; k++) {
                    double a = Math.PI * 2 * k / 10;
                    p.spawnParticle(Particle.DUST, h.clone().add(Math.cos(a) * 0.8, 0.15, Math.sin(a) * 0.8), 1, 0, 0, 0, 0, mark);
                }
                return;
            }
            if (i % 2 == 1) p.spawnParticle(Particle.DUST, next.toLocation(w), 1, 0, 0, 0, 0, dot);
            pos = next;
            v.setY(v.getY() - g).multiply(drag);
        }
    }

    /** Moves a ball one tick. Returns true when it is done (hit something). */
    private boolean stepBall(Ball b) {
        World w = b.visual.getWorld();
        Vector from = b.pos.clone();
        Vector to = from.clone().add(b.vel);
        double len = b.vel.length();
        Vector dir = b.vel.clone().normalize();

        // 1) a sailing ship (its blocks are models, so check the cells it fills)
        int samples = Math.max(1, (int) Math.ceil(len / 0.4));
        for (int i = 1; i <= samples; i++) {
            Vector pt = from.clone().add(dir.clone().multiply(len * i / samples));
            ShipManager.Ship hitShip = plugin.ships().sailingShipAtCell(w, pt.getBlockX(), pt.getBlockY(), pt.getBlockZ());
            if (hitShip != null && hitShip != b.ownSailing) {
                Location at = pt.toLocation(w);
                impact(b, at, false);
                plugin.combat().hitSailing(hitShip, (int) cfg("ship-damage", 1), at);
                return true;
            }
        }

        // 2) blocks / water
        RayTraceResult bh = w.rayTraceBlocks(from.toLocation(w), dir, len, FluidCollisionMode.ALWAYS, true);
        // 3) living things
        RayTraceResult eh = w.rayTraceEntities(from.toLocation(w), dir, len, 0.35, e ->
                e instanceof LivingEntity && !e.getUniqueId().equals(b.shooter) && !plugin.ships().isShipPart(e)
                        && !plugin.seats().isSeat(e) && !isModel(e) && !(e instanceof Player pl && pl.getGameMode() == GameMode.SPECTATOR));
        double bd = bh == null ? Double.MAX_VALUE : bh.getHitPosition().distance(from);
        double ed = eh == null ? Double.MAX_VALUE : eh.getHitPosition().distance(from);

        if (eh != null && ed <= bd) {
            Location at = eh.getHitPosition().toLocation(w);
            impact(b, at, false);
            return true;
        }
        if (bh != null) {
            Location at = bh.getHitPosition().toLocation(w);
            Block hb = bh.getHitBlock();
            if (hb != null && (hb.getType() == Material.WATER || Materials.isWaterLike(hb.getType()))) {
                w.spawnParticle(Particle.SPLASH, at, 60, 0.6, 0.2, 0.6, 0.3);
                w.spawnParticle(Particle.BUBBLE_POP, at, 20, 0.5, 0.2, 0.5, 0.05);
                w.spawnParticle(Particle.CLOUD, at, 6, 0.4, 0.4, 0.4, 0.02);
                w.playSound(at, Sound.ENTITY_GENERIC_SPLASH, 2f, 0.7f);
                w.playSound(at, Sound.ENTITY_PLAYER_SPLASH_HIGH_SPEED, 1.5f, 0.8f);
                return true;
            }
            if (hb != null && b.ownBlocks.contains(Pos.of(hb))) {
                // hit your own ship: just a dull thud
                w.spawnParticle(Particle.LARGE_SMOKE, at, 8, 0.2, 0.2, 0.2, 0.02);
                w.playSound(at, Sound.BLOCK_WOOD_HIT, 1.5f, 0.6f);
                return true;
            }
            impact(b, at, true);
            if (hb != null) {
                // other plugins may handle this block themselves (their own ships or forts)
                com.aquaticsmp.test.api.ShipCannonHitEvent hit = new com.aquaticsmp.test.api.ShipCannonHitEvent(
                        hb, at, Bukkit.getEntity(b.shooter), (int) cfg("ship-damage", 1));
                Bukkit.getPluginManager().callEvent(hit);
                if (!hit.isCancelled() && hit.getDamage() > 0) plugin.combat().hitBlock(hb, hit.getDamage(), at);
            }
            return true;
        }

        // keep flying
        b.pos = to;
        b.vel.setY(b.vel.getY() - cfg("gravity", 0.045)).multiply(cfg("drag", 0.995));
        Location l = to.toLocation(w);
        b.visual.teleport(l);
        w.spawnParticle(Particle.LARGE_SMOKE, l, 2, 0.05, 0.05, 0.05, 0.01);
        if (b.life % 2 == 0) w.spawnParticle(Particle.SMOKE, l, 3, 0.08, 0.08, 0.08, 0.01);
        return false;
    }

    /** Explosion: hurts nearby players/mobs (PvP rules from other plugins still apply), never breaks blocks. */
    private void impact(Ball b, Location at, boolean onBlock) {
        World w = at.getWorld();
        w.spawnParticle(Particle.EXPLOSION_EMITTER, at, 1);
        w.spawnParticle(Particle.LARGE_SMOKE, at, 25, 0.6, 0.6, 0.6, 0.05);
        w.spawnParticle(Particle.FLAME, at, 15, 0.4, 0.4, 0.4, 0.05);
        w.playSound(at, Sound.ENTITY_GENERIC_EXPLODE, 3f, 0.9f);
        double radius = cfg("explosion-radius", 3.0);
        double damage = cfg("player-damage", 8.0);
        Entity shooter = Bukkit.getEntity(b.shooter); // a player, or a mob firing through the API
        for (Entity e : w.getNearbyEntities(at, radius, radius, radius)) {
            if (!(e instanceof LivingEntity le) || plugin.ships().isShipPart(e) || isModel(e)) continue;
            double d = e.getLocation().distance(at);
            if (d > radius) continue;
            double amount = damage * (1.0 - d / (radius + 0.5));
            if (shooter != null && !shooter.equals(e)) le.damage(amount, shooter);
            else le.damage(amount);
            le.setVelocity(le.getVelocity().add(e.getLocation().toVector().subtract(at.toVector()).normalize()
                    .multiply(0.6).setY(0.35)));
        }
    }
}
