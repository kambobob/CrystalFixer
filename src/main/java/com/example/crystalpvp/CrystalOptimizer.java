package com.example.crystalpvp;

import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Crystal optimizer. When a player hits a crystal, the attack packet is seen on
 * the network thread and the crystal is removed from THAT player's screen
 * immediately, instead of waiting for the server's next tick to process the hit.
 * The server still handles the real hit normally. Everything shared between
 * threads lives in concurrent maps, so this is safe on Folia.
 */
final class CrystalOptimizer extends PacketListenerAbstract implements Listener {

    private record Tracked(EnderCrystal crystal, double x, double y, double z) {}

    private final CrystalPvPPlugin plugin;
    private final Map<Integer, Tracked> crystals = new ConcurrentHashMap<>();
    private final Map<UUID, double[]> positions = new ConcurrentHashMap<>();

    CrystalOptimizer(CrystalPvPPlugin plugin) {
        super(PacketListenerPriority.NORMAL);
        this.plugin = plugin;
    }

    void register() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        PacketEvents.getAPI().getEventManager().registerListener(this);
    }

    void unregister() {
        PacketEvents.getAPI().getEventManager().unregisterListener(this);
    }

    // ---------------- Bukkit side: keep track of crystals and players ----------------

    @EventHandler
    public void onAdd(EntityAddToWorldEvent event) {
        if (event.getEntity() instanceof EnderCrystal crystal) {
            Location l = crystal.getLocation();
            crystals.put(crystal.getEntityId(), new Tracked(crystal, l.getX(), l.getY(), l.getZ()));
        }
    }

    @EventHandler
    public void onRemove(EntityRemoveFromWorldEvent event) {
        if (event.getEntity() instanceof EnderCrystal crystal) {
            crystals.remove(crystal.getEntityId());
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Location l = event.getPlayer().getLocation();
        positions.put(event.getPlayer().getUniqueId(), new double[]{l.getX(), l.getY(), l.getZ()});
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        positions.remove(event.getPlayer().getUniqueId());
    }

    // ---------------- Packet side (network thread) ----------------

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        PacketTypeCommon type = event.getPacketType();

        // Track where the player is from their movement packets.
        if (type == PacketType.Play.Client.PLAYER_POSITION
                || type == PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION) {
            UUID id = event.getUser().getUUID();
            if (id != null) {
                Vector3d p = new WrapperPlayClientPlayerFlying(event).getLocation().getPosition();
                positions.put(id, new double[]{p.getX(), p.getY(), p.getZ()});
            }
            return;
        }

        if (type != PacketType.Play.Client.INTERACT_ENTITY) {
            return;
        }

        CrystalPvPPlugin.Settings s = plugin.settings();
        if (!s.optimizerEnabled()) {
            return;
        }

        WrapperPlayClientInteractEntity wrapper = new WrapperPlayClientInteractEntity(event);
        if (wrapper.getAction() != WrapperPlayClientInteractEntity.InteractAction.ATTACK) {
            return;
        }

        int entityId = wrapper.getEntityId();
        Tracked tracked = crystals.get(entityId);
        if (tracked == null) {
            return;
        }

        UUID uuid = event.getUser().getUUID();
        double[] pos = uuid == null ? null : positions.get(uuid);
        if (pos == null) {
            debug(s, "no known position for attacker, skipping fast removal");
            return;
        }

        // Only act when the hit is clearly valid (distance from eye to crystal hitbox).
        double ex = pos[0];
        double ey = pos[1] + 1.62;
        double ez = pos[2];
        double dx = Math.max(Math.max(tracked.x() - 1.0 - ex, 0.0), ex - (tracked.x() + 1.0));
        double dy = Math.max(Math.max(tracked.y() - ey, 0.0), ey - (tracked.y() + 2.0));
        double dz = Math.max(Math.max(tracked.z() - 1.0 - ez, 0.0), ez - (tracked.z() + 1.0));
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (dist > s.maxReach()) {
            debug(s, "crystal " + entityId + " too far (" + String.format("%.2f", dist) + "), skipping");
            return;
        }

        // Remove it from this player's screen right now.
        event.getUser().sendPacket(new WrapperPlayServerDestroyEntities(entityId));
        debug(s, "fast-removed crystal " + entityId + " for " + uuid);

        // Safety net: if the server did not actually remove it, put it back.
        Object bukkitPlayer = event.getPlayer();
        if (bukkitPlayer instanceof Player player) {
            tracked.crystal().getScheduler().runDelayed(plugin, task -> {
                if (tracked.crystal().isValid() && player.isOnline()) {
                    try {
                        player.hideEntity(plugin, tracked.crystal());
                        player.showEntity(plugin, tracked.crystal());
                        debug(plugin.settings(), "resynced crystal " + entityId + " (hit was rejected)");
                    } catch (Throwable t) {
                        debug(plugin.settings(), "resync failed: " + t);
                    }
                }
            }, null, s.resyncTicks());
        }
    }

    private void debug(CrystalPvPPlugin.Settings s, String message) {
        if (s.debug()) {
            plugin.getLogger().info("[optimizer] " + message);
        }
    }
}
