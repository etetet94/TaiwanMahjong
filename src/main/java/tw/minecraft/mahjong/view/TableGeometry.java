package tw.minecraft.mahjong.view;

/** Pure geometry, independent of Bukkit so the physical footprint can be tested. */
public final class TableGeometry {
    public static final double TABLE_WIDTH = 3.0;
    public static final double HAND_RADIUS = 1.25;
    public static final double HAND_PITCH = 0.14;
    public static final double TILE_WIDTH = 0.123;
    public static final double TILE_DEPTH = 0.09;
    public static final double TILE_HEIGHT = 0.25;

    private TableGeometry() {}

    public record Point(double x, double z) {}

    /** Seat zero is south, followed by west, north and east. */
    public static Point rotate(int seat, double x, double z) {
        return switch (Math.floorMod(seat, 4)) {
            case 0 -> new Point(x, z);
            case 1 -> new Point(-z, x);
            case 2 -> new Point(-x, -z);
            default -> new Point(z, -x);
        };
    }

    public static Point hand(int seat, int tile, int count) {
        if (count < 0 || count > 17 || tile < 0 || tile >= count) {
            throw new IllegalArgumentException("Invalid hand slot");
        }
        return rotate(seat, (tile - (count - 1) / 2.0) * HAND_PITCH, HAND_RADIUS);
    }

    public static Point discard(int seat, int index) {
        if (index < 0 || index >= 30) throw new IllegalArgumentException("Invalid discard slot");
        // One quadrant for each seat, so the four discard rivers cannot overlap.
        return rotate(seat, -0.64 + (index % 5) * 0.105, 0.12 + (index / 5) * 0.115);
    }

    public static double meldPitch(int count) {
        if (count < 1 || count > 20) throw new IllegalArgumentException("Invalid meld tile count");
        return Math.min(0.125, 1.7 / count);
    }

    public static Point meld(int seat, int index, int count) {
        if (index < 0 || index >= count) throw new IllegalArgumentException("Invalid meld tile index");
        return rotate(seat, (index - (count - 1) / 2.0) * meldPitch(count), 1.01);
    }

    public static float yaw(int seat) { return Math.floorMod(seat, 4) * 90.0F; }
}
