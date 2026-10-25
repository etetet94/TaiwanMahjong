package tw.minecraft.mahjong.engine;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * Four-meld Japanese scoring, including seven pairs and thirteen orphans.
 * Yaku/fu use the configured Japanese table conventions: renhou and special-wait double yakuman.
 * Alternatives maximize standard Japanese base points, then han and fu.
 * Yakuman replace ordinary yaku/dora; each yakuman is represented by 13 han in the display breakdown.
 */
public final class RiichiScorer {
    private RiichiScorer() {}

    public record Context(int winningTile, boolean selfDraw, int seatWind, int prevailingWind,
                          boolean riichi, boolean doubleRiichi, boolean ippatsu,
                          boolean heavenly, boolean earthly, boolean lastDraw, boolean lastDiscard,
                          boolean replacementDraw, boolean robKong,
                          List<Integer> doraIndicators, List<Integer> uraDoraIndicators, boolean renhou) implements Serializable {
        public Context {
            if (winningTile < 0 || winningTile >= 34 || seatWind < 0 || seatWind > 3
                    || prevailingWind < 0 || prevailingWind > 3) throw new IllegalArgumentException("無效的日麻和牌條件");
            doraIndicators = List.copyOf(doraIndicators);
            uraDoraIndicators = List.copyOf(uraDoraIndicators);
            for (List<Integer> indicators : List.of(doraIndicators, uraDoraIndicators)) {
                if (indicators.size() > 5 || indicators.stream().anyMatch(t -> t < 0 || t >= 34))
                    throw new IllegalArgumentException("無效的寶牌指示牌");
            }
        }
        public Context(int winningTile, boolean selfDraw, int seatWind, int prevailingWind,
                       boolean riichi, boolean doubleRiichi, boolean ippatsu, boolean heavenly, boolean earthly,
                       boolean lastDraw, boolean lastDiscard, boolean replacementDraw, boolean robKong,
                       List<Integer> doraIndicators, List<Integer> uraDoraIndicators) {
            this(winningTile, selfDraw, seatWind, prevailingWind, riichi, doubleRiichi, ippatsu, heavenly, earthly,
                    lastDraw, lastDiscard, replacementDraw, robKong, doraIndicators, uraDoraIndicators, false);
        }
    }

    /** Null means an invalid structure or a structurally complete hand without any yaku. */
    public static Score score(List<Integer> completeHand, List<Meld> declared, Context c) {
        if (declared.size() > 4 || completeHand.size() != (4 - declared.size()) * 3 + 2
                || !completeHand.contains(c.winningTile())) return null;
        List<Integer> physical = new ArrayList<>(completeHand);
        for (Meld m : declared) physical.addAll(m.tiles());
        int[] counts = new int[34];
        for (int t : physical) if (t < 0 || t >= 34 || ++counts[t] > 4) return null;
        boolean closed = declared.stream().noneMatch(Meld::open);
        Score best = null;
        if (declared.isEmpty() && HandAnalyzer.isThirteenOrphans(completeHand)) {
            List<Score.Tai> y = yakumanByTiles(physical, declared, c);
            boolean thirteenWait = thirteenOrphansWait(completeHand, c.winningTile());
            add(y, thirteenWait ? "kokushi_13" : "kokushi", thirteenWait ? "國士無雙十三面待" : "國士無雙", thirteenWait ? 26 : 13);
            best = yakuman(y);
        }
        if (declared.isEmpty() && HandAnalyzer.isSevenPairs(completeHand)) {
            List<Score.Tai> y = yakumanByTiles(physical, declared, c);
            if (!y.isEmpty()) best = better(best, yakuman(y));
            else {
                List<Score.Tai> parts = ordinaryCommon(physical, closed, c);
                add(parts, "chiitoitsu", "七對子", 2);
                best = better(best, finish(parts, physical, closed, c, 25));
            }
        }
        for (HandAnalyzer.Decomposition d : HandAnalyzer.winningDecompositions(completeHand, declared, RuleSet.JAPANESE)) {
            if (d.pair() == c.winningTile()) best = better(best, evaluate(d, declared, physical, c, -1));
            for (int i = 0; i < d.concealedMelds().size(); i++) {
                if (d.concealedMelds().get(i).tiles().contains(c.winningTile()))
                    best = better(best, evaluate(d, declared, physical, c, i));
            }
        }
        return best;
    }

