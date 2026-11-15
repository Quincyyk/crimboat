package com.aquaticsmp.test;

import io.papermc.paper.entity.TeleportFlag;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Input;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.Sign;
import org.bukkit.block.TileState;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.block.data.type.Slab;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.block.sign.Side;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.Shulker;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Runs ships that have a captain.
 *
 * DOCKED  = ship is real blocks.
 * SAILING = ship blocks are swapped for display entities that glide smoothly,
 *           then turned back into blocks when the ship stops (anchors).
 */
public final class ShipManager {

    public enum State { DOCKED, SAILING }

    /** A block taken from the ship, stored relative to the captain's chair. */
    record Captured(int ox, int oy, int oz, BlockData data, ItemStack[] contents,
                    Component[] front, Component[] back, int engine, boolean tile,
                    boolean cannon, int hp) {}

    /** One moving piece of the ship while sailing. */
    static final class Part {
        final Captured cap;
        final boolean centered; // item display (chests etc.) vs block display
        final float faceDeg;
        final boolean bottom;
        Display display;

        Part(Captured cap, boolean centered, float faceDeg, boolean bottom) {
            this.cap = cap;
            this.centered = centered;
            this.faceDeg = faceDeg;
            this.bottom = bottom;
        }
    }

    /** A seat that rides along with the ship. */
    record SeatRef(UUID id, int ox, int oy, int oz, double dy) {}

    /** A cannon barrel model that rides along with the ship (lx/ly/lz relative to the captain's chair). */
    record AttachRef(UUID id, double lx, double ly, double lz, int cox, int coy, int coz) {}

    /** Invisible solid box (shulker) so people can stand and walk on the deck while sailing. */
    static final class Collider {
        final int ox, oz;
        final double bottom; // local y of the box bottom
        ItemDisplay carrier;
        Shulker shulker;

        Collider(int ox, int oz, double bottom) {
            this.ox = ox;
            this.oz = oz;
            this.bottom = bottom;
        }
    }

    public static final class Ship {
        final World world;
        final UUID driver;
        final UUID driverSeat;
        State state = State.DOCKED;

        // docked
        Set<Pos> blocks;
        int waterTop;
        Pos seatPos;

        // sailing
        ItemDisplay root;
        List<Part> parts;
        List<SeatRef> seatRefs;
        Map<Long, List<Double>> surface;
        List<Collider> colliders;
        Set<Long> cells;
        double rx, ry, rz, theta, speed, omega;
        double shownTheta;
        double fx, fz;
        double radius;
        int idle;

        // ui
        String warning;
        int warningTicks;
        int ticks;
        int launchCooldown;
        int tier = 1;
        int hp = -1;
        double bob;                       // current up/down bob on the water
        double sternX, sternZ, bowX, bowZ; // local spots for wake and bow spray
        double engineX = Double.NaN, engineY, engineZ; // local engine spot for smoke
        int lowY;
        List<AttachRef> attachRefs;
        int lastNagTick = -1000;
        String lastNagNames = "";

        Ship(World world, Set<Pos> blocks, int waterTop, Pos seat, UUID driver, UUID seatEntity) {
            this.world = world;
            this.blocks = blocks;
            this.waterTop = waterTop;
            this.seatPos = seat;
            this.driver = driver;
            this.driverSeat = seatEntity;
        }
    }

    private final TestPlugin plugin;
    private final List<Ship> ships = new ArrayList<>();
    private final NamespacedKey prevModeKey;
    private final NamespacedKey partKey;
    private BukkitTask task;

    public ShipManager(TestPlugin plugin) {
        this.plugin = plugin;
        this.prevModeKey = new NamespacedKey(plugin, "prev_gamemode");
        this.partKey = new NamespacedKey(plugin, "ship_part");
    }

    /** True for any entity this plugin spawned for a moving ship (models, colliders, cameras). */
    public boolean isShipPart(Entity e) {
        return e.getPersistentDataContainer().has(partKey, PersistentDataType.BYTE);
    }

    private void tag(Entity e) {
        e.getPersistentDataContainer().set(partKey, PersistentDataType.BYTE, (byte) 1);
    }

    /** Remove leftovers from a crash. */
    public void cleanupLeftovers() {
        for (World w : Bukkit.getWorlds()) {
            for (Entity e : w.getEntities()) {
                if (isShipPart(e)) e.remove();
            }
        }
    }

    public void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    /** Server stopping: bring everyone back and anchor every ship. */
    public void stopAll() {
        if (task != null) task.cancel();
        for (Ship s : new ArrayList<>(ships)) end(s);
        ships.clear();
    }

    public int maxSize() {
        return Engine.maxShipSizeAny();
    }

    // ------------------------------------------------------------------ lookup

    public Ship shipAt(World w, Pos p) {
        for (Ship s : ships) {
            if (s.state == State.DOCKED && s.world.equals(w) && s.blocks.contains(p)) return s;
        }
        return null;
    }

    public Ship shipOfDriver(UUID player) {
        for (Ship s : ships) if (s.driver.equals(player)) return s;
        return null;
    }

    /** Sailing ship that carries this seat, or null. */
    public Ship sailingShipWithSeat(UUID seatId) {
        for (Ship s : ships) {
            if (s.state != State.SAILING || s.seatRefs == null) continue;
            for (SeatRef r : s.seatRefs) if (r.id().equals(seatId)) return s;
        }
        return null;
    }

    /** The sailing ship filling this block cell right now, or null. */
    public Ship sailingShipAtCell(World w, int x, int y, int z) {
        long k = cellKey(x, y, z);
        for (Ship s : ships) {
            if (s.state == State.SAILING && s.cells != null && s.world.equals(w) && s.cells.contains(k)) return s;
        }
        return null;
    }

    /** Ship got sunk while anchored: just forget the captain session. */
    public void endWithoutAnchor(Ship s) {
        ships.remove(s);
    }

    /** Ship's health hit zero while it had a captain. */
    public void sinkSession(Ship s, Location at) {
        if (s.state == State.SAILING) dock(s);
        ships.remove(s);
        Set<Pos> b = s.blocks;
        plugin.combat().sink(s.world, b, s.waterTop, at);
    }

