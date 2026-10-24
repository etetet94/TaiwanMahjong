package tw.minecraft.mahjong.engine;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;

/** Explicit Taiwan 16-tile house rules, not a mixture of Japanese 14-tile special hands. */
public record MahjongRules(int baseDiamonds, int diamondsPerTai, int reserveTiles,
                           Map<String, Integer> taiValues) implements Serializable {
    public MahjongRules {
        if (baseDiamonds < 0 || diamondsPerTai < 0 || reserveTiles < 0 || reserveTiles > 24)
            throw new IllegalArgumentException("無效的底、台或保留牌設定");
        taiValues = Map.copyOf(taiValues);
        for (int amount : taiValues.values()) if (amount < 0 || amount > 1000) throw new IllegalArgumentException("台數必須為 0～1000");
    }
    public static MahjongRules defaults() {
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("self_draw", 1); m.put("closed", 1); m.put("closed_self_draw", 3);
        m.put("all_sequences", 2); m.put("all_triplets", 4);
        m.put("full_flush", 8); m.put("half_flush", 4); m.put("all_honors", 16);
        m.put("big_dragons", 8); m.put("small_dragons", 4);
        m.put("big_winds", 16); m.put("small_winds", 8);
        m.put("three_concealed", 2); m.put("four_concealed", 5); m.put("five_concealed", 8);
        m.put("dragon", 1); m.put("seat_wind", 1); m.put("round_wind", 1);
        m.put("seat_flower", 1); m.put("flower_set", 2); m.put("eight_flowers", 8); m.put("seven_flowers", 8);
        m.put("heavenly", 16); m.put("earthly", 16); m.put("human", 8);
        m.put("last_draw", 1); m.put("last_discard", 1); m.put("replacement_draw", 1); m.put("rob_kong", 1);
        m.put("single_wait", 1); m.put("all_exposed", 2);
        m.put("four_kongs", 8);
        return new MahjongRules(5, 2, 16, m);
    }
    public int tai(String key) { return taiValues.getOrDefault(key, 0); }
    /** Conservative bound grouped by exclusive scoring families; no cap is applied to actual winnings. */
    public int maximumTai(int dealerStreak) {
        if (dealerStreak < 0 || dealerStreak > 1_000_000) throw new IllegalArgumentException("無效的連莊次數");
        int concealment = Math.max(tai("closed_self_draw"), tai("closed") + tai("self_draw")) + tai("single_wait");
        concealment = Math.max(concealment, tai("all_exposed"));
        int form = Math.max(tai("all_triplets"), tai("all_sequences")) +
                Math.max(tai("five_concealed"), Math.max(tai("four_concealed"), tai("three_concealed")));
        int color = Math.max(tai("all_honors"), Math.max(tai("full_flush"), tai("half_flush")));
        int opening = Math.max(tai("heavenly"), Math.max(tai("earthly"), tai("human"))) + tai("replacement_draw");
        int later = tai("four_kongs") + Math.max(tai("last_draw") + tai("replacement_draw"), Math.max(tai("last_discard"), tai("rob_kong")));
        // An ordinary hand can have at most seven flowers: eight immediately wins separately.
        int flower = 2 * tai("seat_flower") + tai("flower_set");
        int ordinary = concealment + form + color + maximumHonorTai() + flower + Math.max(opening, later);
        int special = Math.max(tai("eight_flowers"), tai("seven_flowers"));
        return Math.max(ordinary, special) + 1 + 2 * dealerStreak;
    }
    private int maximumHonorTai() {
        int best = 0;
        // Honor triplets are distinct (five physical copies cannot occur) and consume at most five melds.
        for (int mask = 0; mask < 128; mask++) if (Integer.bitCount(mask) <= 5)
            for (int pair = -1; pair < 7; pair++) if (pair < 0 || (mask & 1 << pair) == 0) {
                int dragons = Integer.bitCount(mask >> 4), winds = Integer.bitCount(mask & 15), value;
                if (dragons == 3) value = tai("big_dragons");
                else if (dragons == 2 && pair >= 4) value = tai("small_dragons");
                else value = dragons * tai("dragon");
                if (winds == 4) value += tai("big_winds");
                else if (winds == 3 && pair >= 0 && pair < 4) value += tai("small_winds");
                else if (winds > 0) value += tai("seat_wind") + tai("round_wind");
                best = Math.max(best, value);
            }
        return best;
    }
    public long maximumLossDiamonds(int dealerStreak) {
        return 3L * (baseDiamonds + (long) diamondsPerTai * maximumTai(dealerStreak));
    }
}