    private static Score evaluate(HandAnalyzer.Decomposition d, List<Meld> declared, List<Integer> physical,
                                  Context c, int winningMeld) {
        boolean closed = declared.stream().noneMatch(Meld::open);
        List<Meld> all = new ArrayList<>(declared);
        all.addAll(d.concealedMelds());
        int[] sets = new int[34], sequences = new int[27];
        int sequenceCount = 0, concealedSets = 0, quads = 0;
        for (Meld m : all) {
            if (m.kind() == Meld.Kind.SEQUENCE) { sequences[m.tile()]++; sequenceCount++; }
            else {
                sets[m.tile()]++;
                if (!m.open()) concealedSets++;
                if (m.kind() == Meld.Kind.QUAD) quads++;
            }
        }
        if (!c.selfDraw() && winningMeld >= 0 && d.concealedMelds().get(winningMeld).kind() == Meld.Kind.TRIPLET)
            concealedSets--;
        int dragons = sets[31] + sets[32] + sets[33];
        int winds = sets[27] + sets[28] + sets[29] + sets[30];
        List<Score.Tai> yakuman = yakumanByTiles(physical, declared, c);
        if (dragons == 3) add(yakuman, "daisangen", "大三元", 13);
        if (winds == 4) add(yakuman, "daisuushii", "大四喜", 13);
        else if (winds == 3 && d.pair() >= 27 && d.pair() <= 30) add(yakuman, "shousuushii", "小四喜", 13);
        if (concealedSets == 4 && closed) add(yakuman, winningMeld < 0 ? "suuankou_tanki" : "suuankou",
                winningMeld < 0 ? "四暗刻單騎" : "四暗刻", winningMeld < 0 ? 26 : 13);
        if (quads == 4) add(yakuman, "suukantsu", "四槓子", 13);
        if (!yakuman.isEmpty()) return yakuman(yakuman);

        List<Score.Tai> parts = ordinaryCommon(physical, closed, c);
        boolean pinfu = closed && sequenceCount == 4 && !valuePair(d.pair(), c) && twoSided(d, c, winningMeld);
        if (pinfu) add(parts, "pinfu", "平和", 1);
        int twinSequences = 0;
        for (int number : sequences) twinSequences += number / 2;
        if (closed && twinSequences >= 2) add(parts, "ryanpeikou", "二盃口", 3);
        else if (closed && twinSequences == 1) add(parts, "iipeikou", "一盃口", 1);
        for (int t = 31; t < 34; t++) if (sets[t] > 0) add(parts, "yakuhai_dragon_" + t, "役牌（" + Tiles.name(t) + "）", 1);
        if (sets[27 + c.seatWind()] > 0) add(parts, "yakuhai_seat", "自風（" + Tiles.name(27 + c.seatWind()) + "）", 1);
        if (sets[27 + c.prevailingWind()] > 0) add(parts, "yakuhai_round", "場風（" + Tiles.name(27 + c.prevailingWind()) + "）", 1);
        if (sequenceCount == 0) add(parts, "toitoi", "對對和", 2);
        if (concealedSets >= 3) add(parts, "sanankou", "三暗刻", 2);
        if (quads == 3) add(parts, "sankantsu", "三槓子", 2);
        if (dragons == 2 && d.pair() >= 31) add(parts, "shousangen", "小三元", 2);
        for (int n = 0; n <= 6; n++) if (sequences[n] > 0 && sequences[n + 9] > 0 && sequences[n + 18] > 0) {
            add(parts, "sanshoku_doujun", "三色同順", closed ? 2 : 1); break;
        }
        for (int n = 0; n < 9; n++) if (sets[n] > 0 && sets[n + 9] > 0 && sets[n + 18] > 0) {
            add(parts, "sanshoku_doukou", "三色同刻", 2); break;
        }
        for (int suit = 0; suit < 27; suit += 9) if (sequences[suit] > 0 && sequences[suit + 3] > 0 && sequences[suit + 6] > 0) {
            add(parts, "ittsu", "一氣通貫", closed ? 2 : 1); break;
        }
        boolean outside = terminalOrHonor(d.pair());
        for (Meld m : all) outside &= m.kind() == Meld.Kind.SEQUENCE
                ? m.tile() % 9 == 0 || m.tile() % 9 == 6 : terminalOrHonor(m.tile());
        if (outside && sequenceCount > 0) {
            boolean honors = physical.stream().anyMatch(t -> t >= 27);
            add(parts, honors ? "chanta" : "junchan", honors ? "混全帶么九" : "純全帶么九",
                    honors ? (closed ? 2 : 1) : (closed ? 3 : 2));
        }
        return finish(parts, physical, closed, c, fu(d, declared, c, winningMeld, pinfu));
    }

