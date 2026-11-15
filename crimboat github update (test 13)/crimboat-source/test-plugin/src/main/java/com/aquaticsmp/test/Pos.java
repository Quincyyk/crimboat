package com.aquaticsmp.test;

import org.bukkit.World;
import org.bukkit.block.Block;

/** Simple block position (no world). */
public record Pos(int x, int y, int z) {

    public static Pos of(Block b) {
        return new Pos(b.getX(), b.getY(), b.getZ());
    }

    public Block block(World w) {
        return w.getBlockAt(x, y, z);
    }

    public Pos add(int dx, int dy, int dz) {
        return new Pos(x + dx, y + dy, z + dz);
    }

    /** Rotate 90 degrees clockwise (seen from above) around pivot. */
    public Pos rotateCW(Pos p) {
        return new Pos(p.x - (z - p.z), y, p.z + (x - p.x));
    }

    /** Rotate 90 degrees counter-clockwise (seen from above) around pivot. */
    public Pos rotateCCW(Pos p) {
        return new Pos(p.x + (z - p.z), y, p.z - (x - p.x));
    }
}
