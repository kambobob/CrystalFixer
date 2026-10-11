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
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.entity.ExplosionPrimeEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class CrystalPvPPlugin extends JavaPlugin implements Listener {

    record Settings(
            boolean allowCrystalFire,
            boolean allowAnchorFire,
            boolean allowOtherExplosionFire,
            boolean damageEnabled,
            double crystalMultiplier,
            double anchorMultiplier,
            boolean playersOnly,
            boolean crystalBreakBlocks,
            boolean anchorBreakBlocks,
            boolean optimizerEnabled,
            double maxReach,
            int resyncTicks,
            boolean debug
    ) {}

    // volatile: events run on many region threads under Folia
    private volatile Settings settings;
    private CrystalOptimizer optimizer;

    Settings settings() {
        return settings;
    }

    @Override
    public void onEnable() {
        loadSettings();
        getServer().getPluginManager().registerEvents(this, this);
        setupOptimizer();
        for (String line : describe(settings)) {
            getLogger().info(line);
        }
    }

    @Override
    public void onDisable() {
        if (optimizer != null) {
            try {
                optimizer.unregister();
            } catch (Throwable ignored) {
            }
        }
    }

    // ---------------------------------------------------------------
    // Config
    // ---------------------------------------------------------------

    /** If the config is in the old format, back it up and write a fresh one. */
    private void ensureCurrentConfig() {
        File file = new File(getDataFolder(), "config.yml");
        if (file.exists()) {
            FileConfiguration existing = YamlConfiguration.loadConfiguration(file);
            if (!existing.contains("fire.allow-crystal-fire")) {
                File backup = new File(getDataFolder(), "config-old.yml");
                if (backup.exists()) {
                    backup.delete();
                }
                if (file.renameTo(backup)) {
                    getLogger().warning("Old-format config.yml found. Saved it as config-old.yml "
                            + "and created a new config.yml with the new options.");
                }
            }
        }
        saveDefaultConfig();
    }

    private void loadSettings() {
        ensureCurrentConfig();
        reloadConfig();
        FileConfiguration c = getConfig();

        settings = new Settings(
                c.getBoolean("fire.allow-crystal-fire", false),
                c.getBoolean("fire.allow-anchor-fire", false),
                c.getBoolean("fire.allow-other-explosion-fire", true),
                c.getBoolean("damage.enabled", true),
                Math.max(0.0, c.getDouble("damage.crystal-multiplier", 1.5)),
                Math.max(0.0, c.getDouble("damage.anchor-multiplier", 1.5)),
                c.getBoolean("damage.players-only", false),
                c.getBoolean("explosions.crystal-break-blocks", true),
                c.getBoolean("explosions.anchor-break-blocks", true),
                c.getBoolean("optimizer.enabled", true),
                Math.max(1.0, Math.min(4.0, c.getDouble("optimizer.max-reach", 3.4))),
                Math.max(2, Math.min(40, c.getInt("optimizer.resync-ticks", 4))),
                c.getBoolean("optimizer.debug", false)
        );
    }

    private List<String> describe(Settings s) {
        List<String> out = new ArrayList<>();
        out.add("Crystal fire (End): " + (s.allowCrystalFire() ? "ALLOWED (vanilla)" : "REMOVED"));
        out.add("Anchor fire: " + (s.allowAnchorFire() ? "ALLOWED (vanilla)" : "REMOVED"));
        out.add("Other explosion fire: " + (s.allowOtherExplosionFire() ? "ALLOWED (vanilla)" : "REMOVED"));
        out.add("Damage buff: " + (s.damageEnabled()
                ? "crystal x" + s.crystalMultiplier() + ", anchor x" + s.anchorMultiplier()
                + (s.playersOnly() ? " (players only)" : "")
                : "OFF"));
        out.add("Block damage: crystals " + (s.crystalBreakBlocks() ? "break" : "don't break")
                + " blocks, anchors " + (s.anchorBreakBlocks() ? "break" : "don't break") + " blocks");
        out.add("Optimizer: " + (optimizer == null
                ? "OFF (PacketEvents plugin not found)"
                : (s.optimizerEnabled() ? "ON, reach " + s.maxReach() : "disabled in config")));
        return out;
    }

    private void setupOptimizer() {
        if (getServer().getPluginManager().getPlugin("packetevents") == null) {
            if (settings.optimizerEnabled()) {
                getLogger().warning("Crystal optimizer is enabled in config but the PacketEvents "
                        + "plugin is not installed. Install PacketEvents 2.13.0 to use it.");
            }
            return;
        }
        try {
            optimizer = new CrystalOptimizer(this);
            optimizer.register();
        } catch (Throwable t) {
            optimizer = null;
            getLogger().warning("Could not start the crystal optimizer: " + t);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            return false;
        }
        if (!sender.hasPermission("crystalpvp.reload")) {
            sender.sendMessage(Component.text("You don't have permission."));
            return true;
        }
        if (args[0].equalsIgnoreCase("reload")) {
            loadSettings();
            sender.sendMessage(Component.text("CrystalPvP config reloaded. Active settings:"));
        } else if (!args[0].equalsIgnoreCase("status")) {
            return false;
        }
        for (String line : describe(settings)) {
            sender.sendMessage(Component.text(" - " + line));
        }
        return true;
    }

    // ---------------------------------------------------------------
    // Fire
    // ---------------------------------------------------------------

    /** Crystal explosions never ignite when crystal fire is not allowed. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onExplosionPrime(ExplosionPrimeEvent event) {
        if (event.getEntity() instanceof EnderCrystal && !settings.allowCrystalFire()) {
            event.setFire(false);
        }
    }

    /** Stops explosion fire at the source. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent event) {
        if (event.getCause() != BlockIgniteEvent.IgniteCause.EXPLOSION) {
            return;
        }
        Settings s = settings;
        Entity igniter = event.getIgnitingEntity();
        boolean cancel;
        if (igniter == null) {
            cancel = !s.allowAnchorFire(); // block explosions: respawn anchors / beds
        } else if (igniter instanceof EnderCrystal) {
            cancel = !s.allowCrystalFire();
        } else {
            cancel = !s.allowOtherExplosionFire();
        }
        if (cancel) {
            event.setCancelled(true);
        }
    }

    /** Anchors: optionally don't break blocks. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplodeBlocks(BlockExplodeEvent event) {
        if (!settings.anchorBreakBlocks()) {
            event.blockList().clear();
        }
    }

    /**
     * Backup for anchor fire: even if something places fire anyway, sweep every
     * position the explosion touched one tick later and remove fire there.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplodeCleanup(BlockExplodeEvent event) {
        if (settings.allowAnchorFire()) {
            return;
        }

        Block origin = event.getBlock();
        World world = origin.getWorld();

        Map<Long, List<int[]>> byChunk = new HashMap<>();
        addPosition(byChunk, origin.getX(), origin.getY(), origin.getZ());
        for (Block b : event.blockList()) {
            addPosition(byChunk, b.getX(), b.getY(), b.getZ());
        }

        for (Map.Entry<Long, List<int[]>> entry : byChunk.entrySet()) {
            int chunkX = (int) (entry.getKey() >> 32);
            int chunkZ = entry.getKey().intValue();
            List<int[]> positions = entry.getValue();

            Bukkit.getRegionScheduler().runDelayed(this, world, chunkX, chunkZ, task -> {
                for (int[] p : positions) {
                    Block b = world.getBlockAt(p[0], p[1], p[2]);
                    if (Tag.FIRE.isTagged(b.getType())) {
                        b.setType(Material.AIR, false);
                    }
                }
            }, 1L);
        }
    }

    private static void addPosition(Map<Long, List<int[]>> map, int x, int y, int z) {
        long key = ((long) (x >> 4) << 32) | ((z >> 4) & 0xFFFFFFFFL);
        map.computeIfAbsent(key, k -> new ArrayList<>()).add(new int[]{x, y, z});
    }

    /** Crystals: optionally don't break blocks. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        if (event.getEntity() instanceof EnderCrystal && !settings.crystalBreakBlocks()) {
            event.blockList().clear();
        }
    }

    /**
     * In the End, vanilla crystals place a fire block at their own position
     * every tick. There is no event for that, so watch crystals that players
     * place and clear the fire.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCrystalPlace(EntityPlaceEvent event) {
        if (settings.allowCrystalFire()) {
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
            if (settings.allowCrystalFire()) {
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
