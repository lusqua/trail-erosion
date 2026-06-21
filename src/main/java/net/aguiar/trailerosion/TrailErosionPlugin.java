package net.aguiar.trailerosion;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * "Desire paths": blocks gradually erode into worn variants as players walk over them, and
 * slowly revert when left alone. Server-side recreation of "The Roads More Travelled", built
 * for Folia (and no-op-compatible on plain Paper).
 *
 * <p>See the Folia threading notes in {@link MoveListener} and {@link #sweep()} — every block
 * mutation is scheduled on the region that owns the block's location.
 */
public final class TrailErosionPlugin extends JavaPlugin implements CommandExecutor {

    /** Bounded radius (blocks) for {@code /trailerosion convert-to-vanilla}. */
    private static final int CONVERT_RADIUS = 64;

    /** world UID -> (packed block key -> progress). All maps are concurrent: many region threads write. */
    private final Map<UUID, ConcurrentHashMap<Long, BlockProgress>> perWorld = new ConcurrentHashMap<>();

    // Config snapshot. volatile so the (many) listener threads see reloads without locking.
    private volatile int stepsPerStage;
    private volatile Map<Material, Integer> stepsByRoot; // per-chain override of stepsPerStage
    private volatile double thresholdJitter;
    private volatile double mountedMultiplier;
    private volatile long revertAfterMillis;
    private volatile int revertScanIntervalSeconds;
    private volatile Set<String> disabledWorlds;
    private volatile ErosionChains chains;

    private ScheduledTask sweepTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfigValues();

        getServer().getPluginManager().registerEvents(new MoveListener(this), this);
        if (getCommand("trailerosion") != null) {
            getCommand("trailerosion").setExecutor(this);
        }
        startSweep();

        getLogger().info("TrailErosion enabled — " + chains.chainCount() + " erosion chain(s) active.");
    }

    @Override
    public void onDisable() {
        if (sweepTask != null) {
            sweepTask.cancel();
        }
    }

    // ---- config ----------------------------------------------------------------

    private void loadConfigValues() {
        reloadConfig();
        stepsPerStage = Math.max(1, getConfig().getInt("stepsPerStage", 8));
        stepsByRoot = parseStepsByRoot(getConfig().getConfigurationSection("stepsPerStageByMaterial"));
        thresholdJitter = Math.max(0.0, getConfig().getDouble("thresholdJitter", 0.4));
        mountedMultiplier = Math.max(1.0, getConfig().getDouble("mountedMultiplier", 2.0));
        revertAfterMillis = Math.max(1L, getConfig().getLong("revertAfterMinutes", 20)) * 60_000L;
        revertScanIntervalSeconds = Math.max(1, getConfig().getInt("revertScanIntervalSeconds", 60));
        disabledWorlds = Set.copyOf(getConfig().getStringList("disabledWorlds"));
        chains = ErosionChains.fromConfig(getConfig().getConfigurationSection("chains"), getLogger());
        if (chains.isEmpty()) {
            getLogger().warning("No valid erosion chains configured — the plugin will do nothing.");
        }
    }

    /** Parse the optional per-chain step overrides ({@code stepsPerStageByMaterial}). */
    private Map<Material, Integer> parseStepsByRoot(org.bukkit.configuration.ConfigurationSection section) {
        Map<Material, Integer> out = new java.util.EnumMap<>(Material.class);
        if (section == null) {
            return out;
        }
        for (String name : section.getKeys(false)) {
            Material m = Material.matchMaterial(name);
            if (m == null) {
                getLogger().warning("[stepsPerStageByMaterial] unknown material '" + name + "' — skipped");
                continue;
            }
            out.put(m, Math.max(1, section.getInt(name)));
        }
        return out;
    }

    // Accessors used by the listener (hot path).
    ErosionChains chains() {
        return chains;
    }

    double mountedMultiplier() {
        return mountedMultiplier;
    }

    boolean isWorldDisabled(String name) {
        return disabledWorlds.contains(name);
    }

    ConcurrentHashMap<Long, BlockProgress> worldMap(UUID worldId) {
        return perWorld.computeIfAbsent(worldId, k -> new ConcurrentHashMap<>());
    }

    /**
     * Random per-block threshold for advancing one stage in the chain rooted at {@code root}:
     * the chain's base step count (its override, or the global {@code stepsPerStage}) scaled by
     * +/- {@code thresholdJitter}.
     */
    int rollThreshold(Material root) {
        int base = stepsByRoot.getOrDefault(root, stepsPerStage);
        double factor = 1.0 + thresholdJitter * (2.0 * ThreadLocalRandom.current().nextDouble() - 1.0);
        return Math.max(1, (int) Math.round(base * factor));
    }

    // ---- revert sweep ----------------------------------------------------------

    private void startSweep() {
        long periodTicks = (long) revertScanIntervalSeconds * 20L;
        // Folia: the global region scheduler only drives the timer. Per-block work hops to the
        // owning region inside sweep() — we never mutate blocks directly on this thread.
        sweepTask = getServer().getGlobalRegionScheduler()
                .runAtFixedRate(this, task -> sweep(), periodTicks, periodTicks);
    }

    private void restartSweep() {
        if (sweepTask != null) {
            sweepTask.cancel();
        }
        startSweep();
    }

    private void sweep() {
        long now = System.currentTimeMillis();
        long idle = revertAfterMillis;

        for (Map.Entry<UUID, ConcurrentHashMap<Long, BlockProgress>> we : perWorld.entrySet()) {
            World world = getServer().getWorld(we.getKey());
            ConcurrentHashMap<Long, BlockProgress> map = we.getValue();
            if (world == null) {
                continue; // world not loaded right now; leave entries for when it returns
            }
            for (Map.Entry<Long, BlockProgress> e : map.entrySet()) {
                long key = e.getKey();
                BlockProgress bp = e.getValue();

                // Self-cleaning / stale prune: original-stage entries with no pending progress.
                if (bp.stage <= 0) {
                    if (bp.steps <= 0 || now - bp.lastStepMillis > idle) {
                        map.remove(key, bp);
                    }
                    continue;
                }
                if (now - bp.lastStepMillis < idle) {
                    continue; // walked recently enough; not idle yet
                }
                int cx = BlockKey.x(key) >> 4;
                int cz = BlockKey.z(key) >> 4;
                if (!world.isChunkLoaded(cx, cz)) {
                    continue; // only operate on loaded chunks; never force-load
                }
                Location loc = new Location(world, BlockKey.x(key), BlockKey.y(key), BlockKey.z(key));
                // Folia: hop to the region owning this block before touching it.
                getServer().getRegionScheduler().execute(this, loc, () -> revertOneStage(map, key, bp, loc));
            }
        }
    }

    /** Runs on the region thread owning {@code loc}: step the block one stage back toward original. */
    private void revertOneStage(ConcurrentHashMap<Long, BlockProgress> map, long key, BlockProgress bp, Location loc) {
        int stage = bp.stage;
        if (stage <= 0) {
            map.remove(key, bp);
            return;
        }
        List<Material> chain = chains.stages(bp.root);
        if (chain == null) {
            map.remove(key, bp);
            return;
        }
        Block b = loc.getBlock();
        if (b.getType() != chain.get(stage)) {
            map.remove(key, bp); // world changed out from under us
            return;
        }
        int newStage = stage - 1;
        b.setType(chain.get(newStage), false);
        bp.stage = newStage;
        bp.steps = 0;
        bp.lastStepMillis = System.currentTimeMillis(); // gradual: each step-down waits another interval
        if (newStage <= 0) {
            map.remove(key, bp);
        }
    }

    // ---- commands --------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("§e/trailerosion <reload|convert-to-vanilla>");
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "reload" -> {
                loadConfigValues();
                restartSweep();
                sender.sendMessage("§aTrailErosion config reloaded — " + chains.chainCount() + " chain(s) active.");
            }
            case "convert-to-vanilla" -> convertToVanilla(sender);
            default -> sender.sendMessage("§e/trailerosion <reload|convert-to-vanilla>");
        }
        return true;
    }

    /** Reset tracked blocks near the sender back to their stage-0 material (bounded radius). */
    private void convertToVanilla(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cThis command must be run by a player.");
            return;
        }
        World world = player.getWorld();
        ConcurrentHashMap<Long, BlockProgress> map = perWorld.get(world.getUID());
        if (map == null || map.isEmpty()) {
            sender.sendMessage("§eNo tracked blocks in this world.");
            return;
        }
        int px = player.getLocation().getBlockX();
        int pz = player.getLocation().getBlockZ();
        int count = 0;
        for (Long key : map.keySet()) {
            int x = BlockKey.x(key);
            int z = BlockKey.z(key);
            if (Math.abs(x - px) > CONVERT_RADIUS || Math.abs(z - pz) > CONVERT_RADIUS) {
                continue;
            }
            BlockProgress bp = map.get(key);
            if (bp == null) {
                continue;
            }
            if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                continue;
            }
            List<Material> chain = chains.stages(bp.root);
            if (chain == null) {
                map.remove(key, bp);
                continue;
            }
            Material original = chain.get(0);
            Location loc = new Location(world, x, BlockKey.y(key), z);
            getServer().getRegionScheduler().execute(this, loc, () -> {
                loc.getBlock().setType(original, false);
                map.remove(key, bp);
            });
            count++;
        }
        sender.sendMessage("§aConverting " + count + " block(s) back to vanilla within " + CONVERT_RADIUS + " blocks.");
    }
}