    private static List<Score.Tai> yakumanByTiles(List<Integer> tiles, List<Meld> declared, Context c) {
        List<Score.Tai> parts = new ArrayList<>();
        if (c.selfDraw() && declared.isEmpty()) {
            if (c.heavenly() && c.seatWind() == 0) add(parts, "tenhou", "天和", 13);
            else if (c.earthly() && c.seatWind() != 0) add(parts, "chiihou", "地和", 13);
        }
        if (c.renhou() && !c.selfDraw() && c.seatWind() != 0 && declared.isEmpty()) add(parts, "renhou", "人和", 13);
        if (tiles.stream().allMatch(t -> t >= 27)) add(parts, "tsuuiisou", "字一色", 13);
        if (tiles.stream().allMatch(RiichiScorer::terminal)) add(parts, "chinroutou", "清老頭", 13);
        if (tiles.stream().allMatch(t -> t == 19 || t == 20 || t == 21 || t == 23 || t == 25 || t == 32))
            add(parts, "ryuuiisou", "綠一色", 13);
        if (declared.isEmpty() && nineGates(tiles)) {
            boolean pure = pureNineGatesWait(tiles, c.winningTile());
            add(parts, pure ? "junsei_chuuren" : "chuuren", pure ? "純正九蓮寶燈" : "九蓮寶燈", pure ? 26 : 13);
        }
        return parts;
    }

    private static List<Score.Tai> ordinaryCommon(List<Integer> tiles, boolean closed, Context c) {
        List<Score.Tai> parts = new ArrayList<>();
        boolean riichi = closed && (c.riichi() || c.doubleRiichi());
        if (riichi) {
            add(parts, c.doubleRiichi() ? "double_riichi" : "riichi", c.doubleRiichi() ? "兩立直" : "立直", c.doubleRiichi() ? 2 : 1);
            if (c.ippatsu()) add(parts, "ippatsu", "一發", 1);
        }
        if (closed && c.selfDraw()) add(parts, "menzen_tsumo", "門前清自摸和", 1);
        if (c.selfDraw() && c.replacementDraw()) add(parts, "rinshan", "嶺上開花", 1);
        else if (c.selfDraw() && c.lastDraw()) add(parts, "haitei", "海底摸月", 1);
        if (!c.selfDraw() && c.robKong()) add(parts, "chankan", "搶槓", 1);
        else if (!c.selfDraw() && c.lastDiscard()) add(parts, "houtei", "河底撈魚", 1);
        if (tiles.stream().noneMatch(RiichiScorer::terminalOrHonor)) add(parts, "tanyao", "斷么九", 1);
        if (tiles.stream().allMatch(RiichiScorer::terminalOrHonor)) add(parts, "honroutou", "混老頭", 2);
        boolean[] suits = new boolean[3]; boolean honors = false;
        for (int t : tiles) { if (t >= 27) honors = true; else suits[t / 9] = true; }
        int suitCount = 0; for (boolean suit : suits) if (suit) suitCount++;
        if (suitCount == 1) add(parts, honors ? "honitsu" : "chinitsu", honors ? "混一色" : "清一色",
                honors ? (closed ? 3 : 2) : (closed ? 6 : 5));
        return parts;
    }

    private static int fu(HandAnalyzer.Decomposition d, List<Meld> declared, Context c, int winningMeld, boolean pinfu) {
        if (pinfu) return c.selfDraw() ? 20 : 30;
        boolean closed = declared.stream().noneMatch(Meld::open);
        int fu = 20 + (!c.selfDraw() && closed ? 10 : 0) + (c.selfDraw() ? 2 : 0);
        if (valuePair(d.pair(), c)) fu += 2;
        if (d.pair() == 27 + c.seatWind() && d.pair() == 27 + c.prevailingWind()) fu += 2;
        if (winningMeld < 0) fu += 2;
        else if (d.concealedMelds().get(winningMeld).kind() == Meld.Kind.SEQUENCE && !twoSided(d, c, winningMeld)) fu += 2;
        for (Meld m : declared) fu += meldFu(m, m.open());
        for (int i = 0; i < d.concealedMelds().size(); i++)
            fu += meldFu(d.concealedMelds().get(i), !c.selfDraw() && i == winningMeld);
        if (fu == 20) fu = 30; // Open all-sequence ron still has a 30-fu floor.
        return ((fu + 9) / 10) * 10;
    }

