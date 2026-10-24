package tw.minecraft.mahjong.engine;

import java.io.Serializable;
import java.util.List;

/** Dealer additions are per payment, not included in this intrinsic winning-hand score. */
public record Score(int tai, List<Tai> breakdown) implements Serializable {
    public Score { breakdown = List.copyOf(breakdown); }
    public record Tai(String key, String name, int tai) implements Serializable {}
}
