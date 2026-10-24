package tw.minecraft.mahjong.arena;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Coordinates are relative to the arena floor's integer anchor. No Bukkit dependency. */
public final class ArenaGeometry {
    public static final int MIN = -13, MAX = 12, TOP = 13;
    public static final int WALL_HEIGHT = 6, ROOF = 7, DECK = 9;
    public enum Kind { FLOOR, EDGE, LIGHT, ACCENT, GLASS, DECK, RAIL, SEAT_NORTH, SEAT_SOUTH, SIGN }
    public record Cell(int x, int y, int z, Kind kind) {}
    public record Point(double x, double y, double z) {}
    private ArenaGeometry() {}

    public static List<Cell> blocks() {
        Map<String, Cell> result = new LinkedHashMap<>();
        for (int x = -11; x <= 10; x++) for (int z = -11; z <= 10; z++) {
            boolean edge = x == -11 || x == 10 || z == -11 || z == 10;
            Kind floor = edge ? Kind.EDGE : Kind.FLOOR;
            if (!edge && (x == -9 || x == 8) && (z == -9 || z == 8)) floor = Kind.LIGHT;
            else if (!edge && (x == -10 || x == 9 || z == -10 || z == 9)) floor = Kind.ACCENT;
            put(result, x, 0, z, floor);
            if (edge) for (int y = 1; y <= WALL_HEIGHT; y++) put(result, x, y, z, Kind.GLASS);
            put(result, x, ROOF, z, Kind.GLASS);
        }
        for (int x = MIN; x <= MAX; x++) for (int z = MIN; z <= MAX; z++) {
            put(result, x, DECK, z, Kind.DECK);
            if (x == MIN || x == MAX || z == MIN || z == MAX) {
                put(result, x, DECK + 1, z, Kind.RAIL);
                put(result, x, DECK + 2, z, Kind.RAIL);
            }
        }
        for (int x : new int[] {-8, -7, -6, -5, 4, 5, 6, 7}) {
            put(result, x, DECK + 1, 10, Kind.SEAT_NORTH);
            put(result, x, DECK + 1, -11, Kind.SEAT_SOUTH);
        }
        put(result, -1, DECK + 1, 10, Kind.SIGN);
        return List.copyOf(result.values());
    }

    private static void put(Map<String, Cell> result, int x, int y, int z, Kind kind) {
        String key = x + ":" + y + ":" + z;
        if (result.putIfAbsent(key, new Cell(x, y, z, kind)) != null) throw new IllegalStateException("Overlapping arena blocks: " + key);
    }

    public static boolean fight(double x, double y, double z) {
        return x >= -10 && x < 10 && z >= -10 && z < 10 && y >= 1 && y < ROOF;
    }
    public static boolean spectator(double x, double y, double z) {
        return x >= -12 && x < 12 && z >= -12 && z < 12 && y >= DECK + 1 && y < TOP + 1;
    }
    public static boolean structure(double x, double y, double z) {
        return x >= MIN && x < MAX + 1 && z >= MIN && z < MAX + 1 && y >= 0 && y < TOP + 1;
    }
    public static Point fighter() { return new Point(0, 1, 0); }
    public static Point spectator() { return new Point(0, DECK + 1, 0); }
    public static List<Point> wardens() {
        List<Point> points = new ArrayList<>();
        for (double z : new double[] {-7.5, 7.5}) for (double x : new double[] {-7.5, -3.5, 0.5, 3.5, 7.5}) points.add(new Point(x, 1, z));
        return List.copyOf(points);
    }
}