    public void forgetSeat(UUID seatId) {
        for (Ship s : ships) if (s.seatRefs != null) s.seatRefs.removeIf(r -> r.id().equals(seatId));
    }


    // ------------------------------------------------------------------ start / stop

    public void tryStartDriving(Player p, Block stair, Entity seat) {
        if (shipAt(stair.getWorld(), Pos.of(stair)) != null) {
            p.sendActionBar(Component.text("This ship already has a captain.", NamedTextColor.YELLOW));
            return;
        }
        ShipScanner.Result r = ShipScanner.scan(stair, maxSize());
        if (!r.hasEngine) return; // just a normal chair
        if (r.status != ShipScanner.Status.OK) {
            p.sendMessage(Component.text(r.describe(maxSize()), NamedTextColor.RED));
            return;
        }
        // other plugins may keep people off ships that aren't theirs (crews, claims...)
        Set<Block> shipBlocks = new java.util.HashSet<>();
        Block engine = null;
        for (Pos pos : r.blocks) {
            Block b = pos.block(stair.getWorld());
            shipBlocks.add(b);
            if (Engine.blockTier(b) == r.tier && engine == null) engine = b;
        }
        com.aquaticsmp.test.api.ShipSailEvent sail = new com.aquaticsmp.test.api.ShipSailEvent(p, stair, engine, shipBlocks);
        Bukkit.getPluginManager().callEvent(sail);
        if (sail.isCancelled()) {
            if (sail.getDenyMessage() != null) p.sendMessage(Component.text(sail.getDenyMessage(), NamedTextColor.RED));
            return;
        }
        Ship ship = new Ship(stair.getWorld(), r.blocks, r.waterTop, Pos.of(stair), p.getUniqueId(), seat.getUniqueId());
        ship.tier = Math.max(1, r.tier);
        ships.add(ship);
        p.sendMessage(Component.text(r.describe(maxSize()), NamedTextColor.GREEN));
        p.sendMessage(Component.text("W/S = sail, A/D = steer, Shift = leave the helm. "
                + "Everyone on board must be seated to sail.", NamedTextColor.GRAY));
    }

    /** Captain leaves: anchor the ship and end the session. */
    public void stopDriving(Ship s) {
        end(s);
        Player p = Bukkit.getPlayer(s.driver);
        if (p != null) p.sendActionBar(Component.text("Ship anchored. You left the helm.", NamedTextColor.GRAY));
    }

    private void end(Ship s) {
        if (s.state == State.SAILING) dock(s);
        ships.remove(s);
    }

    // ------------------------------------------------------------------ config helpers

    /** How many ticks the game blends each movement over (higher = smoother, slightly more delay). */
    private int smoothTicks() {
        return Math.max(1, Math.min(5, plugin.getConfig().getInt("smoothing-ticks", 3)));
    }

    private double cfg(String k, double def) {
        return plugin.getConfig().getDouble(k, def);
    }

    private int size(Ship s) {
        return s.state == State.SAILING ? s.parts.size() : s.blocks.size();
    }

    // ------------------------------------------------------------------ tick

    private void tick() {
        for (Ship s : new ArrayList<>(ships)) {
            Player d = Bukkit.getPlayer(s.driver);
            Entity seat = Bukkit.getEntity(s.driverSeat);
            boolean seated = d != null && seat != null && seat.isValid() && d.getVehicle() == seat;
            if (d == null || !seated) {
                end(s);
                continue;
            }
            s.ticks++;
            if (s.warningTicks > 0) s.warningTicks--;
            if (s.launchCooldown > 0) s.launchCooldown--;

            Input in = d.getCurrentInput();


            boolean move = in.isForward() != in.isBackward();
            boolean steer = in.isLeft() != in.isRight();

            if (s.state == State.DOCKED) {
                if ((move || steer) && s.launchCooldown <= 0) {
                    String err = launch(s);
                    if (err != null) {
                        warn(s, err);
                        s.launchCooldown = 20;
                    } else if (s.state == State.DOCKED) {
                        s.launchCooldown = 10; // waiting for crew to sit
                    }
                }
            } else {
                sail(s, in);
            }


            if (s.warningTicks > 0 && s.warning != null) {
                d.sendActionBar(Component.text("⚠ " + s.warning, NamedTextColor.RED));
            } else if (s.ticks % 5 == 0) {
                Component bar = Engine.display(s.tier)
                        .append(Component.text("  |  ", NamedTextColor.DARK_GRAY))
                        .append(s.state == State.SAILING
                                ? Component.text(gauge(Math.abs(s.speed) / Math.max(0.01, Engine.speedSmall(s.tier))) + " ",
                                        Engine.color(s.tier))
                                    .append(Component.text(String.format("%.1f blocks/s", Math.abs(s.speed) * 20), NamedTextColor.WHITE))
                                : Component.text("⚓ Anchored", NamedTextColor.GRAY))
                        .append(Component.text("  |  " + size(s) + " blocks", NamedTextColor.GRAY));
                d.sendActionBar(bar);
            }
        }
    }

    private void warn(Ship s, String msg) {
        if (msg == null) return;
        s.warning = msg;
        s.warningTicks = 20;
    }

    // ------------------------------------------------------------------ math

    /** Rotate (x,z) clockwise (seen from above) by deg. */
    static double[] rot(double x, double z, double deg) {
        double r = Math.toRadians(deg), c = Math.cos(r), sn = Math.sin(r);
        return new double[]{x * c - z * sn, x * sn + z * c};
    }

    private static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    private static boolean isCentered(Material m) {
        return m == Material.CHEST || m == Material.TRAPPED_CHEST || m == Material.ENDER_CHEST
                || m.name().endsWith("SHULKER_BOX");
    }

    private static float faceDeg(BlockData d) {
        if (!(d instanceof Directional dir)) return 0f;
        return switch (dir.getFacing()) {
            case WEST -> 90f;
            case NORTH -> 180f;
            case EAST -> 270f;
            default -> 0f;
        };
    }

    // ------------------------------------------------------------------ launch (blocks -> displays)

