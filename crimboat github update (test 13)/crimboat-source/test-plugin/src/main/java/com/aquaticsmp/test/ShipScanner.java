package com.aquaticsmp.test;

import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Slab;
import org.bukkit.block.data.type.Stairs;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

/**
 * Finds every ship block connected to a starting block (flood fill) and checks
 * that it floats on water and isn't stuck to land.
 */
public final class ShipScanner {

    private ShipScanner() {}

    public enum Status { OK, NOT_A_SHIP_BLOCK, TOO_BIG, TOO_BIG_FOR_ENGINE, STUCK_TO_LAND, BAD_BLOCK, NOT_FLOATING, NO_ENGINE }

    public static final BlockFace[] FACES = {
            BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};

    public static final class Result {
        public Status status;
        public Set<Pos> blocks = new HashSet<>();
        public int waterTop = Integer.MIN_VALUE;
        public boolean hasEngine;
        public int tier;
        public org.bukkit.Material badBlock;
        public int stairs;
        public int slabs;

        private static String nice(org.bukkit.Material m) {
            return m == null ? "block" : m.name().toLowerCase().replace('_', ' ');
        }

        public String describe(int max) {
            return switch (status) {
                case OK -> "Boat ready! " + Engine.name(tier) + ", " + blocks.size() + " blocks, " + stairs
                        + " pirate chair(s), " + slabs + " passenger seat(s).";
                case NOT_A_SHIP_BLOCK -> "That block can't be part of a boat.";
                case TOO_BIG -> "Too big to be a boat! (max " + max + " blocks, even with a Tier 5 engine)";
                case TOO_BIG_FOR_ENGINE -> "Your ship is " + blocks.size() + " blocks - too big for a " + Engine.name(tier)
                        + " (max " + Engine.maxShipSize(tier) + "). Upgrade your engine or make the ship smaller!";
                case STUCK_TO_LAND -> "Your boat is stuck to land! Break the connection.";
                case BAD_BLOCK -> "Ships can only be made of wood, wool and decorations - remove the "
                        + nice(badBlock) + " touching your ship.";
                case NOT_FLOATING -> "Your boat isn't floating on water.";
                case NO_ENGINE -> "No Ship Engine on this boat.";
            };
        }
    }

    public static Result scan(Block start, int max) {
        Result r = new Result();
        World w = start.getWorld();
        if (!Materials.isShipBlock(start.getType())) {
            r.status = Status.NOT_A_SHIP_BLOCK;
            return r;
        }

        ArrayDeque<Pos> queue = new ArrayDeque<>();
        Pos first = Pos.of(start);
        r.blocks.add(first);
        queue.add(first);

        while (!queue.isEmpty()) {
            Pos p = queue.poll();
            for (BlockFace f : FACES) {
                Pos n = p.add(f.getModX(), f.getModY(), f.getModZ());
                if (r.blocks.contains(n)) continue;
                if (n.y() < w.getMinHeight() || n.y() >= w.getMaxHeight()) continue;
                if (!w.isChunkLoaded(n.x() >> 4, n.z() >> 4)) continue;
                Block nb = n.block(w);
                if (!Materials.isShipBlock(nb.getType())) continue;
                int nt = Engine.blockTier(nb);
                if (nt > 0) {
                    r.hasEngine = true;
                    r.tier = Math.max(r.tier, nt);
                }
                r.blocks.add(n);
                if (r.blocks.size() > max) {
                    r.status = Status.TOO_BIG;
                    return r;
                }
                queue.add(n);
            }
        }

        boolean floating = false;
        boolean stuck = false;
        for (Pos p : r.blocks) {
            Block b = p.block(w);
            int bt = Engine.blockTier(b);
            if (bt > 0) {
                r.hasEngine = true;
                r.tier = Math.max(r.tier, bt);
            }

            BlockData d = b.getBlockData();
            boolean freeAbove = b.getRelative(BlockFace.UP).isPassable();
            if (d instanceof Stairs st && st.getHalf() == Bisected.Half.BOTTOM && freeAbove) r.stairs++;
            if (d instanceof Slab sl && sl.getType() == Slab.Type.BOTTOM && freeAbove) r.slabs++;

            for (BlockFace f : FACES) {
                Pos n = p.add(f.getModX(), f.getModY(), f.getModZ());
                if (r.blocks.contains(n)) continue;
                if (!w.isChunkLoaded(n.x() >> 4, n.z() >> 4)) continue;
                var t = n.block(w).getType();
                if (Materials.isWaterLike(t)) {
                    r.waterTop = Math.max(r.waterTop, n.y());
                    if (f == BlockFace.DOWN) floating = true;
                } else if (!Materials.isAirLike(t)) {
                    if (Materials.isNatural(t)) stuck = true; // touching natural terrain
                    else if (r.badBlock == null) r.badBlock = t; // stone etc. stuck to the ship
                }
            }
        }

        if (stuck) r.status = Status.STUCK_TO_LAND;
        else if (r.badBlock != null) r.status = Status.BAD_BLOCK;
        else if (r.hasEngine && r.blocks.size() > Engine.maxShipSize(r.tier)) r.status = Status.TOO_BIG_FOR_ENGINE;
        else if (!floating) r.status = Status.NOT_FLOATING;
        else if (!r.hasEngine) r.status = Status.NO_ENGINE;
        else r.status = Status.OK;
        return r;
    }
}
