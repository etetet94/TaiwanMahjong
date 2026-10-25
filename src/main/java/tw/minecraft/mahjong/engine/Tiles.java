package tw.minecraft.mahjong.engine;

/** Canonical tile ids: man0..8, pin9..17, sou18..26, winds27..30, dragons31..33, flowers34..41. */
public final class Tiles {
    private Tiles() {}
    public static final int TYPES = 42;
    private static final String[] HONORS = {"東", "南", "西", "北", "中", "發", "白", "春", "夏", "秋", "冬", "梅", "蘭", "竹", "菊"};
    public static boolean flower(int tile) { return tile >= 34 && tile < 42; }
    public static boolean suited(int tile) { return tile >= 0 && tile < 27; }
    public static boolean terminalOrHonor(int tile) { return tile >= 27 && tile < 34 || suited(tile) && (tile % 9 == 0 || tile % 9 == 8); }
    /** Indicator order: 1..9, east/south/west/north, white/green/red. */
    public static int doraAfter(int indicator) {
        if (indicator < 0 || indicator >= 34) throw new IllegalArgumentException("無效的寶牌指示牌");
        if (indicator < 27) return indicator / 9 * 9 + (indicator % 9 + 1) % 9;
        if (indicator < 31) return 27 + (indicator - 27 + 1) % 4;
        return indicator == 33 ? 32 : indicator == 32 ? 31 : 33;
    }
    public static String name(int tile) {
        check(tile);
        return tile < 27 ? (tile % 9 + 1) + new String[]{"萬", "筒", "索"}[tile / 9] : HONORS[tile - 27];
    }
    public static void check(int tile) { if (tile < 0 || tile >= TYPES) throw new IllegalArgumentException("無效的麻將牌：" + tile); }
}