    private String launch(Ship s) {
        World w = s.world;
        Pos piv = s.seatPos;
        Block seatBlock = piv.block(w);
        if (!(seatBlock.getBlockData() instanceof Stairs st)) return "The pirate chair is gone!";

        ShipScanner.Result r = ShipScanner.scan(seatBlock, maxSize());
        if (r.status != ShipScanner.Status.OK) return r.describe(maxSize());
        s.blocks = r.blocks;
        s.waterTop = r.waterTop;
        s.tier = Math.max(1, r.tier);

        if (requireSeated()) {
            List<Player> standing = standingOnBlocks(s);
            if (!standing.isEmpty()) {
                nagStanding(s, standing);
                return null; // just don't set sail yet
            }
        }

        BlockFace f = st.getFacing().getOppositeFace();
        s.fx = f.getModX();
        s.fz = f.getModZ();

        // capture blocks
        List<Captured> caps = new ArrayList<>();
        for (Pos p : s.blocks) {
            Block b = p.block(w);
            BlockState bs = b.getState();
            ItemStack[] contents = null;
            Component[] front = null, back = null;
            if (bs instanceof Container c) {
                ItemStack[] raw = c.getSnapshotInventory().getContents();
                contents = new ItemStack[raw.length];
                for (int i = 0; i < raw.length; i++) contents[i] = raw[i] == null ? null : raw[i].clone();
            }
            if (bs instanceof Sign sign) {
                front = new Component[4];
                back = new Component[4];
                for (int i = 0; i < 4; i++) {
                    front[i] = sign.getSide(Side.FRONT).line(i);
                    back[i] = sign.getSide(Side.BACK).line(i);
                }
            }
            int engine = Engine.blockTier(b);
            boolean cannon = Cannons.isCannonBlock(b);
            int hp = engine > 0 ? Engine.getHp(b) : -1;
            caps.add(new Captured(p.x() - piv.x(), p.y() - piv.y(), p.z() - piv.z(), b.getBlockData(),
                    contents, front, back, engine, bs instanceof TileState, cannon, hp));
        }

        // seats that ride along
        s.seatRefs = new ArrayList<>();
        for (Map.Entry<UUID, Location> e : plugin.seats().allSeats().entrySet()) {
            Location l = e.getValue();
            if (!w.equals(l.getWorld())) continue;
            Pos sp = Pos.of(l.getBlock());
            if (s.blocks.contains(sp)) {
                Entity se = Bukkit.getEntity(e.getKey());
                double dy = se != null ? se.getLocation().getY() - sp.y() : 0.5;
                s.seatRefs.add(new SeatRef(e.getKey(), sp.x() - piv.x(), sp.y() - piv.y(), sp.z() - piv.z(), dy));
            }
        }

        // ship health (saved on the engine)
        s.hp = -1;
        for (Captured c : caps) if (c.engine() > 0 && c.hp() >= 0) s.hp = c.hp();

        // cannon barrels ride along
        s.attachRefs = new ArrayList<>();
        for (Captured c : caps) {
            if (!c.cannon()) continue;
            Block cb = w.getBlockAt(piv.x() + c.ox(), piv.y() + c.oy(), piv.z() + c.oz());
            for (Entity m : plugin.cannons().findModels(cb)) {
                Location ml = m.getLocation();
                if (m instanceof Display md) md.setTeleportDuration(smoothTicks());
                s.attachRefs.add(new AttachRef(m.getUniqueId(), ml.getX() - (piv.x() + 0.5), ml.getY() - piv.y(),
                        ml.getZ() - (piv.z() + 0.5), c.ox(), c.oy(), c.oz()));
            }
        }

        // deck surface map (for people standing on deck) + bottom blocks
        Set<Pos> local = new HashSet<>();
        for (Captured c : caps) local.add(new Pos(c.ox(), c.oy(), c.oz()));
        s.surface = new HashMap<>();
        s.parts = new ArrayList<>();
        double radius = 0;
        for (Captured c : caps) {
            double top = c.oy() + 1.0;
            if (c.data() instanceof Slab sl && sl.getType() == Slab.Type.BOTTOM) top = c.oy() + 0.5;
            s.surface.computeIfAbsent(key(c.ox(), c.oz()), k -> new ArrayList<>()).add(top);
            boolean bottom = !local.contains(new Pos(c.ox(), c.oy() - 1, c.oz()));
            s.parts.add(new Part(c, isCentered(c.data().getMaterial()), faceDeg(c.data()), bottom));
            radius = Math.max(radius, Math.sqrt(c.ox() * c.ox() + c.oz() * c.oz()));
        }
        s.radius = radius + 1.5;

        // where the wake, bow spray and engine smoke come from
        double minDot = Double.MAX_VALUE, maxDot = -Double.MAX_VALUE;
        s.lowY = Integer.MAX_VALUE;
        for (Captured c : caps) s.lowY = Math.min(s.lowY, c.oy());
        for (Captured c : caps) {
            if (c.oy() != s.lowY) continue;
            double dot = c.ox() * s.fx + c.oz() * s.fz;
            if (dot < minDot) {
                minDot = dot;
                s.sternX = c.ox();
                s.sternZ = c.oz();
            }
            if (dot > maxDot) {
                maxDot = dot;
                s.bowX = c.ox();
                s.bowZ = c.oz();
            }
        }
        s.engineX = Double.NaN;
        for (Captured c : caps) {
            if (c.engine() > 0) {
                s.engineX = c.ox();
                s.engineY = c.oy();
                s.engineZ = c.oz();
                break;
            }
        }

        // walkable surfaces: every block with open space above it, plus railings/walls
        s.colliders = new ArrayList<>();
        int maxColliders = plugin.getConfig().getInt("max-deck-colliders", 600);
        List<Collider> sides = new ArrayList<>();
        for (Captured c : caps) {
            Pos above = new Pos(c.ox(), c.oy() + 1, c.oz());
            double top = c.data() instanceof Slab sl && sl.getType() == Slab.Type.BOTTOM ? c.oy() + 0.5 : c.oy() + 1.0;
            if (!local.contains(above)) {
                s.colliders.add(new Collider(c.ox(), c.oz(), top - 1.0));
            } else {
                boolean openSide = !local.contains(new Pos(c.ox() + 1, c.oy(), c.oz()))
                        || !local.contains(new Pos(c.ox() - 1, c.oy(), c.oz()))
                        || !local.contains(new Pos(c.ox(), c.oy(), c.oz() + 1))
                        || !local.contains(new Pos(c.ox(), c.oy(), c.oz() - 1));
                if (openSide) sides.add(new Collider(c.ox(), c.oz(), c.oy()));
            }
        }
        s.colliders.addAll(sides);
        if (s.colliders.size() > maxColliders) s.colliders = new ArrayList<>(s.colliders.subList(0, maxColliders));

        // spawn the moving ship FIRST (so there is no flicker), then remove the blocks
        s.rx = piv.x() + 0.5;
        s.ry = piv.y();
        s.rz = piv.z() + 0.5;
        s.theta = 0;
        s.shownTheta = 0;
        s.speed = 0;
        s.omega = 0;
        s.idle = 0;
        Location rootLoc = new Location(w, s.rx, s.ry, s.rz, 0f, 0f);
        // Light: full sky light (the client still darkens it at night) + nearby torch light.
        Block lightBlock = seatBlock.getRelative(BlockFace.UP);
        int blockLight = Math.max(lightBlock.getLightFromBlocks(), seatBlock.getLightFromBlocks());
        Display.Brightness light = new Display.Brightness(blockLight, 15);

        int smooth = smoothTicks();
        s.root = w.spawn(rootLoc, ItemDisplay.class, e -> {
            e.setPersistent(false);
            e.setTeleportDuration(smooth);
            tag(e);
        });
        for (Part part : s.parts) {
            Transformation t = transform(part, 0);
            if (part.centered) {
                part.display = w.spawn(rootLoc, ItemDisplay.class, e -> {
                    e.setItemStack(new ItemStack(part.cap.data().getMaterial()));
                    e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
                    setupPart(e, t, light);
                });
            } else {
                part.display = w.spawn(rootLoc, BlockDisplay.class, e -> {
                    e.setBlock(part.cap.data());
                    setupPart(e, t, light);
                });
            }
            s.root.addPassenger(part.display);
        }
        double colOffset = cfg("collider-y-offset", 0.0);
        for (Collider c : s.colliders) {
            Location cl = new Location(w, s.rx + c.ox, s.ry + c.bottom + colOffset, s.rz + c.oz, 0f, 0f);
            c.carrier = w.spawn(cl, ItemDisplay.class, e -> {
                e.setPersistent(false);
                e.setTeleportDuration(smooth);
                tag(e);
            });
            c.shulker = w.spawn(cl, Shulker.class, e -> {
                e.setPersistent(false);
                e.setAI(false);
                e.setInvisible(true);
                e.setSilent(true);
                e.setInvulnerable(true);
                e.setGravity(false);
                e.setCollidable(false);
                e.setRemoveWhenFarAway(false);
                e.setPeek(0f);
                tag(e);
            });
            c.carrier.addPassenger(c.shulker);
        }

        // now remove the real blocks (containers emptied first so nothing drops)
        for (Pos p : s.blocks) {
            BlockState bs = p.block(w).getState();
            if (bs instanceof Container con) con.getInventory().clear();
        }
        for (Pos p : s.blocks) {
            p.block(w).setType(p.y() <= s.waterTop ? Material.WATER : Material.AIR, false);
        }
        s.cells = new HashSet<>();
        for (Pos p : s.blocks) s.cells.add(cellKey(p.x(), p.y(), p.z()));
        for (SeatRef ref : s.seatRefs) {
            Entity se = Bukkit.getEntity(ref.id());
            if (se instanceof Display d) d.setTeleportDuration(smooth);
        }
        s.state = State.SAILING;
        return null;
    }

