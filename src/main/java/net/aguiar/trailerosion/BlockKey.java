package net.aguiar.trailerosion;

/**
 * Packs/unpacks block coordinates into a single long.
 *
 * <p>Layout: 26 bits x (signed), 26 bits z (signed), 12 bits y (signed). This covers
 * x/z in [-33M, 33M] (well past any world border) and y in [-2048, 2047] (covers all
 * vanilla world heights). We roll our own instead of {@code Block.getBlockKey()} so we
 * fully control the inverse (unpacking), which the Bukkit API does not expose portably.
 */
final class BlockKey {

    private BlockKey() {}

    static long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF))
                | ((long) (z & 0x3FFFFFF) << 26)
                | ((long) (y & 0xFFF) << 52);
    }

    static int x(long key) {
        return (int) (key << 38 >> 38); // sign-extend the low 26 bits
    }

    static int z(long key) {
        return (int) (key << 12 >> 38); // sign-extend bits [26, 51]
    }

    static int y(long key) {
        return (int) (key >> 52); // arithmetic shift sign-extends the top 12 bits
    }
}
