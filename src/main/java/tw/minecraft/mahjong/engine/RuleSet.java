package tw.minecraft.mahjong.engine;

/** Tile and win rules; match points and diamond settlement are handled separately. */
public enum RuleSet {
    TAIWAN(16, 5, "台灣十六張", "台"),
    JAPANESE(13, 4, "日本十三張立直", "飜");

    private final int handTiles, meldCount;
    private final String displayName, unit;
    RuleSet(int handTiles, int meldCount, String displayName, String unit) {
        this.handTiles = handTiles; this.meldCount = meldCount; this.displayName = displayName; this.unit = unit;
    }
    public int handTiles() { return handTiles; }
    public int meldCount() { return meldCount; }
    public String displayName() { return displayName; }
    public String unit() { return unit; }
}