    private void setupPart(Display e, Transformation t, Display.Brightness light) {
        tag(e);
        e.setPersistent(false);
        e.setTransformation(t);
        e.setInterpolationDuration(1);
        e.setBrightness(light);
        e.setViewRange(2.0f);
    }

    private Transformation transform(Part p, double theta) {
        Captured c = p.cap;
        Vector3f tr;
        float ang;
        if (p.centered) {
            double[] w = rot(c.ox(), c.oz(), theta);
            tr = new Vector3f((float) w[0], c.oy() + 0.5f, (float) w[1]);
            ang = (float) (theta + p.faceDeg);
        } else {
            double[] w = rot(c.ox() - 0.5, c.oz() - 0.5, theta);
            tr = new Vector3f((float) w[0], c.oy(), (float) w[1]);
            ang = (float) theta;
        }
        Quaternionf q = new Quaternionf().rotateY((float) Math.toRadians(-ang));
        return new Transformation(tr, q, new Vector3f(1f, 1f, 1f), new Quaternionf());
    }

    // ------------------------------------------------------------------ crew must sit

    private boolean requireSeated() {
        return plugin.getConfig().getBoolean("crew-must-sit-to-sail", true);
    }

    /** Players standing (not seated) on an anchored ship. */
    private List<Player> standingOnBlocks(Ship s) {
        List<Player> out = new ArrayList<>();
        for (Player p : s.world.getPlayers()) {
            if (p.getVehicle() != null || p.getGameMode() == GameMode.SPECTATOR) continue;
            if (p.getUniqueId().equals(s.driver)) continue;
            Location l = p.getLocation();
            Pos at = new Pos(l.getBlockX(), l.getBlockY(), l.getBlockZ());
            Location u = l.clone().subtract(0, 0.2, 0);
            Pos below = new Pos(u.getBlockX(), u.getBlockY(), u.getBlockZ());
            Pos below2 = below.add(0, -1, 0);
            if (s.blocks.contains(at) || s.blocks.contains(below) || s.blocks.contains(below2)) out.add(p);
        }
        return out;
    }

    /** Players standing (not seated) on a sailing ship. */
    private List<Player> standingPlayers(Ship s) {
        List<Player> out = new ArrayList<>();
        for (Entity e : carry(s, s.rx, s.rz, s.theta).keySet()) {
            if (e instanceof Player p && !p.getUniqueId().equals(s.driver)) out.add(p);
        }
        return out;
    }

