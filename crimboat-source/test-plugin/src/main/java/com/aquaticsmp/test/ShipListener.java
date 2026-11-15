package com.aquaticsmp.test;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Slab;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;

public final class ShipListener implements Listener {

    private final TestPlugin plugin;

    public ShipListener(TestPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------ sitting + engine check

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        // Gunner right-clicks (like a crossbow) = fire.
        if ((e.getAction() == Action.RIGHT_CLICK_AIR || e.getAction() == Action.RIGHT_CLICK_BLOCK)
                && plugin.cannons().isGunner(e.getPlayer())) {
            e.setCancelled(true);
            if (e.getHand() == EquipmentSlot.HAND) plugin.cannons().tryFire(e.getPlayer());
            return;
        }
        if (e.getHand() != EquipmentSlot.HAND || e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block b = e.getClickedBlock();
        if (b == null) return;
        Player p = e.getPlayer();

        // Right-click a cannon = man it.
        if (Cannons.isCannonBlock(b)) {
            e.setCancelled(true);
            if (!p.isSneaking() && !p.isInsideVehicle()) {
                com.aquaticsmp.test.api.CannonManEvent man = new com.aquaticsmp.test.api.CannonManEvent(p, b);
                Bukkit.getPluginManager().callEvent(man);
                if (!man.isCancelled()) plugin.cannons().man(p, b);
            }
            return;
        }

        // Right-click the engine = check if the boat works.
        if (b.getType() == Engine.TYPE && !p.isSneaking() && Engine.isEngineBlock(b)) {
            e.setCancelled(true);
            ShipScanner.Result r = ShipScanner.scan(b, plugin.ships().maxSize());
            boolean ok = r.status == ShipScanner.Status.OK;
            p.sendMessage(Component.text(r.describe(plugin.ships().maxSize()),
                    ok ? NamedTextColor.GREEN : NamedTextColor.RED));
            return;
        }

        // Sitting: empty hand, not sneaking, bottom stair or bottom slab.
        if (p.isSneaking() || p.isInsideVehicle()) return;
        if (!p.getInventory().getItemInMainHand().getType().isAir()) return;

        BlockData d = b.getBlockData();
        boolean stair = d instanceof Stairs st && st.getHalf() == Bisected.Half.BOTTOM;
        boolean slab = d instanceof Slab sl && sl.getType() == Slab.Type.BOTTOM;
        if (!stair && !slab) return;
        if (!b.getRelative(BlockFace.UP).isPassable()) return;
        if (p.getLocation().distanceSquared(b.getLocation().add(0.5, 0.5, 0.5)) > 16) return;
        if (plugin.seats().isOccupied(b)) {
            p.sendActionBar(Component.text("Someone is already sitting there.", NamedTextColor.YELLOW));
            return;
        }

        e.setCancelled(true);
        ItemDisplay seat = plugin.seats().sit(p, b);
        if (stair) plugin.ships().tryStartDriving(p, b, seat);
    }

    @EventHandler
    public void onDismount(EntityDismountEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        Entity seat = e.getDismounted();
        if (!plugin.seats().isSeat(seat)) return;

        ShipManager.Ship s = plugin.ships().shipOfDriver(p.getUniqueId());
        if (s != null && s.driverSeat.equals(seat.getUniqueId())) plugin.ships().stopDriving(s);

        // Passenger getting up while the ship is still sailing: stay on deck.
        ShipManager.Ship sailing = plugin.ships().sailingShipWithSeat(seat.getUniqueId());
        if (sailing != null) {
            plugin.ships().forgetSeat(seat.getUniqueId());
            Location deck = seat.getLocation().clone();
            if (!plugin.isEnabled()) return;
            Bukkit.getScheduler().runTask(plugin, () -> {
                plugin.seats().removeSeat(seat.getUniqueId());
                if (p.isOnline()) {
                    deck.setYaw(p.getLocation().getYaw());
                    deck.setPitch(p.getLocation().getPitch());
                    p.teleport(deck);
                }
            });
            return;
        }

        Location blockLoc = plugin.seats().blockOf(seat.getUniqueId());
        if (!plugin.isEnabled()) return; // server shutting down
        Bukkit.getScheduler().runTask(plugin, () -> {
            plugin.seats().removeSeat(seat.getUniqueId());
            if (blockLoc != null && p.isOnline()) {
                Block above = blockLoc.getBlock().getRelative(BlockFace.UP);
                if (above.isPassable() && above.getRelative(BlockFace.UP).isPassable()) {
                    Location to = above.getLocation().add(0.5, 0, 0.5);
                    to.setYaw(p.getLocation().getYaw());
                    to.setPitch(p.getLocation().getPitch());
                    p.teleport(to);
                }
            }
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        ShipManager.Ship s = plugin.ships().shipOfDriver(p.getUniqueId());
        if (s != null) plugin.ships().stopDriving(s);
        Entity v = p.getVehicle();
        if (v != null && plugin.seats().isSeat(v)) plugin.seats().removeSeat(v.getUniqueId());
        plugin.ships().restoreGameMode(p);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        // Safety: if an old test build left them in spectator, give their game mode back.
        plugin.ships().restoreGameMode(e.getPlayer());
    }

    /** Upgrade recipes need the engine one tier below in the middle (any plain blast furnace won't do). */
    @EventHandler
    public void onPrepareCraft(org.bukkit.event.inventory.PrepareItemCraftEvent e) {
        if (!(e.getRecipe() instanceof org.bukkit.Keyed keyed)) return;
        int tier = Engine.upgradeRecipeTier(keyed.getKey());
        if (tier < 2) return;
        org.bukkit.inventory.ItemStack[] m = e.getInventory().getMatrix();
        org.bukkit.inventory.ItemStack center = m.length >= 9 ? m[4] : null;
        if (Engine.itemTier(center) != tier - 1) e.getInventory().setResult(null);
    }

    /** Gunner left-click = fire. */
    @EventHandler
    public void onSwing(org.bukkit.event.player.PlayerAnimationEvent e) {
        if (plugin.cannons().isGunner(e.getPlayer())) plugin.cannons().tryFire(e.getPlayer());
    }

    /** Ship models / deck colliders can't be hurt. */
    @EventHandler(ignoreCancelled = true)
    public void onPartDamage(org.bukkit.event.entity.EntityDamageEvent e) {
        if (plugin.ships().isShipPart(e.getEntity())) e.setCancelled(true);
    }

    // ------------------------------------------------------------------ engine item + protection

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        Block b = e.getBlockPlaced();
        if (Cannons.isCannonItem(e.getItemInHand()) && !e.isCancelled()) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (b.getType() != Cannons.BLOCK) return;
                BlockState st = b.getState();
                Cannons.setCannonTag(st, true);
                st.update(true, false);
                plugin.cannons().spawnModels(b, e.getPlayer().getLocation().getYaw());
                e.getPlayer().sendActionBar(Component.text("Cannon placed! Right-click it to man it.", NamedTextColor.GOLD));
            });
        }
        for (BlockFace f : ShipScanner.FACES) {
            if (plugin.ships().shipAt(b.getWorld(), Pos.of(b.getRelative(f))) != null) {
                e.setCancelled(true);
                e.getPlayer().sendActionBar(Component.text("You can't build on a ship while it's sailing.",
                        NamedTextColor.RED));
                return;
            }
        }
        int placedTier = Engine.itemTier(e.getItemInHand());
        if (placedTier > 0) {
            BlockState st = b.getState();
            Engine.setEngineTag(st, placedTier);
            Engine.setOwner(st, e.getPlayer().getUniqueId());
            st.update(true, false);
            e.getPlayer().sendActionBar(Engine.display(placedTier)
                    .append(Component.text(" placed! Right-click it to check your boat.", NamedTextColor.GRAY)));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        if (plugin.ships().shipAt(b.getWorld(), Pos.of(b)) != null) {
            e.setCancelled(true);
            e.getPlayer().sendActionBar(Component.text("You can't break a ship while it's sailing.",
                    NamedTextColor.RED));
            return;
        }
        if (Cannons.isCannonBlock(b)) {
            plugin.cannons().removeModels(b);
            e.setDropItems(false);
            if (e.getPlayer().getGameMode() != org.bukkit.GameMode.CREATIVE) {
                b.getWorld().dropItemNaturally(b.getLocation().add(0.5, 0.5, 0.5), Cannons.createCannonItem(1));
            }
            return;
        }
        int brokenTier = Engine.blockTier(b);
        if (brokenTier > 0) {
            e.setDropItems(false);
            if (e.getPlayer().getGameMode() != org.bukkit.GameMode.CREATIVE) {
                b.getWorld().dropItemNaturally(b.getLocation().add(0.5, 0.5, 0.5), Engine.createItem(brokenTier, 1));
            }
        }
    }
}
