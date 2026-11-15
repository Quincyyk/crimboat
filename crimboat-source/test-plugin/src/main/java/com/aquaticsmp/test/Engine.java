package com.aquaticsmp.test;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.TileState;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;

/**
 * Ship Engines, tier 1 (easy) to tier 5 (diamond). Placed as a Blast Furnace with a hidden tier tag.
 * Each tier is crafted from the tier below it, so you upgrade your engine step by step.
 */
public final class Engine {

    public static final Material TYPE = Material.BLAST_FURNACE;
    public static final int MAX_TIER = 5;

    private static final String[] NAMES = {"", "Copper Engine", "Iron Engine", "Gold Engine", "Blaze Engine", "Diamond Engine"};
    private static final String[] ROMAN = {"", "I", "II", "III", "IV", "V"};
    /** Gradient colors per tier (start, end). */
    private static final String[][] GRADIENT = {
            {"#FFFFFF", "#FFFFFF"},
            {"#B4532A", "#F0A877"},   // copper
            {"#9E9E9E", "#FFFFFF"},   // iron
            {"#FFAA00", "#FFF07A"},   // gold
            {"#FF3D00", "#FFC300"},   // blaze
            {"#2EF2FF", "#A97BFF"}};  // diamond
    private static final TextColor[] COLORS = {NamedTextColor.WHITE, TextColor.color(0xE07A3E),
            TextColor.color(0xD8D8D8), NamedTextColor.GOLD, TextColor.color(0xFF7A00), NamedTextColor.AQUA};
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private static final String NORMAL = "abcdefghijklmnopqrstuvwxyz";
    private static final String SMALL = "ᴀʙᴄᴅᴇꜰɢʜɪᴊᴋʟᴍɴᴏᴘǫʀꜱᴛᴜᴠᴡxʏᴢ";

    /** "Copper Engine" -> "ᴄᴏᴘᴘᴇʀ ᴇɴɢɪɴᴇ" (fancy small-caps font, works without a resource pack). */
    public static String smallCaps(String in) {
        StringBuilder b = new StringBuilder();
        for (char c : in.toLowerCase().toCharArray()) {
            int i = NORMAL.indexOf(c);
            b.append(i >= 0 ? SMALL.charAt(i) : c);
        }
        return b.toString();
    }

    /** Colored, gradient, small-caps name for an engine tier. */
    public static Component display(int tier) {
        tier = clamp(tier);
        String g = GRADIENT[tier][0] + ":" + GRADIENT[tier][1];
        String bold = tier >= 4 ? "<bold>" : "";
        String icon = tier == 5 ? "✦ " : "⚙ ";
        return MM.deserialize("<!italic>" + bold + "<gradient:" + g + ">" + icon + smallCaps(NAMES[tier])
                + "</gradient>");
    }

    public static TextColor color(int tier) {
        return COLORS[clamp(tier)];
    }

    private static NamespacedKey key;
    private static TestPlugin plugin;

    private Engine() {}

    public static void init(TestPlugin p) {
        plugin = p;
        key = new NamespacedKey(p, "engine"); // value = tier (old engines saved 1 = tier 1)
    }

    public static String name(int tier) {
        return "Tier " + tier + " " + NAMES[clamp(tier)];
    }

    private static int clamp(int tier) {
        return Math.max(1, Math.min(MAX_TIER, tier));
    }

