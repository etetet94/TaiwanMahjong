package tw.minecraft.mahjong.engine;

import java.io.Serializable;
import java.util.List;

/** Intrinsic hand score. The historical tai accessor carries Japanese han; JapaneseMatch uses han and fu for points. */
public record Score(int tai, List<Tai> breakdown, String unitLabel, int fu, int yakumanCount) implements Serializable {
    // Records previously used the implicit serial UID of zero. Added components default safely when restored.
    private static final long serialVersionUID = 0L;
    public Score {
        breakdown = List.copyOf(breakdown);
        if (unitLabel == null || unitLabel.isBlank()) unitLabel = "台";
    }
    public Score(int tai, List<Tai> breakdown) { this(tai, breakdown, "台", 0, 0); }
    public record Tai(String key, String name, int tai) implements Serializable {}
}
