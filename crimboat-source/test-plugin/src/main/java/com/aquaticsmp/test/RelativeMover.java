package com.aquaticsmp.test;

import io.papermc.paper.entity.TeleportFlag;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Moves a player by a small offset WITHOUT snapping their camera or cancelling
 * their walking (a "relative" teleport, like /tp ~ ~ ~). This is what makes
 * walking around on a moving ship smooth.
 *
 * Uses server internals through reflection; if that fails it falls back to a
 * normal teleport (works, just less smooth).
 */
public final class RelativeMover {

    private static boolean tried;
    private static boolean ready;
    private static Method getHandle;
    private static Field connectionField;
    private static Method internalTeleport;
    private static Constructor<?> vec3;
    private static Constructor<?> pmr;
    private static Object vecZero;
    private static Set<Object> relatives;

    private RelativeMover() {}

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void init(Player sample, Logger log) {
        tried = true;
        try {
            getHandle = sample.getClass().getMethod("getHandle");
            Object handle = getHandle.invoke(sample);
            connectionField = handle.getClass().getField("connection");
            Class<?> conn = connectionField.getType();
            Class<?> vec3C = Class.forName("net.minecraft.world.phys.Vec3");
            Class<?> pmrC = Class.forName("net.minecraft.world.entity.PositionMoveRotation");
            Class relC = Class.forName("net.minecraft.world.entity.Relative");
            vec3 = vec3C.getConstructor(double.class, double.class, double.class);
            pmr = pmrC.getConstructor(vec3C, vec3C, float.class, float.class);
            vecZero = vec3.newInstance(0d, 0d, 0d);
            relatives = new HashSet<>();
            for (String n : new String[]{"X", "Y", "Z", "Y_ROT", "X_ROT"}) relatives.add(Enum.valueOf(relC, n));
            for (String n : new String[]{"DELTA_X", "DELTA_Y", "DELTA_Z", "ROTATE_DELTA"}) {
                try {
                    relatives.add(Enum.valueOf(relC, n));
                } catch (IllegalArgumentException ignored) {
                    // older/newer versions
                }
            }
            try {
                internalTeleport = conn.getMethod("internalTeleport", pmrC, Set.class);
            } catch (NoSuchMethodException e) {
                internalTeleport = conn.getMethod("teleport", pmrC, Set.class);
            }
            ready = true;
            log.info("Smooth deck walking enabled.");
        } catch (Throwable t) {
            ready = false;
            log.warning("Smooth deck walking unavailable (" + t + "), using normal teleports.");
        }
    }

    /** Shift the player by (dx,dy,dz) and turn their view by dYaw degrees. */
    public static void move(Player p, double dx, double dy, double dz, float dYaw, Logger log) {
        if (!tried) init(p, log);
        if (ready) {
            try {
                Object handle = getHandle.invoke(p);
                Object conn = connectionField.get(handle);
                Object pos = pmr.newInstance(vec3.newInstance(dx, dy, dz), vecZero, dYaw, 0f);
                internalTeleport.invoke(conn, pos, relatives);
                return;
            } catch (Throwable t) {
                ready = false;
                log.warning("Smooth deck walking failed (" + t + "), switching to normal teleports.");
            }
        }
        Location l = p.getLocation().add(dx, dy, dz);
        l.setYaw(l.getYaw() + dYaw);
        p.teleport(l, TeleportFlag.Relative.VELOCITY_X, TeleportFlag.Relative.VELOCITY_Y,
                TeleportFlag.Relative.VELOCITY_Z, TeleportFlag.Relative.VELOCITY_ROTATION);
    }
}