    public static ItemStack createItem(int tier, int amount) {
        tier = clamp(tier);
        ItemStack item = new ItemStack(TYPE, amount);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(display(tier));
        List<Component> lore = new ArrayList<>();
        lore.add(MM.deserialize("<!italic><dark_gray>" + smallCaps("tier") + " </dark_gray>"
                + "<gradient:" + GRADIENT[tier][0] + ":" + GRADIENT[tier][1] + "><bold>" + ROMAN[tier] + "</bold></gradient>"));
        lore.add(Component.empty());
        lore.add(Component.text(smallCaps("speed") + "  ", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
                .append(Component.text("★".repeat(tier), COLORS[tier]))
                .append(Component.text("★".repeat(MAX_TIER - tier), NamedTextColor.DARK_GRAY)));
        lore.add(MM.deserialize(String.format("<!italic><gray>" + smallCaps("small ship") + "  <white>%.0f</white> blocks/s",
                speedSmall(tier) * 20)));
        lore.add(MM.deserialize(String.format("<!italic><gray>" + smallCaps("huge ship") + "   <white>%.0f</white> blocks/s",
                speedBig(tier) * 20)));
        lore.add(MM.deserialize("<!italic><gray>" + smallCaps("max size") + "   <white>" + maxShipSize(tier) + "</white> blocks"));
        lore.add(Component.empty());
        lore.add(line("Place on blocks floating on water.", NamedTextColor.DARK_GRAY));
        lore.add(line("Stair = Pirate Chair  •  Slabs = seats", NamedTextColor.DARK_GRAY));
        if (tier < MAX_TIER) {
            lore.add(line("Upgrade: craft it into a " + NAMES[tier + 1], NamedTextColor.DARK_AQUA));
        } else {
            lore.add(MM.deserialize("<!italic><gradient:#2EF2FF:#A97BFF>✦ " + smallCaps("max tier") + " ✦</gradient>"));
        }
        meta.lore(lore);
        if (tier == MAX_TIER) meta.setEnchantmentGlintOverride(true);
        meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) tier);
        item.setItemMeta(meta);
        return item;
    }

    private static Component line(String s, TextColor c) {
        return Component.text(s, c).decoration(TextDecoration.ITALIC, false);
    }

    /** 0 if not an engine item, otherwise its tier. */
    public static int itemTier(ItemStack item) {
        if (item == null || item.getType() != TYPE || !item.hasItemMeta()) return 0;
        Byte b = item.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.BYTE);
        return b == null ? 0 : clamp(b);
    }

    public static boolean isEngineItem(ItemStack item) {
        return itemTier(item) > 0;
    }

    /** 0 if not an engine block, otherwise its tier. */
    public static int blockTier(Block b) {
        if (b.getType() != TYPE) return 0;
        BlockState st = b.getState();
        if (!(st instanceof TileState ts)) return 0;
        Byte v = ts.getPersistentDataContainer().get(key, PersistentDataType.BYTE);
        return v == null ? 0 : clamp(v);
    }

    public static boolean isEngineBlock(Block b) {
        return blockTier(b) > 0;
    }

    /** Tag the tile state with a tier (0 = not an engine). Caller must call state.update(). */
    public static void setEngineTag(BlockState st, int tier) {
        if (!(st instanceof TileState ts)) return;
        if (tier > 0) ts.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) clamp(tier));
        else ts.getPersistentDataContainer().remove(key);
    }

    // ------------------------------------------------------------------ ship health (stored on the engine block)

    private static NamespacedKey hpKey() {
        return new NamespacedKey(plugin, "ship_hp");
    }

    /** Saved ship health on this engine block, or -1 if never damaged. */
    public static int getHp(Block b) {
        if (b.getType() != TYPE) return -1;
        BlockState st = b.getState();
        if (!(st instanceof TileState ts)) return -1;
        Integer v = ts.getPersistentDataContainer().get(hpKey(), PersistentDataType.INTEGER);
        return v == null ? -1 : v;
    }

    /** Store ship health (-1 = remove). Caller must call state.update(). */
    public static void setHp(BlockState st, int hp) {
        if (!(st instanceof TileState ts)) return;
        if (hp < 0) ts.getPersistentDataContainer().remove(hpKey());
        else ts.getPersistentDataContainer().set(hpKey(), PersistentDataType.INTEGER, hp);
    }

    // ------------------------------------------------------------------ speeds (blocks per tick)

    /** Biggest ship this engine can move. Tier 1 = 500 blocks ... Tier 5 = 2000 blocks. */
    public static int maxShipSize(int tier) {
        int[] def = {0, 500, 875, 1250, 1625, 2000};
        return plugin == null ? def[clamp(tier)]
                : plugin.getConfig().getInt("engines.tier-" + clamp(tier) + ".max-ship-size", def[clamp(tier)]);
    }

    /** The biggest size any engine allows. */
    public static int maxShipSizeAny() {
        int m = 0;
        for (int t = 1; t <= MAX_TIER; t++) m = Math.max(m, maxShipSize(t));
        return m;
    }

    public static double speedSmall(int tier) {
        double[] def = {0, 0.6, 0.7, 0.8, 0.9, 1.0};
        return cfg("tier-" + clamp(tier) + ".speed-small-ship", def[clamp(tier)]);
    }

    public static double speedBig(int tier) {
        double[] def = {0, 0.45, 0.5, 0.56, 0.63, 0.85};
        return cfg("tier-" + clamp(tier) + ".speed-big-ship", def[clamp(tier)]);
    }

    private static double cfg(String k, double def) {
        return plugin == null ? def : plugin.getConfig().getDouble("engines." + k, def);
    }

    // ------------------------------------------------------------------ recipes

    private static NamespacedKey recipeKey(int tier) {
        return new NamespacedKey(plugin, "engine_tier_" + tier);
    }

    public static void registerRecipes() {
        // Tier 1 - Copper: easy, early game
        ShapedRecipe r1 = new ShapedRecipe(recipeKey(1), createItem(1, 1));
        r1.shape("CCC", "CFC", "CRC");
        r1.setIngredient('C', Material.COPPER_INGOT);
        r1.setIngredient('F', Material.FURNACE);
        r1.setIngredient('R', Material.REDSTONE);
        Bukkit.addRecipe(r1);

        // Tier 2 - Iron: upgrade a Copper Engine
        ShapedRecipe r2 = new ShapedRecipe(recipeKey(2), createItem(2, 1));
        r2.shape("III", "IEI", "IRI");
        r2.setIngredient('I', Material.IRON_INGOT);
        r2.setIngredient('E', new RecipeChoice.MaterialChoice(TYPE));
        r2.setIngredient('R', Material.REDSTONE_BLOCK);
        Bukkit.addRecipe(r2);

        // Tier 3 - Gold: upgrade an Iron Engine
        ShapedRecipe r3 = new ShapedRecipe(recipeKey(3), createItem(3, 1));
        r3.shape("GGG", "GEG", "PRP");
        r3.setIngredient('G', Material.GOLD_INGOT);
        r3.setIngredient('E', new RecipeChoice.MaterialChoice(TYPE));
        r3.setIngredient('P', Material.PISTON);
        r3.setIngredient('R', Material.REDSTONE_BLOCK);
        Bukkit.addRecipe(r3);

        // Tier 4 - Blaze: upgrade a Gold Engine (needs the Nether)
        ShapedRecipe r4 = new ShapedRecipe(recipeKey(4), createItem(4, 1));
        r4.shape("BIB", "IEI", "BRB");
        r4.setIngredient('B', Material.BLAZE_ROD);
        r4.setIngredient('I', Material.IRON_BLOCK);
        r4.setIngredient('E', new RecipeChoice.MaterialChoice(TYPE));
        r4.setIngredient('R', Material.REDSTONE_BLOCK);
        Bukkit.addRecipe(r4);

        // Tier 5 - Diamond: upgrade a Blaze Engine, mostly diamonds
        ShapedRecipe r5 = new ShapedRecipe(recipeKey(5), createItem(5, 1));
        r5.shape("DDD", "DED", "DBD");
        r5.setIngredient('D', Material.DIAMOND);
        r5.setIngredient('E', new RecipeChoice.MaterialChoice(TYPE));
        r5.setIngredient('B', Material.DIAMOND_BLOCK);
        Bukkit.addRecipe(r5);
    }

    /** "engine_tier_3" -> 3, or 0 if it's not one of our upgrade recipes. */
    public static int upgradeRecipeTier(NamespacedKey key) {
        if (key == null || plugin == null || !key.getNamespace().equals(plugin.getName().toLowerCase())) return 0;
        String k = key.getKey();
        if (!k.startsWith("engine_tier_")) return 0;
        try {
            return Integer.parseInt(k.substring("engine_tier_".length()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public static void unregisterRecipes() {
        if (plugin == null) return;
        for (int t = 1; t <= MAX_TIER; t++) Bukkit.removeRecipe(recipeKey(t));
    }
}