    private void nagStanding(Ship s, List<Player> standing) {
        warn(s, standing.size() + " crew not seated - can't sail yet!");
        StringBuilder names = new StringBuilder();
        for (int i = 0; i < standing.size(); i++) {
            if (i > 0) names.append(i == standing.size() - 1 ? " and " : ", ");
            names.append(standing.get(i).getName());
        }
        String n = names.toString();
        // Chat message to the captain: right away when it changes, then every 5 seconds.
        if (!n.equals(s.lastNagNames) || s.ticks - s.lastNagTick >= 100) {
            s.lastNagNames = n;
            s.lastNagTick = s.ticks;
            Player captain = Bukkit.getPlayer(s.driver);
            if (captain != null) {
                String who = standing.size() == 1 ? n + " is" : n + " are";
                captain.sendMessage(Component.text("⚓ " + who + " not seated! Tell them to sit down "
                        + "(on a slab or stair) before you can sail.", NamedTextColor.YELLOW));
            }
            for (Player p : standing) {
                p.sendMessage(Component.text("⚓ The captain wants to set sail - sit down on a slab or stair!",
                        NamedTextColor.YELLOW));
            }
        }
    }

    // ------------------------------------------------------------------ sailing

    /** 0 = small ship, 1 = biggest ship. */
    public double sizeT(int size) {
        int small = plugin.getConfig().getInt("small-ship-size", 30);
        int big = Math.max(small + 1, plugin.getConfig().getInt("big-ship-size", 1000));
        return Math.max(0, Math.min(1, (size - small) / (double) (big - small)));
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private void sail(Ship s, Input in) {
        double t = sizeT(s.parts.size());
        // Small ships are fast and nimble, big ships are slower - but always faster than a vanilla boat (~0.4).
        // Engine tier sets the speed: each tier is a bit faster. Tier 5 is fast even on huge ships.
        double max = Math.max(0.45, lerp(Engine.speedSmall(s.tier), Engine.speedBig(s.tier), t));
        double accel = lerp(cfg("acceleration-small-ship", 0.03), cfg("acceleration-big-ship", 0.012), t)
                * (0.8 + 0.1 * s.tier);
        double drag = cfg("drag", 0.015);
        double turn = lerp(cfg("turn-speed-small-ship", 4.0), cfg("turn-speed-big-ship", 1.5), t);
        double turnAccel = lerp(cfg("turn-acceleration-small-ship", 0.35), cfg("turn-acceleration-big-ship", 0.12), t);

        if (requireSeated()) {
            List<Player> standing = standingPlayers(s);
            if (!standing.isEmpty()) {
                s.speed = 0;
                s.omega = 0;
                nagStanding(s, standing);
                if (alignStep(s)) return;
                s.idle++;
                if (s.idle >= plugin.getConfig().getInt("anchor-after-idle-ticks", 40)) dock(s);
                return;
            }
        }

        boolean fwd = in.isForward() && !in.isBackward();
        boolean back = in.isBackward() && !in.isForward();
        // Eased: picks up speed quickly at first, then gently levels off at top speed,
        // and glides to a stop instead of braking hard.
        if (fwd) {
            double ease = Math.max(0.15, 1.0 - Math.max(0, s.speed) / max);
            s.speed = Math.min(max, s.speed + accel * ease + (s.speed < 0 ? drag : 0));
        } else if (back) {
            double rmax = max * 0.35;
            double ease = Math.max(0.15, 1.0 - Math.max(0, -s.speed) / rmax);
            s.speed = Math.max(-rmax, s.speed - accel * ease - (s.speed > 0 ? drag : 0));
        } else {
            double slow = drag * 0.5 + Math.abs(s.speed) * 0.04;
            if (s.speed > 0) s.speed = Math.max(0, s.speed - slow);
            else if (s.speed < 0) s.speed = Math.min(0, s.speed + slow);
        }

        // Smooth steering: the turn speeds up and slows down instead of snapping.
        double target = 0;
        if (in.isRight() && !in.isLeft()) target = turn;
        else if (in.isLeft() && !in.isRight()) target = -turn;
        if (s.omega < target) s.omega = Math.min(target, s.omega + turnAccel);
        else if (s.omega > target) s.omega = Math.max(target, s.omega - turnAccel);
        if (Math.abs(s.omega) < 1e-3) s.omega = 0;
        double dTheta = s.omega;

        if (dTheta == 0 && Math.abs(s.speed) < 1e-4) {
            s.speed = 0;
            if (alignStep(s)) return; // gently straighten up before anchoring
            s.idle++;
            if (s.idle >= plugin.getConfig().getInt("anchor-after-idle-ticks", 40)) {
                dock(s);
                warn(s, null);
            }
            return;
        }
        s.idle = 0;

        double nTheta = s.theta + dTheta;
        double[] fwNew = rot(s.fx, s.fz, nTheta);
        double[] fwOld = rot(s.fx, s.fz, s.theta);

        // try: move+turn, move only, turn only
        double[][] tries = {
                {s.rx + fwNew[0] * s.speed, s.rz + fwNew[1] * s.speed, nTheta},
                {s.rx + fwOld[0] * s.speed, s.rz + fwOld[1] * s.speed, s.theta},
                {s.rx, s.rz, nTheta}};
        String firstErr = null;
        Set<Long> cells = new HashSet<>();
        for (int i = 0; i < tries.length; i++) {
            double[] tr = tries[i];
            if (i == 1 && (dTheta == 0 || s.speed == 0)) continue;
            if (i == 2 && dTheta == 0) continue;
            String err = check(s, tr[0], tr[1], tr[2], cells);
            if (err == null) {
                if (i == 2) bump(s);
                if (i == 1) s.omega = 0;
                if (firstErr != null) warn(s, firstErr);
                s.cells = cells;
                applyPose(s, tr[0], tr[1], tr[2]);
                return;
            }
            if (firstErr == null) firstErr = err;
        }
        bump(s);
        s.omega = 0;
        warn(s, firstErr);
    }

    /**
     * When stopped, slowly turn the ship to the nearest straight angle and slide it onto
     * the block grid, so anchoring doesn't snap. Returns true while still moving.
     */
    private boolean alignStep(Ship s) {
        double target = Math.round(s.theta / 90.0) * 90.0;
        double dt = target - s.theta;
        double tx = Math.floor(s.rx) + 0.5, tz = Math.floor(s.rz) + 0.5;
        double dx = tx - s.rx, dz = tz - s.rz;
        if (Math.abs(dt) < 1e-3 && Math.abs(dx) < 1e-3 && Math.abs(dz) < 1e-3) return false;
        double maxTurn = cfg("anchor-straighten-speed", 1.5);
        double maxSlide = 0.04;
        double st = Math.max(-maxTurn, Math.min(maxTurn, dt));
        double sx = Math.max(-maxSlide, Math.min(maxSlide, dx));
        double sz = Math.max(-maxSlide, Math.min(maxSlide, dz));
        if (Math.abs(dt) < maxTurn) st = dt;
        if (Math.abs(dx) < maxSlide) sx = dx;
        if (Math.abs(dz) < maxSlide) sz = dz;
        Set<Long> cells = new HashSet<>();
        if (check(s, s.rx + sx, s.rz + sz, s.theta + st, cells) != null) return false; // blocked: anchor as-is
        s.cells = cells;
        s.idle = 0;
        applyPose(s, s.rx + sx, s.rz + sz, s.theta + st);
        return true;
    }

    /** Hit something solid: stop and bounce back a little, like bumping into a wall. */
    private void bump(Ship s) {
        s.speed = -s.speed * cfg("bump-bounce", 0.3);
        if (Math.abs(s.speed) < 0.02) s.speed = 0;
    }

    static long cellKey(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    private boolean hitsOtherShip(Ship s, long cell) {
        for (Ship o : ships) {
            if (o == s || o.state != State.SAILING || o.cells == null || !o.world.equals(s.world)) continue;
            if (o.cells.contains(cell)) return true;
        }
        return false;
    }

    /** Returns null if the ship can be at this pose, otherwise why not. Fills the cells it would use. */
    private String check(Ship s, double nrx, double nrz, double nTheta, Set<Long> out) {
        out.clear();
        World w = s.world;
        boolean floating = false;
        int baseY = (int) Math.floor(s.ry);
        for (Part p : s.parts) {
            double[] o = rot(p.cap.ox(), p.cap.oz(), nTheta);
            int cx = (int) Math.floor(nrx + o[0]);
            int cz = (int) Math.floor(nrz + o[1]);
            int cy = baseY + p.cap.oy();
            if (cy < w.getMinHeight() || cy >= w.getMaxHeight()) return "Can't go there!";
            if (!w.isChunkLoaded(cx >> 4, cz >> 4)) return "Uncharted waters (chunk not loaded)";
            long key = cellKey(cx, cy, cz);
            if (hitsOtherShip(s, key)) return "Bonk! You hit another ship!";
            Material t = w.getBlockAt(cx, cy, cz).getType();
            boolean free = Materials.isAirLike(t) || (Materials.isWaterLike(t) && cy <= s.waterTop);
            if (!free) return Materials.isNatural(t) ? "Land ahead!" : "Bonk! Something is in the way!";
            if (p.bottom) {
                Material bt = w.getBlockAt(cx, cy - 1, cz).getType();
                if (Materials.isWaterLike(bt)) floating = true;
                else if (!Materials.isAirLike(bt)) return "Land ahead!";
            }
            out.add(key);
        }
        return floating ? null : "You can't sail out of the water!";
    }

    private void applyPose(Ship s, double nrx, double nrz, double nTheta) {
        Map<Entity, Location> carried = carry(s, nrx, nrz, nTheta);
        float dYaw = (float) (nTheta - s.theta);

        // gentle bob on the waves, bigger when moving fast
        double maxBob = cfg("bob-amount", 0.06);
        double moveFrac = Math.min(1.0, Math.abs(s.speed) / 0.3 + Math.abs(s.omega) / 3.0);
        s.bob = maxBob * moveFrac * Math.sin(s.ticks * 0.16);
        double by = s.ry + s.bob;
        s.root.teleport(new Location(s.world, nrx, by, nrz, 0f, 0f), TeleportFlag.EntityState.RETAIN_PASSENGERS);

        // Turn the models. Updated every few ticks with matching interpolation so all
        // pieces stay glued together (no gaps between blocks while turning).
        int every = Math.max(1, plugin.getConfig().getInt("turn-render-ticks", 1));
        boolean pending = Math.abs(nTheta - s.shownTheta) > 1e-6;
        if (pending && (s.ticks % every == 0 || s.omega == 0)) {
            List<Entity> changed = new ArrayList<>(s.parts.size());
            for (Part p : s.parts) {
                p.display.setInterpolationDelay(0);
                p.display.setInterpolationDuration(Math.max(every, smoothTicks()));
                p.display.setTransformation(transform(p, nTheta));
                changed.add(p.display);
            }
            // send all block updates together so the ship can't come apart on screen
            double range = Bukkit.getViewDistance() * 16.0 + 32.0;
            PacketBundler.flush(changed, new Location(s.world, nrx, s.ry, nrz), range, plugin.getLogger());
            s.shownTheta = nTheta;
        }

        double offset = cfg("seat-height-offset", 0.0);
        for (SeatRef ref : s.seatRefs) {
            Entity e = Bukkit.getEntity(ref.id());
            if (e == null) continue;
            double[] o = rot(ref.ox(), ref.oz(), nTheta);
            Location l = new Location(s.world, nrx + o[0], by + ref.oy() + ref.dy(), nrz + o[1],
                    e.getLocation().getYaw() + dYaw, 0f);
            e.teleport(l, TeleportFlag.EntityState.RETAIN_PASSENGERS);
        }
        moveColliders(s, nrx, nrz, nTheta);
        effects(s, nrx, nrz, nTheta);
        if (s.attachRefs != null) {
            for (AttachRef a : s.attachRefs) {
                Entity e = Bukkit.getEntity(a.id());
                if (e == null) continue;
                double[] o = rot(a.lx(), a.lz(), nTheta);
                Location l = new Location(s.world, nrx + o[0], by + a.ly(), nrz + o[1],
                        e.getLocation().getYaw() + dYaw, e.getLocation().getPitch());
                e.teleport(l);
            }
        }

        for (Map.Entry<Entity, Location> m : carried.entrySet()) {
            Entity e = m.getKey();
            Location to = m.getValue();
            if (e instanceof Player p) {
                Location from = p.getLocation();
                RelativeMover.move(p, to.getX() - from.getX(), to.getY() - from.getY(), to.getZ() - from.getZ(),
                        dYaw, plugin.getLogger());
            } else {
                e.teleport(to);
            }
            e.setFallDistance(0f);
        }
        s.rx = nrx;
        s.rz = nrz;
        s.theta = nTheta;
    }

    /** Wake behind the ship, spray at the bow, smoke from the engine, water sounds. */
    private void effects(Ship s, double nrx, double nrz, double nTheta) {
        World w = s.world;
        double sp = Math.abs(s.speed);
        double waterY = s.waterTop + 1.05;
        if (sp > 0.05) {
            double[] st = rot(s.sternX - s.fx * 0.8, s.sternZ - s.fz * 0.8, nTheta);
            double[] bw = rot(s.bowX + s.fx * 0.6, s.bowZ + s.fz * 0.6, nTheta);
            int n = (int) Math.ceil(sp * 10);
            Location stern = new Location(w, nrx + st[0], waterY, nrz + st[1]);
            w.spawnParticle(Particle.SPLASH, stern, n * 2, 0.6, 0.05, 0.6, 0.15);
            w.spawnParticle(Particle.BUBBLE_POP, stern, n, 0.7, 0.05, 0.7, 0.02);
            if (s.ticks % 2 == 0) w.spawnParticle(Particle.CLOUD, stern, 1, 0.4, 0.02, 0.4, 0.01);
            if (s.speed > 0.15) {
                Location bow = new Location(w, nrx + bw[0], waterY, nrz + bw[1]);
                w.spawnParticle(Particle.SPLASH, bow, n * 3, 0.5, 0.1, 0.5, 0.25);
            }
            int every = Math.max(6, (int) (20 - sp * 15));
            if (s.ticks % every == 0) w.playSound(stern, Sound.ENTITY_BOAT_PADDLE_WATER, 0.7f, 0.8f + (float) sp * 0.4f);
        }
        if (!Double.isNaN(s.engineX) && s.ticks % 3 == 0) {
            double[] e = rot(s.engineX, s.engineZ, nTheta);
            Location top = new Location(w, nrx + e[0], s.ry + s.bob + s.engineY + 1.1, nrz + e[1]);
            w.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, top, 1, 0.1, 0.05, 0.1, 0.01 + sp * 0.03);
            if (sp > 0.3) w.spawnParticle(Particle.LARGE_SMOKE, top, 1, 0.1, 0.05, 0.1, 0.02);
            if (s.tier >= 4 && sp > 0.2) w.spawnParticle(Particle.FLAME, top, 1, 0.08, 0.02, 0.08, 0.005);
            if (s.tier >= 5 && sp > 0.2) w.spawnParticle(Particle.ELECTRIC_SPARK, top, 2, 0.15, 0.1, 0.15, 0.02);
        }
    }

    /** "▰▰▰▱▱" speed gauge. */
    private static String gauge(double frac) {
        int filled = (int) Math.round(Math.max(0, Math.min(1, frac)) * 10);
        return "▰".repeat(filled) + "▱".repeat(10 - filled);
    }

    private void moveColliders(Ship s, double nrx, double nrz, double nTheta) {
        if (s.colliders == null) return;
        double colOffset = cfg("collider-y-offset", 0.0);
        for (Collider c : s.colliders) {
            if (c.carrier == null || !c.carrier.isValid()) continue;
            double[] o = rot(c.ox, c.oz, nTheta);
            Location l = new Location(s.world, nrx + o[0], s.ry + s.bob + c.bottom + colOffset, nrz + o[1], 0f, 0f);
            c.carrier.teleport(l, TeleportFlag.EntityState.RETAIN_PASSENGERS);
        }
    }

    private void removeColliders(Ship s) {
        if (s.colliders == null) return;
        for (Collider c : s.colliders) {
            if (c.shulker != null) c.shulker.remove();
            if (c.carrier != null) c.carrier.remove();
        }
        s.colliders = null;
    }

    /** People/mobs/items standing on deck, and where they go with the new pose. */
    private Map<Entity, Location> carry(Ship s, double nrx, double nrz, double nTheta) {
        Map<Entity, Location> out = new LinkedHashMap<>();
        Location c = new Location(s.world, s.rx, s.ry, s.rz);
        double rad = s.radius + 2;
        for (Entity e : s.world.getNearbyEntities(c, rad, 40, rad)) {
            if (e.getVehicle() != null || isShipPart(e) || plugin.seats().isSeat(e)) continue;
            if (e instanceof Display) continue;
            if (e instanceof Player p && p.getGameMode() == GameMode.SPECTATOR) continue;
            Location l = e.getLocation();
            double relX = l.getX() - s.rx, relY = l.getY() - s.ry, relZ = l.getZ() - s.rz;
            double[] loc = rot(relX, relZ, -s.theta);
            int cx = (int) Math.floor(loc[0] + 0.5), cz = (int) Math.floor(loc[1] + 0.5);
            List<Double> tops = s.surface.get(key(cx, cz));
            if (tops == null) continue;
            double best = Double.NEGATIVE_INFINITY;
            for (double t : tops) if (t <= relY + 0.6 && t > best) best = t;
            if (best == Double.NEGATIVE_INFINITY || relY - best > 2.5) continue; // 2.5 = still carried mid-jump
            double[] nw = rot(loc[0], loc[1], nTheta);
            Location to = new Location(s.world, nrx + nw[0], s.ry + Math.max(relY, best), nrz + nw[1],
                    l.getYaw() + (float) (nTheta - s.theta), l.getPitch());
            out.put(e, to);
        }
        return out;
    }

    // ------------------------------------------------------------------ dock (displays -> blocks)

    private void dock(Ship s) {
        if (s.state != State.SAILING) return;
        int k = Math.floorMod((int) Math.round(s.theta / 90.0), 4);
        int bx = (int) Math.floor(s.rx), by = (int) Math.floor(s.ry), bz = (int) Math.floor(s.rz);
        int[] order = {k, (k + 1) % 4, (k + 3) % 4, (k + 2) % 4};
        for (int r = 0; r <= 3; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    for (int kk : order) {
                        if (canPlace(s, bx + dx, by, bz + dz, kk)) {
                            place(s, bx + dx, by, bz + dz, kk, false);
                            return;
                        }
                    }
                }
            }
        }
        place(s, bx, by, bz, k, true); // last resort
    }

    private static int[] rotSteps(int x, int z, int steps) {
        for (int i = 0; i < steps; i++) {
            int nx = -z;
            z = x;
            x = nx;
        }
        return new int[]{x, z};
    }

    private boolean canPlace(Ship s, int bx, int by, int bz, int k) {
        World w = s.world;
        for (Part p : s.parts) {
            int[] o = rotSteps(p.cap.ox(), p.cap.oz(), k);
            int x = bx + o[0], y = by + p.cap.oy(), z = bz + o[1];
            if (!w.isChunkLoaded(x >> 4, z >> 4)) return false;
            if (hitsOtherShip(s, cellKey(x, y, z))) return false;
            Material t = w.getBlockAt(x, y, z).getType();
            if (!(Materials.isAirLike(t) || (Materials.isWaterLike(t) && y <= s.waterTop))) return false;
        }
        return true;
    }

    private void place(Ship s, int bx, int by, int bz, int k, boolean force) {
        World w = s.world;
        double finalTheta = k * 90.0;
        Map<Entity, Location> carried = carry(s, bx + 0.5, bz + 0.5, finalTheta);

        Set<Pos> newBlocks = new HashSet<>();
        for (Part p : s.parts) {
            Captured c = p.cap;
            int[] o = rotSteps(c.ox(), c.oz(), k);
            Pos n = new Pos(bx + o[0], by + c.oy(), bz + o[1]);
            Block nb = n.block(w);
            Material cur = nb.getType();
            boolean free = Materials.isAirLike(cur) || (Materials.isWaterLike(cur) && n.y() <= s.waterTop);
            if (force && !free) {
                Location drop = nb.getLocation().add(0.5, 1.0, 0.5);
                w.dropItemNaturally(drop, new ItemStack(c.data().getMaterial()));
                if (c.contents() != null) {
                    for (ItemStack it : c.contents()) if (it != null) w.dropItemNaturally(drop, it);
                }
                continue;
            }
            BlockData data = c.data().clone();
            for (int i = 0; i < k; i++) data = BlockRotator.rotate(data, true);
            if (data instanceof Waterlogged wl) wl.setWaterlogged(n.y() <= s.waterTop);
            nb.setBlockData(data, false);
            if (c.tile()) {
                BlockState st = nb.getState();
                if (c.contents() != null && st instanceof Container con) con.getSnapshotInventory().setContents(c.contents());
                if (c.front() != null && st instanceof Sign sign) {
                    for (int i = 0; i < 4; i++) {
                        sign.getSide(Side.FRONT).line(i, c.front()[i]);
                        sign.getSide(Side.BACK).line(i, c.back()[i]);
                    }
                }
                Engine.setEngineTag(st, c.engine());
                Engine.setHp(st, c.engine() > 0 ? s.hp : -1);
                Cannons.setCannonTag(st, c.cannon());
                st.update(true, false);
            }
            newBlocks.add(n);
        }

        // seats back onto their blocks
        double offset = cfg("seat-height-offset", 0.0);
        for (SeatRef ref : s.seatRefs) {
            Entity e = Bukkit.getEntity(ref.id());
            if (e == null) continue;
            int[] o = rotSteps(ref.ox(), ref.oz(), k);
            Block sb = w.getBlockAt(bx + o[0], by + ref.oy(), bz + o[1]);
            Location l = sb.getLocation().add(0.5, ref.dy(), 0.5);
            l.setYaw(e.getLocation().getYaw() + (float) (finalTheta - s.theta));
            e.teleport(l, TeleportFlag.EntityState.RETAIN_PASSENGERS);
            plugin.seats().relocate(ref.id(), sb.getLocation());
        }
        // cannon barrels to their new spot
        if (s.attachRefs != null) {
            for (AttachRef a : s.attachRefs) {
                Entity e = Bukkit.getEntity(a.id());
                if (e == null) continue;
                double[] o = rot(a.lx(), a.lz(), finalTheta);
                Location l = new Location(w, bx + 0.5 + o[0], by + a.ly(), bz + 0.5 + o[1],
                        e.getLocation().getYaw() + (float) (finalTheta - s.theta), e.getLocation().getPitch());
                e.teleport(l);
                int[] co = rotSteps(a.cox(), a.coz(), k);
                plugin.cannons().retag(e, w.getBlockAt(bx + co[0], by + a.coy(), bz + co[1]));
            }
        }

        // blocks are placed, now remove the moving ship (no flicker)
        for (Part p : s.parts) {
            if (p.display != null) p.display.remove();
        }
        if (s.root != null) {
            s.root.eject();
            s.root.remove();
        }
        removeColliders(s);

        for (Map.Entry<Entity, Location> m : carried.entrySet()) {
            Location to = unstuck(m.getValue());
            m.getKey().teleport(to);
            m.getKey().setFallDistance(0f);
        }

        s.blocks = newBlocks;
        s.seatPos = new Pos(bx, by, bz);
        s.state = State.DOCKED;
        s.root = null;
        s.parts = null;
        s.seatRefs = null;
        s.attachRefs = null;
        s.surface = null;
        s.cells = null;
        s.speed = 0;
        s.theta = 0;
    }

    /** Move a spot up until there is room to stand (so nobody gets stuck in blocks). */
    private static Location unstuck(Location l) {
        Location t = l.clone();
        for (int i = 0; i < 6; i++) {
            Block feet = t.getBlock();
            Block head = feet.getRelative(BlockFace.UP);
            if (feet.isPassable() && head.isPassable()) return t;
            t.setY(Math.floor(t.getY()) + 1.0);
        }
        return t;
    }

    // ------------------------------------------------------------------ game mode safety

    /** Gives back the normal game mode if a player got stuck in spectator from an old test build. */
    public void restoreGameMode(Player p) {
        String prev = p.getPersistentDataContainer().get(prevModeKey, PersistentDataType.STRING);
        if (prev == null) return;
        GameMode gm;
        try {
            gm = GameMode.valueOf(prev);
        } catch (IllegalArgumentException ex) {
            gm = GameMode.SURVIVAL;
        }
        p.setGameMode(gm);
        p.getPersistentDataContainer().remove(prevModeKey);
    }
}