    private static int meldFu(Meld m, boolean open) {
        if (m.kind() == Meld.Kind.SEQUENCE) return 0;
        int value = m.kind() == Meld.Kind.QUAD ? 8 : 2;
        return value * (open ? 1 : 2) * (terminalOrHonor(m.tile()) ? 2 : 1);
    }
    private static boolean valuePair(int tile, Context c) {
        return tile >= 31 || tile == 27 + c.seatWind() || tile == 27 + c.prevailingWind();
    }
    private static boolean twoSided(HandAnalyzer.Decomposition d, Context c, int winningMeld) {
        if (winningMeld < 0) return false;
        Meld m = d.concealedMelds().get(winningMeld);
        if (m.kind() != Meld.Kind.SEQUENCE) return false;
        int offset = c.winningTile() - m.tile();
        return offset == 0 && m.tile() % 9 != 6 || offset == 2 && m.tile() % 9 != 0;
    }
    private static boolean nineGates(List<Integer> tiles) {
        if (tiles.size() != 14 || tiles.get(0) >= 27) return false;
        int suit = tiles.get(0) / 9; int[] counts = new int[9];
        for (int t : tiles) { if (t >= 27 || t / 9 != suit) return false; counts[t % 9]++; }
        if (counts[0] < 3 || counts[8] < 3) return false;
        for (int n = 1; n < 8; n++) if (counts[n] < 1) return false;
        return true;
    }
    private static boolean thirteenOrphansWait(List<Integer> tiles, int winningTile) {
        List<Integer> before = new ArrayList<>(tiles); before.remove(Integer.valueOf(winningTile));
        return before.stream().distinct().count() == 13;
    }
    private static boolean pureNineGatesWait(List<Integer> tiles, int winningTile) {
        int[] counts = new int[9]; for (int tile : tiles) counts[tile % 9]++;
        counts[winningTile % 9]--;
        for (int n = 0; n < 9; n++) if (counts[n] != (n == 0 || n == 8 ? 3 : 1)) return false;
        return true;
    }
    /** Indicators follow numeric, wind, or white-green-red cycles; saved dragon IDs are red-green-white. */
    public static int doraTile(int indicator) {
        if (indicator < 0 || indicator >= 34) throw new IllegalArgumentException("無效的寶牌指示牌");
        if (indicator < 27) return indicator / 9 * 9 + (indicator % 9 + 1) % 9;
        if (indicator < 31) return 27 + (indicator - 27 + 1) % 4;
        return switch (indicator) { case 33 -> 32; case 32 -> 31; default -> 33; };
    }
    private static int countDora(List<Integer> tiles, List<Integer> indicators) {
        int total = 0;
        for (int indicator : indicators) { int dora = doraTile(indicator); for (int tile : tiles) if (tile == dora) total++; }
        return total;
    }
    private static Score finish(List<Score.Tai> parts, List<Integer> tiles, boolean closed, Context c, int fu) {
        if (parts.isEmpty()) return null; // Dora never supplies the required yaku.
        int dora = countDora(tiles, c.doraIndicators());
        if (dora > 0) add(parts, "dora", "寶牌", dora);
        if (closed && (c.riichi() || c.doubleRiichi())) {
            int ura = countDora(tiles, c.uraDoraIndicators());
            if (ura > 0) add(parts, "ura_dora", "裏寶牌", ura);
        }
        return new Score(parts.stream().mapToInt(Score.Tai::tai).sum(), parts, RuleSet.JAPANESE.unit(), fu, 0);
    }
    private static Score yakuman(List<Score.Tai> parts) {
        int han = parts.stream().mapToInt(Score.Tai::tai).sum();
        return new Score(han, parts, RuleSet.JAPANESE.unit(), 0, han / 13);
    }
    private static Score better(Score previous, Score candidate) {
        if (candidate == null) return previous;
        // Once an actual yakuman is available, an alternate ordinary decomposition cannot add dora to it.
        if (previous != null && (previous.yakumanCount() > 0 || candidate.yakumanCount() > 0))
            return candidate.yakumanCount() > previous.yakumanCount() ? candidate : previous;
        if (previous == null || JapaneseMatch.basePoints(candidate) > JapaneseMatch.basePoints(previous)
                || JapaneseMatch.basePoints(candidate) == JapaneseMatch.basePoints(previous) && (candidate.tai() > previous.tai()
                || candidate.tai() == previous.tai() && candidate.fu() > previous.fu())) return candidate;
        return previous;
    }
    private static boolean terminal(int tile) { return tile < 27 && (tile % 9 == 0 || tile % 9 == 8); }
    private static boolean terminalOrHonor(int tile) { return tile >= 27 || terminal(tile); }
    private static void add(List<Score.Tai> parts, String key, String name, int han) { parts.add(new Score.Tai(key, name, han)); }
}
