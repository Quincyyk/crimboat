package com.aquaticsmp.test;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

/** test - simple custom ship plugin for Aquatic SMP. */
public final class TestPlugin extends JavaPlugin {

    private SeatManager seats;
    private ShipManager ships;
    private Cannons cannons;
    private ShipCombat combat;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        Engine.init(this);
        seats = new SeatManager(this);
        ships = new ShipManager(this);
        cannons = new Cannons(this);
        combat = new ShipCombat(this);
        Materials.loadExtra(getConfig().getStringList("ship-extra-blocks"));

        getServer().getPluginManager().registerEvents(new ShipListener(this), this);
        PluginCommand cmd = getCommand("crimboat");
        if (cmd != null) {
            ShipCommand exec = new ShipCommand(this);
            cmd.setExecutor(exec);
            cmd.setTabCompleter(exec);
        }

        Engine.registerRecipes();
        cannons.registerRecipes();
        seats.removeAll(); // clean leftover seats
        ships.cleanupLeftovers();
        ships.start();
        cannons.start();
        com.aquaticsmp.test.api.CrimboatApi.enable(this);
        getLogger().info("test enabled - ships ready to sail.");
    }

    @Override
    public void onDisable() {
        com.aquaticsmp.test.api.CrimboatApi.disable();
        if (cannons != null) {
            cannons.stop();
            cannons.unregisterRecipes();
        }
        if (combat != null) combat.shutdown();
        if (ships != null) ships.stopAll();
        if (seats != null) seats.removeAll();
        Engine.unregisterRecipes();
    }

    public SeatManager seats() {
        return seats;
    }

    public Cannons cannons() {
        return cannons;
    }

    public ShipCombat combat() {
        return combat;
    }

    public ShipManager ships() {
        return ships;
    }
}
