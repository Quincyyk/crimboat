package com.aquaticsmp.test;

import org.bukkit.Material;
import org.bukkit.Tag;

import java.util.EnumSet;
import java.util.Set;

/** Decides which blocks can be part of a ship. */
public final class Materials {

    private Materials() {}

    private static final Set<Material> WATER_LIKE = EnumSet.of(
            Material.WATER, Material.BUBBLE_COLUMN, Material.SEAGRASS,
            Material.TALL_SEAGRASS, Material.KELP, Material.KELP_PLANT);

    /** Natural terrain - islands are made of these, so they are never part of a ship. */
    private static final Set<Material> NATURAL = EnumSet.of(
            Material.GRAVEL, Material.CLAY, Material.SANDSTONE, Material.RED_SANDSTONE,
            Material.BEDROCK, Material.LAVA, Material.MAGMA_BLOCK, Material.SNOW,
            Material.SNOW_BLOCK, Material.POWDER_SNOW, Material.SHORT_GRASS,
            Material.TALL_GRASS, Material.FERN, Material.LARGE_FERN, Material.SEA_PICKLE,
            Material.SUGAR_CANE, Material.DEAD_BUSH, Material.SUSPICIOUS_SAND,
            Material.SUSPICIOUS_GRAVEL);

    public static boolean isAirLike(Material t) {
        return t.isAir() || t == Material.LILY_PAD;
    }

    public static boolean isWaterLike(Material t) {
        return WATER_LIKE.contains(t);
    }

    public static boolean isNatural(Material t) {
        return NATURAL.contains(t)
                || t.name().endsWith("_ORE")
                || Tag.DIRT.isTagged(t)
                || Tag.BASE_STONE_OVERWORLD.isTagged(t)
                || Tag.BASE_STONE_NETHER.isTagged(t)
                || Tag.SAND.isTagged(t)
                || Tag.CORAL_BLOCKS.isTagged(t)
                || Tag.CORALS.isTagged(t)
                || Tag.WALL_CORALS.isTagged(t)
                || Tag.ICE.isTagged(t);
    }

    /** Decorations allowed on ships (besides everything wooden and wool). */
    private static final Set<Material> DECOR = EnumSet.of(
            Material.BARREL, Material.CHEST, Material.TRAPPED_CHEST, Material.LADDER, Material.LANTERN,
            Material.SOUL_LANTERN, Material.TORCH, Material.WALL_TORCH, Material.SOUL_TORCH, Material.SOUL_WALL_TORCH,
            Material.BELL, Material.CRAFTING_TABLE, Material.CARTOGRAPHY_TABLE, Material.FLETCHING_TABLE,
            Material.LECTERN, Material.LOOM, Material.COMPOSTER, Material.SCAFFOLDING, Material.BOOKSHELF,
            Material.CHISELED_BOOKSHELF, Material.NOTE_BLOCK, Material.JUKEBOX, Material.IRON_BARS, Material.HAY_BLOCK,
            Material.DECORATED_POT, Material.TRIPWIRE_HOOK, Material.LEVER, Material.BAMBOO_BLOCK,
            Material.STRIPPED_BAMBOO_BLOCK, Material.BAMBOO_MOSAIC, Material.BAMBOO_MOSAIC_SLAB,
            Material.BAMBOO_MOSAIC_STAIRS, Material.GLASS_PANE, Material.BLAST_FURNACE);

    static {
        // names that differ between Minecraft versions
        for (String n : new String[]{"CHAIN", "IRON_CHAIN"}) {
            Material m = Material.matchMaterial(n);
            if (m != null) DECOR.add(m);
        }
    }

    private static final Set<Material> EXTRA = EnumSet.noneOf(Material.class);

    /** Extra allowed blocks from the config (ship-extra-blocks). */
    public static void loadExtra(java.util.List<String> names) {
        EXTRA.clear();
        if (names == null) return;
        for (String n : names) {
            Material m = Material.matchMaterial(n);
            if (m != null) EXTRA.add(m);
        }
    }

    /** Ships are made of wood and wool (for sails and flags), plus decorations like barrels and banners. */
    public static boolean isAllowedOnShip(Material t) {
        return Tag.PLANKS.isTagged(t) || Tag.LOGS.isTagged(t) || Tag.WOODEN_SLABS.isTagged(t)
                || Tag.WOODEN_STAIRS.isTagged(t) || Tag.WOODEN_FENCES.isTagged(t) || Tag.FENCE_GATES.isTagged(t)
                || Tag.WOODEN_DOORS.isTagged(t) || Tag.WOODEN_TRAPDOORS.isTagged(t) || Tag.WOODEN_BUTTONS.isTagged(t)
                || Tag.WOODEN_PRESSURE_PLATES.isTagged(t) || Tag.ALL_SIGNS.isTagged(t) || Tag.WOOL.isTagged(t)
                || Tag.WOOL_CARPETS.isTagged(t) || Tag.BANNERS.isTagged(t) || Tag.BEDS.isTagged(t)
                || Tag.CANDLES.isTagged(t) || Tag.FLOWER_POTS.isTagged(t)
                || t.name().endsWith("STAINED_GLASS_PANE") || DECOR.contains(t) || EXTRA.contains(t);
    }

    /** Blocks that join up into a ship when scanning. */
    public static boolean isShipBlock(Material t) {
        return !isAirLike(t) && !isWaterLike(t) && !isNatural(t) && isAllowedOnShip(t);
    }
}
