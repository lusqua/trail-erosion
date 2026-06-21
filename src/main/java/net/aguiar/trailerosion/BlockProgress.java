package net.aguiar.trailerosion;

import org.bukkit.Material;

/**
 * Per-block erosion state.
 *
 * <p>All mutations happen on the region thread that owns the block (both the
 * {@code PlayerMoveEvent} handler and the scheduled transform/revert tasks run there).
 * The periodic sweep, which runs on the global region scheduler thread, only <em>reads</em>
 * these fields to decide candidates — hence the fields are {@code volatile} for visibility,
 * but no extra locking is required.
 */
final class BlockProgress {

    /** The chain root material (index 0 of the chain) — identifies which chain this block follows. */
    final Material root;

    /** Current stage index within the chain (0 = original material). */
    volatile int stage;

    /** Accumulated steps toward the next stage (double, because the mount multiplier is fractional). */
    volatile double steps;

    /** Randomized step count needed to advance to the next stage. */
    volatile int threshold;

    /** Wall-clock millis of the last registered step. Used for idle-based reversion (real minutes). */
    volatile long lastStepMillis;

    BlockProgress(Material root, int stage, int threshold, long nowMillis) {
        this.root = root;
        this.stage = stage;
        this.steps = 0;
        this.threshold = threshold;
        this.lastStepMillis = nowMillis;
    }
}
