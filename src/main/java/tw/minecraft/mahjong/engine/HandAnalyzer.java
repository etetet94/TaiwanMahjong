package tw.minecraft.mahjong.engine;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/** Exhaustively decomposes five melds plus a pair; quads are represented as declared melds. */
public final class HandAnalyzer {
    private HandAnalyzer() {}
    public record Decomposition(int pair, List<Meld> concealedMelds) implements Serializable {
        public Decomposition { concealedMelds = List.copyOf(concealedMelds); }
    }
    public static List<Decomposition> winningDecompositions(List<Integer> hand, List<Meld> declared) {
        if (declared.size() > 5 || hand.size() != (5 - declared.size()) * 3 + 2) return List.of();
        int[] counts = new int[34], physical = new int[34];
        for (int tile : hand) {
            if (tile < 0 || tile >= 34 || ++physical[tile] > 4) return List.of();
            counts[tile]++;
        }
        for (Meld meld : declared) for (int tile : meld.tiles()) if (++physical[tile] > 4) return List.of();
        List<Decomposition> results = new ArrayList<>();
        for (int pair = 0; pair < 34; pair++) if (counts[pair] >= 2) {
            counts[pair] -= 2;
            decompose(counts, 5 - declared.size(), pair, new ArrayList<>(), results);
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
        return !winningDecompositions(hand, declared).isEmpty();
    }
    public static List<Integer> waits(List<Integer> hand, List<Meld> declared) {
        List<Integer> work = new ArrayList<>(hand), result = new ArrayList<>();
        for (int tile = 0; tile < 34; tile++) {
            work.add(tile);
            if (canWin(work, declared)) result.add(tile);
            work.remove(work.size() - 1);
        }
        return List.copyOf(result);
    }
}
