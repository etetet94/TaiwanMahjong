package tw.minecraft.mahjong.view;

import java.util.ArrayList;
import java.util.List;

/** Pure geometry, independent of Bukkit so the physical footprint can be tested. */
public final class TableGeometry {
    public static final double TABLE_WIDTH = 3.0;
    public static final double HAND_RADIUS = 1.25;
    public static final double HAND_PITCH = 0.14;
    public static final double TILE_WIDTH = 0.123;
    public static final double TILE_DEPTH = 0.09;
    public static final double TILE_HEIGHT = 0.25;
    public static final int DISCARD_COLUMNS = 6;
    public static final int MAX_VISIBLE_DISCARDS = 30;

    private TableGeometry() {}

    public record Point(double x, double z) {}
    public record MeldSlot(Point center, double width, double depth, boolean sideways) {}

    /** Seat zero is south; +1 is east, north, west: each player's right/downstream seat. */
    public static Point rotate(int seat, double x, double z) {
        return switch (Math.floorMod(seat, 4)) {
            case 0 -> new Point(x, z);
            case 1 -> new Point(z, -x);
            case 2 -> new Point(-x, -z);
            default -> new Point(-z, x);
        };
    }

    public static Point hand(int seat, int tile, int count) {
        if (count < 0 || count > 17 || tile < 0 || tile >= count) {
            throw new IllegalArgumentException("Invalid hand slot");
        }
        return rotate(seat, (tile - (count - 1) / 2.0) * HAND_PITCH, HAND_RADIUS);
    }

    public static Point discard(int seat, int index) {
        if (index < 0 || index >= MAX_VISIBLE_DISCARDS) throw new IllegalArgumentException("Invalid discard slot");
        // One quadrant for each seat, so the four discard rivers cannot overlap.
        return rotate(seat, -0.70 + (index % DISCARD_COLUMNS) * 0.105, 0.12 + (index / DISCARD_COLUMNS) * 0.115);
    }

    /** Source left/across/right is marked by the left/middle/right tile, from the owner's view. */
    public static int calledTileIndex(int seat, int fromSeat, int tileCount) {
        if (tileCount < 1 || fromSeat < 0) return -1;
        return switch (Math.floorMod(fromSeat - seat, 4)) {
            case 3 -> 0;
            case 2 -> Math.min(1, tileCount - 1);
            case 1 -> tileCount - 1;
            default -> -1;
        };
    }

    /** Allocate actual sideways footprints and gaps, rather than overlapping fixed-pitch cells. */
    public static List<MeldSlot> meldLayout(int seat, List<Integer> sizes, List<Integer> calledIndices) {
        if (sizes.size() != calledIndices.size()) throw new IllegalArgumentException("Missing meld source metadata");
        int count = sizes.stream().mapToInt(Integer::intValue).sum();
        if (count == 0) return List.of();
        if (count > 20 || sizes.stream().anyMatch(size -> size < 1 || size > 4)) throw new IllegalArgumentException("Invalid meld sizes");
        double units = 0;
        for (int group = 0; group < sizes.size(); group++) {
            int size = sizes.get(group), called = calledIndices.get(group);
            if (called < -1 || called >= size) throw new IllegalArgumentException("Invalid called tile position");
            units += size * 0.84 + (called >= 0 ? 0.32 : 0) + (size - 1) * 0.10;
        }
        units += (sizes.size() - 1) * 0.35;
        double scale = Math.min(0.125, 1.80 / units), cursor = -units * scale / 2;
        List<MeldSlot> result = new ArrayList<>(count);
        for (int group = 0; group < sizes.size(); group++) {
            int size = sizes.get(group);
            for (int tile = 0; tile < size; tile++) {
                boolean sideways = tile == calledIndices.get(group);
                double footprint = (sideways ? 1.16 : 0.84) * scale;
                result.add(new MeldSlot(rotate(seat, cursor + footprint / 2, 1.01), 0.84 * scale, 1.16 * scale, sideways));
                cursor += footprint + (tile + 1 < size ? 0.10 : 0.35) * scale;
            }
        }
        return List.copyOf(result);
    }

    public static double meldPitch(int count) {
        if (count < 1 || count > 20) throw new IllegalArgumentException("Invalid meld tile count");
        return Math.min(0.125, 1.7 / count);
    }

    public static Point meld(int seat, int index, int count) {
        if (index < 0 || index >= count) throw new IllegalArgumentException("Invalid meld tile index");
        return rotate(seat, (index - (count - 1) / 2.0) * meldPitch(count), 1.01);
    }

    public static float yaw(int seat) { return Math.floorMod(-seat, 4) * 90.0F; }
}
