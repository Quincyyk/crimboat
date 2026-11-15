package com.aquaticsmp.test;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.logging.Logger;

/**
 * Sends the "turn" update for every block of a ship in ONE bundle packet.
 *
 * Why: if 300 block models get their update in separate packets, the player's game
 * can apply half of them one frame and the rest the next frame - that's the
 * "blocks separate and stick back together" glitch. A bundle is applied all at once.
 *
 * Uses server internals through reflection. If anything fails it quietly falls back to
 * normal updates (still works, just might glitch sometimes).
 */
public final class PacketBundler {

    private static boolean tried;
    private static boolean ready;
    private static Method entityHandle;
    private static Method getEntityData;
    private static Method packDirty;
    private static Method getId;
    private static Constructor<?> dataPacket;
    private static Constructor<?> bundlePacket;
    private static Method playerHandle;
    private static Field connectionField;
    private static Method send;

    private PacketBundler() {}

    private static void init(Entity sampleEntity, Player samplePlayer, Logger log) {
        tried = true;
        try {
            entityHandle = sampleEntity.getClass().getMethod("getHandle");
            Object nms = entityHandle.invoke(sampleEntity);
            Class<?> nmsEntity = Class.forName("net.minecraft.world.entity.Entity");
            getEntityData = nmsEntity.getMethod("getEntityData");
            getId = nmsEntity.getMethod("getId");
            Object data = getEntityData.invoke(nms);
            packDirty = data.getClass().getMethod("packDirty");
            dataPacket = Class.forName("net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket")
                    .getConstructor(int.class, List.class);
            bundlePacket = Class.forName("net.minecraft.network.protocol.game.ClientboundBundlePacket")
                    .getConstructor(Iterable.class);

            playerHandle = samplePlayer.getClass().getMethod("getHandle");
            Object sp = playerHandle.invoke(samplePlayer);
            connectionField = sp.getClass().getField("connection");
            Class<?> packetC = Class.forName("net.minecraft.network.protocol.Packet");
            send = connectionField.getType().getMethod("send", packetC);
            ready = true;
            log.info("Glued ship turning enabled (bundled updates).");
        } catch (Throwable t) {
            ready = false;
            log.warning("Bundled ship updates unavailable (" + t + "), using normal updates.");
        }
    }

    /**
     * Call right after changing the transformation of all entities.
     * Takes their pending changes and sends them together to nearby players.
     */
    public static void flush(Collection<? extends Entity> entities, Location center, double range, Logger log) {
        if (entities.isEmpty()) return;
        List<Player> viewers = new ArrayList<>();
        double r2 = range * range;
        for (Player p : center.getWorld().getPlayers()) {
            if (p.getLocation().distanceSquared(center) <= r2) viewers.add(p);
        }
        if (viewers.isEmpty()) return;
        if (!tried) init(entities.iterator().next(), viewers.get(0), log);
        if (!ready) return;
        try {
            List<Object> packets = new ArrayList<>(entities.size());
            for (Entity e : entities) {
                Object nms = entityHandle.invoke(e);
                Object data = getEntityData.invoke(nms);
                Object dirty = packDirty.invoke(data); // also clears it, so the normal update won't double-send
                if (dirty == null) continue;
                int id = (int) getId.invoke(nms);
                packets.add(dataPacket.newInstance(id, dirty));
            }
            if (packets.isEmpty()) return;
            Object bundle = bundlePacket.newInstance(packets);
            for (Player p : viewers) {
                Object conn = connectionField.get(playerHandle.invoke(p));
                send.invoke(conn, bundle);
            }
        } catch (Throwable t) {
            ready = false;
            log.warning("Bundled ship updates failed (" + t + "), switching to normal updates.");
        }
    }
}
