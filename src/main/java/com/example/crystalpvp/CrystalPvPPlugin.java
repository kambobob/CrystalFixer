package com.example.crystalpvp;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.entity.ExplosionPrimeEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;

public final class CrystalPvPPlugin extends JavaPlugin implements Listener {

    private record Settings(
            boolean noFire,
            boolean removeCrystalFire,
            boolean removeAnchorFire,
            boolean cancelExplosionFire,
            boolean damageEnabled,
            double crystalMultiplier,
            double anchorMultiplier,
            boolean playersOnly
    ) {}

    // volatile: events run on many region threads under Folia
    private volatile Settings settings;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("CrystalPvP enabled.");
    }

    private void loadSettings() {
        reloadConfig();
        FileConfiguration c = getConfig();

        double crystal = Math.max(0.0, c.getDouble("damage.crystal-multiplier", 1.5));
        double anchor = Math.max(0.0, c.getDouble("damage.anchor-multiplier", 1.5));

        settings = new Settings(
                c.getBoolean("no-fire.enabled", true),
                c.getBoolean("no-fire.remove-crystal-fire", true),
                c.getBoolean("no-fire.remove-anchor-fire", true),
                c.getBoolean("no-fire.cancel-explosion-fire", true),
                c.getBoolean("damage.enabled", true),
                crystal,
                anchor,
                c.getBoolean("damage.players-only", false)
        );
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("crystalpvp.reload")) {
                sender.sendMessage(Component.text("You don't have permission."));
                return true;
            }
            loadSettings();
            sender.sendMessage(Component.text("CrystalPvP config reloaded."));
            return true;
        }
        return false;
    }

    // ---------------------------------------------------------------
    // No fire
    // ---------------------------------------------------------------

    /** Crystal explosions never create fire. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onExplosionPrime(ExplosionPrimeEvent event) {
        Settings s = settings;
        if (s.noFire() && event.getEntity() instanceof EnderCrystal) {
            event.setFire(false);
        }
    }

    /** Cancels fire created by explosions, where the server reports it as an ignite event. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent event) {
        Settings s = settings;
        if (s.noFire() && s.cancelExplosionFire()
                && event.getCause() == BlockIgniteEvent.IgniteCause.EXPLOSION) {
            event.setCancelled(true);
        }
    }

    /**
     * Respawn anchor explosions place fire only on blocks they just destroyed.
     * Record those positions and clear any fire there one tick later.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        Settings s = settings;
        if (!s.noFire() || !s.removeAnchorFire()) {
            return;
        }
        if (event.getExplodedBlockState().getType() != Material.RESPAWN_ANCHOR) {
            return;
        }

        Block origin = event.getBlock();
        World world = origin.getWorld();

        List<int[]> positions = new ArrayList<>(event.blockList().size() + 1);
        positions.add(new int[]{origin.getX(), origin.getY(), origin.getZ()});
        for (Block b : event.blockList()) {
            positions.add(new int[]{b.getX(), b.getY(), b.getZ()});
        }

        Bukkit.getRegionScheduler().runDelayed(this, origin.getLocation(), task -> {
            for (int[] p : positions) {
                if (!Bukkit.isOwnedByCurrentRegion(world, p[0] >> 4, p[2] >> 4)) {
                    continue;
                }
                Block b = world.getBlockAt(p[0], p[1], p[2]);
                if (Tag.FIRE.isTagged(b.getType())) {
                    b.setType(Material.AIR, false);
                }
            }
        }, 1L);
    }

    /**
     * In the End, vanilla crystals place a fire block at their own position
     * every tick. There is no event for that, so we watch crystals that
     * players place and clear the fire.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCrystalPlace(EntityPlaceEvent event) {
        Settings s = settings;
        if (!s.noFire() || !s.removeCrystalFire()) {
            return;
        }
        if (!(event.getEntity() instanceof EnderCrystal crystal)) {
            return;
        }
        if (crystal.getWorld().getEnvironment() != World.Environment.THE_END) {
            return;
        }

        Bukkit.getRegionScheduler().runDelayed(this, crystal.getLocation(),
                t -> watchCrystal(crystal), 1L);
    }

    private void watchCrystal(EnderCrystal crystal) {
        crystal.getScheduler().runAtFixedRate(this, task -> {
            if (!crystal.isValid()) {
                task.cancel();
                return;
            }
            Settings s = settings;
            if (!s.noFire() || !s.removeCrystalFire()) {
                return;
            }
            Block block = crystal.getLocation().getBlock();
            if (Tag.FIRE.isTagged(block.getType())) {
                block.setType(Material.AIR, false);
            }
        }, null, 1L, 1L);
    }

    // ---------------------------------------------------------------
    // Damage buff
    // ---------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        Settings s = settings;
        if (!s.damageEnabled()) {
            return;
        }
        if (!(event.getEntity() instanceof LivingEntity)) {
            return;
        }
        if (s.playersOnly() && !(event.getEntity() instanceof Player)) {
            return;
        }

        DamageSource source = event.getDamageSource();
        double multiplier;

        if (source.getDirectEntity() instanceof EnderCrystal) {
            multiplier = s.crystalMultiplier();
        } else if (source.getDamageType().equals(DamageType.BAD_RESPAWN_POINT)) {
            multiplier = s.anchorMultiplier();
        } else {
            return;
        }

        if (multiplier == 1.0) {
            return;
        }

        event.setDamage(event.getDamage() * multiplier);
    }
}
