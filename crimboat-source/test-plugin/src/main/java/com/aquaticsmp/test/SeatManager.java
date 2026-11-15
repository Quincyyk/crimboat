package com.aquaticsmp.test;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Lets players sit on stairs and slabs using an invisible seat entity. */
public final class SeatManager {

    private final TestPlugin plugin;
    private final NamespacedKey seatKey;
    private final Map<UUID, Location> seatToBlock = new HashMap<>();
    private final Map<Location, UUID> blockToSeat = new HashMap<>();

    public SeatManager(TestPlugin plugin) {
        this.plugin = plugin;
        this.seatKey = new NamespacedKey(plugin, "seat");
    }

    public boolean isSeat(Entity e) {
        return e instanceof ItemDisplay && e.getPersistentDataContainer().has(seatKey, PersistentDataType.BYTE);
    }

    public boolean isOccupied(Block b) {
        UUID id = blockToSeat.get(b.getLocation());
        if (id == null) return false;
        Entity e = Bukkit.getEntity(id);
        if (e == null || !e.isValid() || e.getPassengers().isEmpty()) {
            removeSeat(id);
            return false;
        }
        return true;
    }

    public ItemDisplay sit(Player p, Block b) {
        return sit(p, b, 0.5);
    }

    /** Sit on a block at a given height above the block's bottom (0.5 = slab/stair, 1.0 = full block). */
    public ItemDisplay sit(Player p, Block b, double height) {
        double offset = plugin.getConfig().getDouble("seat-height-offset", 0.0);
        Location loc = b.getLocation().add(0.5, height + offset, 0.5);
        if (b.getBlockData() instanceof Stairs st) {
            // You sit with your back against the tall part of the stair.
            loc.setDirection(st.getFacing().getOppositeFace().getDirection());
        } else {
            loc.setYaw(p.getLocation().getYaw());
        }
        loc.setPitch(0);

        ItemDisplay seat = b.getWorld().spawn(loc, ItemDisplay.class, e -> {
            e.setPersistent(false);
            e.setTeleportDuration(1);
            e.getPersistentDataContainer().set(seatKey, PersistentDataType.BYTE, (byte) 1);
        });
        seat.addPassenger(p);
        seatToBlock.put(seat.getUniqueId(), b.getLocation());
        blockToSeat.put(b.getLocation(), seat.getUniqueId());
        return seat;
    }

    /** Called when a ship moves a seat to a new block. */
    public void relocate(UUID seatId, Location newBlockLoc) {
        Location old = seatToBlock.get(seatId);
        if (old == null) return;
        blockToSeat.remove(old);
        seatToBlock.put(seatId, newBlockLoc);
        blockToSeat.put(newBlockLoc, seatId);
    }

    public Map<UUID, Location> allSeats() {
        return Map.copyOf(seatToBlock);
    }

    public Location blockOf(UUID seatId) {
        return seatToBlock.get(seatId);
    }

    public void removeSeat(UUID seatId) {
        Location loc = seatToBlock.remove(seatId);
        if (loc != null) blockToSeat.remove(loc);
        Entity e = Bukkit.getEntity(seatId);
        if (e != null) {
            e.eject();
            e.remove();
        }
    }

    public void removeAll() {
        for (UUID id : Map.copyOf(seatToBlock).keySet()) removeSeat(id);
        // Clean up any leftovers (e.g. after a crash).
        for (World w : Bukkit.getWorlds()) {
            for (ItemDisplay d : w.getEntitiesByClass(ItemDisplay.class)) {
                if (isSeat(d)) {
                    d.eject();
                    d.remove();
                }
            }
        }
    }
}
