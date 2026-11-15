package com.aquaticsmp.test;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * /crimboat give engine [tier] [player] [amount]
 * /crimboat check     - check the boat you're looking at
 * /crimboat reload
 */
public final class ShipCommand implements TabExecutor {

    private final TestPlugin plugin;

    public ShipCommand(TestPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(Component.text("/crimboat give <engine [tier]|cannon|cannonball> [player] [amount]  |  /crimboat check  |  /crimboat reload",
                    NamedTextColor.AQUA));
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "give" -> give(sender, args);
            case "check" -> check(sender);
            case "reload" -> {
                if (!sender.hasPermission("test.admin")) return noPerm(sender);
                plugin.reloadConfig();
                Materials.loadExtra(plugin.getConfig().getStringList("ship-extra-blocks"));
                sender.sendMessage(Component.text("test config reloaded.", NamedTextColor.GREEN));
            }
            default -> sender.sendMessage(Component.text("Unknown subcommand.", NamedTextColor.RED));
        }
        return true;
    }

    private boolean noPerm(CommandSender s) {
        s.sendMessage(Component.text("You don't have permission.", NamedTextColor.RED));
        return true;
    }

    private void give(CommandSender sender, String[] args) {
        if (!sender.hasPermission("test.admin")) {
            noPerm(sender);
            return;
        }
        String what = args.length >= 2 ? args[1].toLowerCase() : "";
        if (!what.equals("engine") && !what.equals("cannon") && !what.equals("cannonball")) {
            sender.sendMessage(Component.text("Usage: /crimboat give <engine [tier 1-5]|cannon|cannonball> [player] [amount]",
                    NamedTextColor.RED));
            return;
        }
        int i = 2;
        int tier = 1;
        if (what.equals("engine") && args.length > i) {
            try {
                tier = Math.max(1, Math.min(Engine.MAX_TIER, Integer.parseInt(args[i])));
                i++;
            } catch (NumberFormatException ignored) {
                // not a number = player name
            }
        }
        Player target;
        if (args.length > i) {
            target = Bukkit.getPlayerExact(args[i]);
            if (target == null) {
                sender.sendMessage(Component.text("Player not found.", NamedTextColor.RED));
                return;
            }
            i++;
        } else if (sender instanceof Player p) {
            target = p;
        } else {
            sender.sendMessage(Component.text("Console must name a player.", NamedTextColor.RED));
            return;
        }
        int amount = 1;
        if (args.length > i) {
            try {
                amount = Math.max(1, Math.min(64, Integer.parseInt(args[i])));
            } catch (NumberFormatException ignored) {
                // keep 1
            }
        }
        ItemStack give;
        String label;
        if (what.equals("cannon")) {
            give = Cannons.createCannonItem(amount);
            label = "Ship Cannon";
        } else if (what.equals("cannonball")) {
            give = Cannons.createOwnCannonball(amount);
            label = "Cannonball";
        } else {
            give = Engine.createItem(tier, amount);
            label = Engine.name(tier);
        }
        target.getInventory().addItem(give).values()
                .forEach(left -> target.getWorld().dropItem(target.getLocation(), left));
        sender.sendMessage(Component.text("Gave " + amount + "x " + label + " to " + target.getName() + ".",
                NamedTextColor.GREEN));
    }

    private void check(CommandSender sender) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(Component.text("Players only.", NamedTextColor.RED));
            return;
        }
        if (!p.hasPermission("test.use")) {
            noPerm(p);
            return;
        }
        Block b = p.getTargetBlockExact(6);
        if (b == null) {
            p.sendMessage(Component.text("Look at a block on your boat.", NamedTextColor.RED));
            return;
        }
        ShipScanner.Result r = ShipScanner.scan(b, plugin.ships().maxSize());
        boolean ok = r.status == ShipScanner.Status.OK;
        p.sendMessage(Component.text(r.describe(plugin.ships().maxSize()),
                ok ? NamedTextColor.GREEN : NamedTextColor.RED));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (String s : List.of("give", "check", "reload")) if (s.startsWith(args[0].toLowerCase())) out.add(s);
        } else if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            out.add("engine");
            out.add("cannon");
            out.add("cannonball");
        } else if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
            for (int t = 1; t <= Engine.MAX_TIER; t++) out.add(String.valueOf(t));
        } else if (args.length == 4 && args[0].equalsIgnoreCase("give")) {
            for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
        } else if (args.length == 5 && args[0].equalsIgnoreCase("give")) {
            out.add("1");
            out.add("16");
            out.add("64");
        }
        return out;
    }
}
