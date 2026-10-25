package tw.minecraft.mahjong.engine;

import java.util.ArrayList;
import java.util.List;

/** Modest, deterministic practice opponent. Reads only its own hand, melds and legal actions. */
public final class PracticeBot {
    private PracticeBot() {}

    /** Performs at most one legal action; paid-player timeout behavior remains MahjongGame.autoAction. */
    public static void act(MahjongGame game, int seat) {
        if (game.isFinished()) return;
        var actions = game.validActions(seat);
        if (game.phase() == MahjongGame.Phase.DRAW) {
            if (actions.contains(MahjongGame.Action.DRAW)) game.draw(seat);
            return;
        }
        if (game.phase() == MahjongGame.Phase.CLAIM || game.phase() == MahjongGame.Phase.KAN_CLAIM) {
            if (actions.contains(MahjongGame.Action.RON)) game.claim(seat, MahjongGame.Action.RON, -1);
            else if (actions.contains(MahjongGame.Action.KAN)) game.claim(seat, MahjongGame.Action.KAN, -1);
            else if (actions.contains(MahjongGame.Action.PON)) game.claim(seat, MahjongGame.Action.PON, -1);
            else if (actions.contains(MahjongGame.Action.CHI)) {
                int best = -1, bestScore = Integer.MIN_VALUE;
                for (int start : game.chiOptions(seat)) {
                    List<Integer> after = new ArrayList<>(game.hands(seat));
                    for (int tile = start; tile < start + 3; tile++) if (tile != game.lastTile()) after.remove(Integer.valueOf(tile));
                    List<Meld> melds = new ArrayList<>(game.melds(seat));
                    melds.add(new Meld(Meld.Kind.SEQUENCE, start, true, game.lastDiscarder()));
                    int discard = chooseDiscard(after, melds, -1, game.rules().ruleSet(), t -> true);
                    after.remove(Integer.valueOf(discard));
                    int quality = quality(after, melds, game.rules().ruleSet());
                    if (quality > bestScore) { bestScore = quality; best = start; }
                }
                game.claim(seat, MahjongGame.Action.CHI, best);
            } else if (actions.contains(MahjongGame.Action.PASS)) game.claim(seat, MahjongGame.Action.PASS, -1);
            return;
        }
        if (game.currentTurn() != seat) return;
        if (actions.contains(MahjongGame.Action.TSUMO)) { game.tsumo(seat); return; }
        List<Integer> concealed = game.concealedKanOptions(seat);
        if (!concealed.isEmpty()) { game.concealedKan(seat, concealed.get(0)); return; }
        List<Integer> added = game.addedKanOptions(seat);
        if (!added.isEmpty()) { game.addedKan(seat, added.get(0)); return; }
        if (game.declaredListening(seat)) { game.discard(seat, game.lastDraw()); return; }
        game.discard(seat, chooseDiscard(game.hands(seat), game.melds(seat), game.lastDraw(), game.rules().ruleSet(), t -> game.canDiscard(seat, t)));
    }

    static int chooseDiscard(List<Integer> hand, List<Meld> melds, int drawn) {
        return chooseDiscard(hand, melds, drawn, RuleSet.TAIWAN, t -> true);
    }
    private static int chooseDiscard(List<Integer> hand, List<Meld> melds, int drawn, RuleSet ruleSet, java.util.function.IntPredicate legal) {
        int bestTile = -1, bestQuality = Integer.MIN_VALUE;
        boolean[] seen = new boolean[34];
        for (int tile : hand) {
            if (seen[tile] || !legal.test(tile)) continue; seen[tile] = true;
            List<Integer> after = new ArrayList<>(hand); after.remove(Integer.valueOf(tile));
            int score = quality(after, melds, ruleSet);
            if (score > bestQuality || score == bestQuality &&
                    (tile == drawn || bestTile != drawn && tile > bestTile)) {
                bestQuality = score; bestTile = tile;
            }
        }
        if (bestTile < 0) throw new IllegalArgumentException("沒有可丟出的手牌");
        return bestTile;
    }

    private static int quality(List<Integer> hand, List<Meld> melds, RuleSet ruleSet) {
        int[] counts = new int[34]; for (int tile : hand) counts[tile]++;
        List<Integer> waits = HandAnalyzer.waits(hand, melds, ruleSet);
        if (!waits.isEmpty()) {
            // Count only copies in this bot's own hand/melds, never hidden wall or opponents' hands.
            int outs = 0;
            for (int tile : waits) {
                int known = counts[tile];
                for (Meld meld : melds) for (int own : meld.tiles()) if (own == tile) known++;
                outs += Math.max(0, 4 - known);
            }
            return 100_000 + outs * 100 + waits.size();
        }
        int score = 0;
        for (int tile = 0; tile < 34; tile++) {
            if (counts[tile] >= 3) score += 150;
            else if (counts[tile] == 2) score += 35;
            else if (counts[tile] == 1 && tile >= 27) score -= 10;
            else if (counts[tile] == 1 && (tile % 9 == 0 || tile % 9 == 8)) score -= 2;
            if (tile >= 27) continue;
            if (tile % 9 <= 7) score += 12 * Math.min(counts[tile], counts[tile + 1]);
            if (tile % 9 <= 6) {
                score += 4 * Math.min(counts[tile], counts[tile + 2]);
                score += 100 * Math.min(counts[tile], Math.min(counts[tile + 1], counts[tile + 2]));
            }
        }
        return score;
    }
}
