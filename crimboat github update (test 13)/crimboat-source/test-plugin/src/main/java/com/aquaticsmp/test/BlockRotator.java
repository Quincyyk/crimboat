package com.aquaticsmp.test;

import org.bukkit.Axis;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.MultipleFacing;
import org.bukkit.block.data.Orientable;
import org.bukkit.block.data.Rotatable;
import org.bukkit.block.data.type.Wall;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Rotates block data 90 degrees (stairs, chests, fences, logs, signs...). */
public final class BlockRotator {

    private BlockRotator() {}

    private static final BlockFace[] SIXTEEN = {
            BlockFace.NORTH, BlockFace.NORTH_NORTH_EAST, BlockFace.NORTH_EAST, BlockFace.EAST_NORTH_EAST,
            BlockFace.EAST, BlockFace.EAST_SOUTH_EAST, BlockFace.SOUTH_EAST, BlockFace.SOUTH_SOUTH_EAST,
            BlockFace.SOUTH, BlockFace.SOUTH_SOUTH_WEST, BlockFace.SOUTH_WEST, BlockFace.WEST_SOUTH_WEST,
            BlockFace.WEST, BlockFace.WEST_NORTH_WEST, BlockFace.NORTH_WEST, BlockFace.NORTH_NORTH_WEST};

    private static final BlockFace[] CARDINAL = {
            BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST};

    public static BlockFace rotate(BlockFace f, boolean cw) {
        for (int i = 0; i < SIXTEEN.length; i++) {
            if (SIXTEEN[i] == f) return SIXTEEN[(i + (cw ? 4 : 12)) % 16];
        }
        return f; // UP / DOWN / SELF
    }

    public static BlockData rotate(BlockData original, boolean cw) {
        BlockData d = original.clone();

        if (d instanceof Directional dir) {
            BlockFace nf = rotate(dir.getFacing(), cw);
            if (dir.getFaces().contains(nf)) dir.setFacing(nf);
        }
        if (d instanceof Rotatable rot) {
            rot.setRotation(rotate(rot.getRotation(), cw));
        }
        if (d instanceof Orientable o) {
            Axis a = o.getAxis();
            Axis na = a == Axis.X ? Axis.Z : a == Axis.Z ? Axis.X : a;
            if (o.getAxes().contains(na)) o.setAxis(na);
        }
        if (d instanceof MultipleFacing mf) {
            Set<BlockFace> old = new HashSet<>(mf.getFaces());
            for (BlockFace f : CARDINAL) {
                if (mf.getAllowedFaces().contains(f)) mf.setFace(f, false);
            }
            for (BlockFace f : old) {
                BlockFace nf = rotate(f, cw);
                if (nf != f && mf.getAllowedFaces().contains(nf)) mf.setFace(nf, true);
            }
        }
        if (d instanceof Wall wall) {
            Map<BlockFace, Wall.Height> heights = new EnumMap<>(BlockFace.class);
            for (BlockFace f : CARDINAL) heights.put(f, wall.getHeight(f));
            for (BlockFace f : CARDINAL) wall.setHeight(rotate(f, cw), heights.get(f));
        }
        return d;
    }
}
