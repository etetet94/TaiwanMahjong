package tw.minecraft.mahjong.engine;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/** Exhaustively decomposes the variant's meld count plus a pair; declared quads count as melds. */
public final class HandAnalyzer {
    private HandAnalyzer() {}
    public record Decomposition(int pair, List<Meld> concealedMelds) implements Serializable {
        public Decomposition { concealedMelds = List.copyOf(concealedMelds); }
    }
    public static List<Decomposition> winningDecompositions(List<Integer> hand, List<Meld> declared) {
        return winningDecompositions(hand, declared, RuleSet.TAIWAN);
    }
    public static List<Decomposition> winningDecompositions(List<Integer> hand, List<Meld> declared, RuleSet ruleSet) {
        return decompositions(hand, declared, ruleSet, true);
    }
    /** Riichi-kan structure also considers hypothetical fifth-copy waits. */
    static List<Decomposition> structureDecompositions(List<Integer> hand, List<Meld> declared, RuleSet ruleSet) {
        return decompositions(hand, declared, ruleSet, false);
    }
    private static List<Decomposition> decompositions(List<Integer> hand, List<Meld> declared, RuleSet ruleSet, boolean physicalLimit) {
        int groups = ruleSet.meldCount();
        if (declared.size() > groups || hand.size() != (groups - declared.size()) * 3 + 2) return List.of();
        int[] counts = new int[34], physical = new int[34];
        for (int tile : hand) {
            if (tile < 0 || tile >= 34 || ++physical[tile] > 4 && physicalLimit) return List.of();
            counts[tile]++;
        }
        for (Meld meld : declared) for (int tile : meld.tiles()) if (++physical[tile] > 4 && physicalLimit) return List.of();
        List<Decomposition> results = new ArrayList<>();
        for (int pair = 0; pair < 34; pair++) if (counts[pair] >= 2) {
            counts[pair] -= 2;
            decompose(counts, groups - declared.size(), pair, new ArrayList<>(), results);
            counts[pair] += 2;
        }
        return List.copyOf(results);
    }
    private static void decompose(int[] c, int remaining, int pair, List<Meld> path, List<Decomposition> out) {
        int first = 0;
        while (first < 34 && c[first] == 0) first++;
        if (first == 34) { if (remaining == 0) out.add(new Decomposition(pair, path)); return; }
        if (remaining <= 0) return;
        if (c[first] >= 3) {
            c[first] -= 3; path.add(new Meld(Meld.Kind.TRIPLET, first, false, -1));
            decompose(c, remaining - 1, pair, path, out);
            path.remove(path.size() - 1); c[first] += 3;
        }
        if (first < 27 && first % 9 <= 6 && c[first + 1] > 0 && c[first + 2] > 0) {
            c[first]--; c[first + 1]--; c[first + 2]--;
            path.add(new Meld(Meld.Kind.SEQUENCE, first, false, -1));
            decompose(c, remaining - 1, pair, path, out);
            path.remove(path.size() - 1); c[first]++; c[first + 1]++; c[first + 2]++;
        }
    }
    public static boolean canWin(List<Integer> hand, List<Meld> declared) {
        return canWin(hand, declared, RuleSet.TAIWAN);
    }
    public static boolean canWin(List<Integer> hand, List<Meld> declared, RuleSet ruleSet) {
        return !winningDecompositions(hand, declared, ruleSet).isEmpty()
                || ruleSet == RuleSet.JAPANESE && declared.isEmpty() && (isSevenPairs(hand) || isThirteenOrphans(hand));
    }
    public static boolean isSevenPairs(List<Integer> hand) {
        int[] counts = specialCounts(hand);
        if (counts == null) return false;
        int pairs = 0;
        for (int count : counts) { if (count == 2) pairs++; else if (count != 0) return false; }
        return pairs == 7;
    }
    public static boolean isThirteenOrphans(List<Integer> hand) {
        int[] counts = specialCounts(hand);
        if (counts == null) return false;
        int pairs = 0;
        for (int tile = 0; tile < 34; tile++) {
            if (Tiles.terminalOrHonor(tile)) { if (counts[tile] == 2) pairs++; else if (counts[tile] != 1) return false; }
            else if (counts[tile] != 0) return false;
        }
        return pairs == 1;
    }
    private static int[] specialCounts(List<Integer> hand) {
        if (hand.size() != 14) return null;
        int[] counts = new int[34];
        for (int tile : hand) if (tile < 0 || tile >= 34 || ++counts[tile] > 4) return null;
        return counts;
    }
    public static List<Integer> waits(List<Integer> hand, List<Meld> declared) {
        return waits(hand, declared, RuleSet.TAIWAN);
    }
    public static List<Integer> waits(List<Integer> hand, List<Meld> declared, RuleSet ruleSet) {
        List<Integer> work = new ArrayList<>(hand), result = new ArrayList<>();
        for (int tile = 0; tile < 34; tile++) {
            work.add(tile);
            if (canWin(work, declared, ruleSet)) result.add(tile);
            work.remove(work.size() - 1);
        }
        return List.copyOf(result);
    }
}
