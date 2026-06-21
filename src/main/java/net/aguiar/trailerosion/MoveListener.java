package net.aguiar.trailerosion;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registers "steps" as players walk onto new block columns and schedules block transforms
 * when an erosion threshold is crossed.
 *
 * <p><b>Folia threading:</b> {@code PlayerMoveEvent} fires on the region thread that owns the
 * moving player, which also owns the player's current location — and therefore the floor block
 * directly beneath them (same chunk column). So <em>reading</em> the floor block here is safe.
 * <em>Mutating</em> it is done via {@code getRegionScheduler().execute(plugin, loc, ...)}, which
 * runs on that same region thread — never mutate a block from an arbitrary thread.
 */
final class MoveListener implements Listener {

    private final TrailErosionPlugin plugin;

    MoveListener(TrailErosionPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();

        // Camera-only moves (same block) must cost nothing — this is the hot path.
        if (from.getBlockX() == to.getBlockX()
                && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ()) {
            return;
        }

        World world = to.getWorld();
        if (world == null || plugin.isWorldDisabled(world.getName())) {
            return;
        }

        // The block the player is standing ON: the floor, one below feet level.
        Block floor = world.getBlockAt(to.getBlockX(), to.getBlockY() - 1, to.getBlockZ());
        Material mat = floor.getType();

        ErosionChains chains = plugin.chains();
        Material root = chains.rootOf(mat);
        if (root == null) {
            return; // not a chain root or mid-chain block
        }
        List<Material> chain = chains.stages(root);

        long key = BlockKey.pack(floor.getX(), floor.getY(), floor.getZ());
        ConcurrentHashMap<Long, BlockProgress> worldMap = plugin.worldMap(world.getUID());

        // Create-or-get is atomic on the concurrent map; a single block is only ever touched
        // by one region thread, so the per-progress mutations below need no extra locking.
        BlockProgress bp = worldMap.computeIfAbsent(key, k ->
                new BlockProgress(root, indexOf(chain, mat), plugin.rollThreshold(root), now()));

        int maxStage = chain.size() - 1;
        bp.lastStepMillis = now();

        if (bp.stage >= maxStage) {
            return; // already fully eroded; refreshing lastStepMillis above keeps it from reverting
        }

        double increment = isRiding(event) ? plugin.mountedMultiplier() : 1.0;
        bp.steps += increment;
        if (bp.steps < bp.threshold) {
            return;
        }

        // Threshold crossed: advance one stage. Carry the remainder so big increments don't waste steps.
        bp.steps -= bp.threshold;
        int targetStage = bp.stage + 1;
        Material targetMat = chain.get(targetStage);
        Location loc = floor.getLocation();

        // Folia: schedule the mutation on the region that owns this block's location.
        plugin.getServer().getRegionScheduler().execute(plugin, loc, () -> {
            Block b = loc.getBlock();
            // Re-check: only transform if the block is still the expected current-stage material
            // (it could have been changed by the world in the meantime).
            if (b.getType() != chain.get(bp.stage)) {
                worldMap.remove(key);
                return;
            }
            b.setType(targetMat, false);
            bp.stage = targetStage;
            bp.threshold = plugin.rollThreshold(bp.root);
        });
    }

    private static boolean isRiding(PlayerMoveEvent event) {
        return event.getPlayer().isInsideVehicle();
    }

    private static int indexOf(List<Material> chain, Material mat) {
        int i = chain.indexOf(mat);
        return i < 0 ? 0 : i;
    }

    private static long now() {
        return System.currentTimeMillis();
    }
}
