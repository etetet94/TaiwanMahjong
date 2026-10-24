package tw.minecraft.mahjong.engine;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/** Scores every valid decomposition and every placement of the winning tile; returns the maximum. */
public final class HandScorer {
    private HandScorer() {}
    public record Context(int winningTile, boolean selfDraw, int seatWind, int roundWind,
                          boolean heavenly, boolean earthly, boolean human,
                          boolean lastDraw, boolean lastDiscard, boolean replacement, boolean robKong) implements Serializable {}
    public static Score score(List<Integer> completeHand, List<Meld> declared, List<Integer> flowers,
                              Context ctx, MahjongRules rules) {
        List<HandAnalyzer.Decomposition> decompositions = HandAnalyzer.winningDecompositions(completeHand, declared);
        if (decompositions.isEmpty() || !completeHand.contains(ctx.winningTile())) return null;
        List<Integer> beforeWin = new ArrayList<>(completeHand); beforeWin.remove(Integer.valueOf(ctx.winningTile()));
        boolean uniqueWait = HandAnalyzer.waits(beforeWin, declared).size() == 1;
        Score best = null;
        for (HandAnalyzer.Decomposition d : decompositions) {
            if (d.pair() == ctx.winningTile()) {
                Score s = evaluate(d, declared, flowers, ctx, rules, uniqueWait, -1);
                if (best == null || s.tai() > best.tai()) best = s;
            }
            for (int i = 0; i < d.concealedMelds().size(); i++) if (d.concealedMelds().get(i).tiles().contains(ctx.winningTile())) {
                Score s = evaluate(d, declared, flowers, ctx, rules, uniqueWait, i);
                if (best == null || s.tai() > best.tai()) best = s;
            }
        }
        return best;
    }
    private static Score evaluate(HandAnalyzer.Decomposition d, List<Meld> declared, List<Integer> flowers,
                                  Context c, MahjongRules rules, boolean uniqueWait, int winningMeld) {
        List<Score.Tai> parts = new ArrayList<>();
        List<Meld> all = new ArrayList<>(declared); all.addAll(d.concealedMelds());
        boolean closed = declared.stream().noneMatch(Meld::open);
        if (!c.heavenly() && !c.earthly() && !c.human()) {
            if (closed && c.selfDraw()) add(parts, rules, "closed_self_draw", "門清自摸（一摸三）");
            else { if (closed) add(parts, rules, "closed", "門清"); if (c.selfDraw()) add(parts, rules, "self_draw", "自摸"); }
        }
        int[] sets = new int[34]; int concealedTriplets = 0, sequences = 0, quads = 0;
        boolean[] suits = new boolean[3]; boolean honors = false;
        if (d.pair() >= 27) honors = true; else suits[d.pair() / 9] = true;
        for (Meld m : all) {
            if (m.tile() >= 27) honors = true; else suits[m.tile() / 9] = true;
            if (m.kind() == Meld.Kind.SEQUENCE) sequences++;
            else { sets[m.tile()]++; if (!m.open()) concealedTriplets++; if (m.kind() == Meld.Kind.QUAD) quads++; }
        }
        if (!c.selfDraw() && winningMeld >= 0 && d.concealedMelds().get(winningMeld).kind() == Meld.Kind.TRIPLET) concealedTriplets--;
        if (sequences == 0) add(parts, rules, "all_triplets", "碰碰胡");
        if (concealedTriplets == 5) add(parts, rules, "five_concealed", "五暗刻");
        else if (concealedTriplets == 4) add(parts, rules, "four_concealed", "四暗刻");
        else if (concealedTriplets == 3) add(parts, rules, "three_concealed", "三暗刻");
        if (quads >= 4) add(parts, rules, "four_kongs", "四槓胡");
        int suitCount = 0; for (boolean present : suits) if (present) suitCount++;
        if (suitCount == 0) add(parts, rules, "all_honors", "字一色");
        else if (suitCount == 1) add(parts, rules, honors ? "half_flush" : "full_flush", honors ? "混一色" : "清一色");
        int dragons = sets[31] + sets[32] + sets[33], winds = sets[27] + sets[28] + sets[29] + sets[30];
        if (dragons == 3) add(parts, rules, "big_dragons", "大三元");
        else if (dragons == 2 && d.pair() >= 31) add(parts, rules, "small_dragons", "小三元");
        else for (int t = 31; t < 34; t++) if (sets[t] > 0) add(parts, rules, "dragon", Tiles.name(t) + "刻子");
        if (winds == 4) add(parts, rules, "big_winds", "大四喜");
        else if (winds == 3 && d.pair() >= 27 && d.pair() <= 30) add(parts, rules, "small_winds", "小四喜");
        else {
            if (sets[27 + c.seatWind()] > 0) add(parts, rules, "seat_wind", "門風（" + Tiles.name(27 + c.seatWind()) + "）");
            if (sets[27 + c.roundWind()] > 0) add(parts, rules, "round_wind", "圈風（" + Tiles.name(27 + c.roundWind()) + "）");
        }
        for (int flower : flowers) if ((flower - 34) % 4 == c.seatWind()) add(parts, rules, "seat_flower", "正花（" + Tiles.name(flower) + "）");
        if (flowers.containsAll(List.of(34, 35, 36, 37))) add(parts, rules, "flower_set", "花槓（春夏秋冬）");
        if (flowers.containsAll(List.of(38, 39, 40, 41))) add(parts, rules, "flower_set", "花槓（梅蘭竹菊）");
        boolean allExposed = declared.size() == 5 && declared.stream().allMatch(Meld::open) && !c.selfDraw();
        if (allExposed) add(parts, rules, "all_exposed", "全求人");
        else if (uniqueWait) add(parts, rules, "single_wait", "獨聽");
        boolean twoSided = false;
        if (winningMeld >= 0) {
            Meld m = d.concealedMelds().get(winningMeld);
            if (m.kind() == Meld.Kind.SEQUENCE) {
                int offset = c.winningTile() - m.tile();
                twoSided = offset == 0 && m.tile() % 9 != 6 || offset == 2 && m.tile() % 9 != 0;
            }
        }
        if (sequences == 5 && !honors && flowers.isEmpty() && !c.selfDraw() && twoSided && !uniqueWait)
            add(parts, rules, "all_sequences", "平胡");
        if (c.heavenly()) add(parts, rules, "heavenly", "天胡");
        else if (c.earthly()) add(parts, rules, "earthly", "地胡");
        else if (c.human()) add(parts, rules, "human", "人胡");
        if (c.lastDraw()) add(parts, rules, "last_draw", "海底撈月");
        if (c.lastDiscard()) add(parts, rules, "last_discard", "河底撈魚");
        if (c.replacement()) add(parts, rules, "replacement_draw", "槓上開花／補花自摸");
        if (c.robKong()) add(parts, rules, "rob_kong", "搶槓胡");
        return new Score(parts.stream().mapToInt(Score.Tai::tai).sum(), parts);
    }
    private static void add(List<Score.Tai> out, MahjongRules rules, String key, String name) {
        int value = rules.tai(key); if (value > 0) out.add(new Score.Tai(key, name, value));
    }
}
