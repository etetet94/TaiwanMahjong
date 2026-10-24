package tw.minecraft.mahjong.engine;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;

/** One deterministic Taiwan 16-tile hand. Call only from a single serialized table actor. */
public final class MahjongGame implements Serializable {
    private static final long serialVersionUID = 1L;
    public enum Phase { DISCARD, CLAIM, KAN_CLAIM, FINISHED, DRAW }
    public enum Action { PASS, CHI, PON, KAN, RON, TSUMO, DRAW }
    private enum DrawKind { DEAL, NORMAL, FLOWER, KAN }
    public record Win(int seat, Score score) implements Serializable {}
    public record Payment(int fromSeat, int toSeat, long diamonds, int tai) implements Serializable {}
    public record Outcome(List<Win> winners, List<Payment> payments, int sourceSeat,
                          boolean selfDraw, boolean draw, String reason) implements Serializable {
        public Outcome { winners = List.copyOf(winners); payments = List.copyOf(payments); }
    }
    private record Response(Action action, int chiStart) implements Serializable {}

    private final MahjongRules rules;
    // Added without changing serialVersionUID: older saved hands default to automatic drawing.
    private final boolean manualDraw;
    private final int dealer, prevailingWind, dealerStreak;
    private final List<List<Integer>> hands = new ArrayList<>(), flowers = new ArrayList<>(), discards = new ArrayList<>();
    private final List<List<Meld>> melds = new ArrayList<>();
    private final List<Integer> wall = new ArrayList<>();
    private int head, tail, currentTurn, lastTile = -1, lastDiscarder = -1, lastDraw = -1;
    private int totalDiscards;
    private final int[] drawsTaken = new int[4], discardCounts = new int[4];
    private final boolean[] passedRon = new boolean[4];
    private final Response[] responses = new Response[4];
    private final List<EnumSet<Action>> claimOptions = new ArrayList<>();
    private boolean callsOccurred, lastDiscardAtWallEnd, initialDeal;
    private DrawKind drawKind = DrawKind.DEAL;
    private int pendingAddedMeld = -1;
    private Phase phase = Phase.DISCARD;
    private Outcome outcome;

    public MahjongGame(long seed, int dealer, int prevailingWind, int dealerStreak) {
        this(seed, dealer, prevailingWind, dealerStreak, MahjongRules.defaults());
    }
    public MahjongGame(long seed, int dealer, int prevailingWind, int dealerStreak, MahjongRules rules) {
        this(seed, dealer, prevailingWind, dealerStreak, rules, false);
    }
    public MahjongGame(long seed, int dealer, int prevailingWind, int dealerStreak, MahjongRules rules, boolean manualDraw) {
        seat(dealer);
        if (prevailingWind < 0 || prevailingWind > 3 || dealerStreak < 0 || dealerStreak > 1_000_000 || rules == null)
            throw new IllegalArgumentException("無效的圈風、連莊或桌規");
        this.dealer = dealer; this.prevailingWind = prevailingWind; this.dealerStreak = dealerStreak; this.rules = rules; this.manualDraw = manualDraw;
        for (int i = 0; i < 4; i++) {
            hands.add(new ArrayList<>()); flowers.add(new ArrayList<>()); discards.add(new ArrayList<>());
            melds.add(new ArrayList<>()); claimOptions.add(EnumSet.noneOf(Action.class));
        }
        for (int t = 0; t < 34; t++) for (int n = 0; n < 4; n++) wall.add(t);
        for (int t = 34; t < 42; t++) wall.add(t);
        Collections.shuffle(wall, new Random(seed)); tail = wall.size() - 1; currentTurn = dealer;
        initialDeal = true;
        for (int round = 0; round < 16; round++) for (int offset = 0; offset < 4; offset++) drawTile((dealer + offset) % 4, DrawKind.DEAL);
        drawTile(dealer, DrawKind.DEAL); drawsTaken[dealer] = 1;
        initialDeal = false;
        detectFlowerWin();
    }
    public MahjongRules rules() { return rules; }
    public boolean manualDraw() { return manualDraw; }
    public int dealer() { return dealer; }
    public int prevailingWind() { return prevailingWind; }
    public int dealerStreak() { return dealerStreak; }
    public int currentTurn() { return currentTurn; }
    public int lastTile() { return lastTile; }
    public int lastDiscarder() { return lastDiscarder; }
    public int lastDraw() { return lastDraw; }
    public int remainingWall() { return Math.max(0, tail - head + 1 - rules.reserveTiles()); }
    public Phase phase() { return phase; }
    public boolean isFinished() { return phase == Phase.FINISHED; }
    public Outcome outcome() { return outcome; }
    public List<Integer> hands(int seat) { seat(seat); return List.copyOf(hands.get(seat)); }
    public List<Integer> flowers(int seat) { seat(seat); return List.copyOf(flowers.get(seat)); }
    public List<Integer> discards(int seat) { seat(seat); return List.copyOf(discards.get(seat)); }
    public List<Meld> melds(int seat) { seat(seat); return List.copyOf(melds.get(seat)); }
    public int seatWind(int seat) { seat(seat); return (seat - dealer + 4) % 4; }
    public boolean hasResponded(int seat) { seat(seat); return responses[seat] != null; }
    public EnumSet<Action> validActions(int seat) {
        seat(seat);
        if (phase == Phase.FINISHED) return EnumSet.noneOf(Action.class);
        if (phase == Phase.DRAW) return seat == currentTurn ? EnumSet.of(Action.DRAW) : EnumSet.noneOf(Action.class);
        if (phase == Phase.CLAIM || phase == Phase.KAN_CLAIM)
            return responses[seat] == null ? claimOptions.get(seat).clone() : EnumSet.noneOf(Action.class);
        EnumSet<Action> result = EnumSet.noneOf(Action.class);
        if (seat != currentTurn) return result;
        if (lastDraw >= 0 && HandAnalyzer.canWin(hands.get(seat), melds.get(seat))) result.add(Action.TSUMO);
        if (!concealedKanOptions(seat).isEmpty() || !addedKanOptions(seat).isEmpty()) result.add(Action.KAN);
        return result;
    }
    /** Starts of all legal same-suit runs, e.g. 0 means one/two/three man. */
    public List<Integer> chiOptions(int seat) {
        seat(seat);
        if (phase != Phase.CLAIM || seat != (lastDiscarder + 1) % 4 || lastTile < 0 || lastTile >= 27 || responses[seat] != null)
            return List.of();
        List<Integer> result = new ArrayList<>();
        int min = Math.max(lastTile / 9 * 9, lastTile - 2), max = Math.min(lastTile, lastTile / 9 * 9 + 6);
        for (int start = min; start <= max; start++) {
            boolean ok = true;
            for (int tile = start; tile < start + 3; tile++) if (tile != lastTile && count(hands.get(seat), tile) == 0) ok = false;
            if (ok) result.add(start);
        }
        return List.copyOf(result);
    }
    public List<Integer> concealedKanOptions(int seat) {
        seat(seat);
        if (phase != Phase.DISCARD || seat != currentTurn || remainingWall() == 0 || lastDraw < 0) return List.of();
        List<Integer> result = new ArrayList<>();
        for (int t = 0; t < 34; t++) if (count(hands.get(seat), t) == 4) result.add(t);
        return List.copyOf(result);
    }
    public List<Integer> addedKanOptions(int seat) {
        seat(seat);
        if (phase != Phase.DISCARD || seat != currentTurn || remainingWall() == 0 || lastDraw < 0) return List.of();
        List<Integer> result = new ArrayList<>();
        for (Meld m : melds.get(seat)) if (m.kind() == Meld.Kind.TRIPLET && m.open() && hands.get(seat).contains(m.tile())) result.add(m.tile());
        return List.copyOf(result);
    }
    /** Normal turn draw; flowers and kan supplements are still replaced automatically. */
    public void draw(int seat) {
        seat(seat);
        if (phase != Phase.DRAW || seat != currentTurn) throw new IllegalArgumentException("現在不是你的摸牌回合");
        drawForTurn(seat, DrawKind.NORMAL);
    }
    public void discard(int seat, int tile) {
        requireTurn(seat); Tiles.check(tile);
        if (!hands.get(seat).contains(tile)) throw new IllegalArgumentException("你的手牌沒有這張牌");
        hands.get(seat).remove(Integer.valueOf(tile)); discards.get(seat).add(tile);
        lastTile = tile; lastDiscarder = seat; totalDiscards++; discardCounts[seat]++;
        lastDiscardAtWallEnd = remainingWall() == 0 && drawKind == DrawKind.NORMAL;
        lastDraw = -1; phase = Phase.CLAIM;
        prepareClaims(false);
    }
    public void claim(int seat, Action action, int chiStart) {
        seat(seat);
        if (action == null || phase != Phase.CLAIM && phase != Phase.KAN_CLAIM || !validActions(seat).contains(action))
            throw new IllegalArgumentException("現在不能執行這個動作");
        if (action == Action.CHI && !chiOptions(seat).contains(chiStart)) throw new IllegalArgumentException("不合法的吃牌組合");
        if (action != Action.RON && claimOptions.get(seat).contains(Action.RON)) passedRon[seat] = true;
        responses[seat] = new Response(action, chiStart);
        resolveIfReady();
    }
    public void tsumo(int seat) {
        requireTurn(seat);
        if (!validActions(seat).contains(Action.TSUMO)) throw new IllegalArgumentException("尚未構成五組面子加一對將眼，不能自摸");
        finishNormal(List.of(seat), -1, true, false);
    }
    public void concealedKan(int seat, int tile) {
        requireTurn(seat);
        if (!concealedKanOptions(seat).contains(tile)) throw new IllegalArgumentException("現在不能暗槓這張牌");
        remove(hands.get(seat), tile, 4); melds.get(seat).add(new Meld(Meld.Kind.QUAD, tile, false, seat));
        callsOccurred = true; drawForTurn(seat, DrawKind.KAN);
    }
    public void addedKan(int seat, int tile) {
        requireTurn(seat);
        if (!addedKanOptions(seat).contains(tile)) throw new IllegalArgumentException("現在不能加槓這張牌");
        pendingAddedMeld = -1;
        for (int i = 0; i < melds.get(seat).size(); i++) if (melds.get(seat).get(i).kind() == Meld.Kind.TRIPLET && melds.get(seat).get(i).tile() == tile) { pendingAddedMeld = i; break; }
        lastTile = tile; lastDiscarder = seat; phase = Phase.KAN_CLAIM;
        prepareClaims(true);
    }
    /** Timeout/trustee: draw if needed, take a win, otherwise pass or discard the drawn tile. */
    public void autoAction(int seat) {
        seat(seat);
        if (phase == Phase.FINISHED) return;
        EnumSet<Action> valid = validActions(seat);
        if (phase == Phase.DRAW) {
            if (valid.contains(Action.DRAW)) draw(seat);
        } else if (phase == Phase.CLAIM || phase == Phase.KAN_CLAIM) {
            if (valid.contains(Action.RON)) claim(seat, Action.RON, -1);
            else if (valid.contains(Action.PASS)) claim(seat, Action.PASS, -1);
        } else if (seat == currentTurn) {
            if (valid.contains(Action.TSUMO)) tsumo(seat);
            else discard(seat, lastDraw >= 0 && hands.get(seat).contains(lastDraw) ? lastDraw : hands.get(seat).get(hands.get(seat).size() - 1));
        }
    }
    private void prepareClaims(boolean robKong) {
        for (int s = 0; s < 4; s++) { responses[s] = null; claimOptions.get(s).clear(); }
        for (int s = 0; s < 4; s++) if (s != lastDiscarder) {
            EnumSet<Action> allowed = claimOptions.get(s);
            if (!passedRon[s]) {
                List<Integer> candidate = new ArrayList<>(hands.get(s)); candidate.add(lastTile);
                if (HandAnalyzer.canWin(candidate, melds.get(s))) allowed.add(Action.RON);
            }
            if (!robKong) {
                int have = count(hands.get(s), lastTile);
                if (have >= 2) allowed.add(Action.PON);
                if (have >= 3 && remainingWall() > 0) allowed.add(Action.KAN);
                if (!chiOptions(s).isEmpty()) allowed.add(Action.CHI);
            }
            if (!allowed.isEmpty()) allowed.add(Action.PASS);
        }
        resolveIfReady();
    }
    private void resolveIfReady() {
        List<Integer> ronWinners = new ArrayList<>(); boolean anyRon = false, pendingRon = false, pendingOther = false;
        for (int offset = 1; offset <= 3; offset++) {
            int s = (lastDiscarder + offset) % 4;
            if (responses[s] != null && responses[s].action() == Action.RON) { anyRon = true; ronWinners.add(s); }
            if (!claimOptions.get(s).isEmpty() && responses[s] == null) {
                if (claimOptions.get(s).contains(Action.RON)) pendingRon = true;
                pendingOther = true;
            }
        }
        if (anyRon && !pendingRon) {
            boolean rob = phase == Phase.KAN_CLAIM;
            if (rob) hands.get(lastDiscarder).remove(Integer.valueOf(lastTile));
            finishNormal(ronWinners, lastDiscarder, false, rob); return;
        }
        if (pendingOther) return;
        if (phase == Phase.KAN_CLAIM) {
            int s = lastDiscarder; Meld previous = melds.get(s).get(pendingAddedMeld);
            remove(hands.get(s), lastTile, 1);
            melds.get(s).set(pendingAddedMeld, new Meld(Meld.Kind.QUAD, lastTile, true, previous.fromSeat()));
            pendingAddedMeld = -1; callsOccurred = true; drawForTurn(s, DrawKind.KAN); return;
        }
        for (int priority = 0; priority < 2; priority++) for (int offset = 1; offset <= 3; offset++) {
            int s = (lastDiscarder + offset) % 4; Response r = responses[s];
            if (r == null) continue;
            boolean match = priority == 0 ? r.action() == Action.PON || r.action() == Action.KAN : r.action() == Action.CHI;
            if (!match) continue;
            discards.get(lastDiscarder).remove(discards.get(lastDiscarder).size() - 1);
            if (r.action() == Action.CHI) {
                for (int tile = r.chiStart(); tile < r.chiStart() + 3; tile++) if (tile != lastTile) remove(hands.get(s), tile, 1);
                melds.get(s).add(new Meld(Meld.Kind.SEQUENCE, r.chiStart(), true, lastDiscarder));
            } else {
                remove(hands.get(s), lastTile, r.action() == Action.KAN ? 3 : 2);
                melds.get(s).add(new Meld(r.action() == Action.KAN ? Meld.Kind.QUAD : Meld.Kind.TRIPLET, lastTile, true, lastDiscarder));
            }
            callsOccurred = true; currentTurn = s; phase = Phase.DISCARD; lastDraw = -1;
            if (r.action() == Action.KAN) drawForTurn(s, DrawKind.KAN);
            return;
        }
        int next = (lastDiscarder + 1) % 4;
        if (manualDraw) {
            if (remainingWall() == 0) { finishDraw(); return; }
            currentTurn = next; phase = Phase.DRAW; lastDraw = -1;
        } else drawForTurn(next, DrawKind.NORMAL);
    }
    private void drawForTurn(int s, DrawKind kind) {
        if (remainingWall() == 0) { finishDraw(); return; }
        currentTurn = s; phase = Phase.DISCARD; passedRon[s] = false; lastDraw = -1;
        if (kind == DrawKind.NORMAL) drawsTaken[s]++;
        drawTile(s, kind);
    }
    private void drawTile(int s, DrawKind kind) {
        DrawKind effective = kind;
        boolean supplement = kind == DrawKind.KAN;
        while (head <= tail) {
            int tile = supplement ? wall.get(tail--) : wall.get(head++);
            if (Tiles.flower(tile)) {
                flowers.get(s).add(tile); Collections.sort(flowers.get(s));
                if (!initialDeal && detectFlowerWin()) return;
                supplement = true;
                if (effective != DrawKind.KAN) effective = DrawKind.FLOWER;
                // Last live tile may itself be a flower; allow its replacement from the retained tail.
            } else {
                hands.get(s).add(tile); Collections.sort(hands.get(s));
                lastDraw = tile; drawKind = effective; return;
            }
        }
        finishDraw();
    }
    private boolean detectFlowerWin() {
        for (int s = 0; s < 4; s++) if (flowers.get(s).size() == 8) { finishFlower(s, -1, "eight_flowers", "八仙過海"); return true; }
        for (int s = 0; s < 4; s++) if (flowers.get(s).size() == 7)
            for (int other = 0; other < 4; other++) if (other != s && flowers.get(other).size() == 1) {
                finishFlower(s, other, "seven_flowers", "七搶一"); return true;
            }
        return false;
    }
    private void finishFlower(int winner, int source, String key, String title) {
        Score score = new Score(rules.tai(key), List.of(new Score.Tai(key, title, rules.tai(key))));
        finish(List.of(new Win(winner, score)), source, source < 0, title);
    }
    private void finishNormal(List<Integer> winnerSeats, int source, boolean selfDraw, boolean robKong) {
        List<Win> wins = new ArrayList<>();
        for (int winner : winnerSeats) {
            List<Integer> complete = new ArrayList<>(hands.get(winner));
            int tile = selfDraw ? lastDraw : lastTile;
            if (!selfDraw) complete.add(tile);
            boolean heavenly = selfDraw && winner == dealer && totalDiscards == 0 && !callsOccurred;
            boolean earthly = selfDraw && winner != dealer && drawsTaken[winner] == 1 && discardCounts[winner] == 0 && !callsOccurred;
            boolean human = !selfDraw && !robKong && winner != dealer && source == dealer && totalDiscards == 1 && !callsOccurred;
            HandScorer.Context ctx = new HandScorer.Context(tile, selfDraw, seatWind(winner), prevailingWind,
                    heavenly, earthly, human,
                    selfDraw && remainingWall() == 0 && drawKind == DrawKind.NORMAL,
                    !selfDraw && !robKong && lastDiscardAtWallEnd,
                    selfDraw && (drawKind == DrawKind.KAN || drawKind == DrawKind.FLOWER), robKong);
            Score score = HandScorer.score(complete, melds.get(winner), flowers.get(winner), ctx, rules);
            if (score == null) throw new IllegalStateException("已通過胡牌檢查的手牌無法計分");
            wins.add(new Win(winner, score));
        }
        finish(wins, source, selfDraw, robKong ? "搶槓胡" : selfDraw ? "自摸" : "榮和（放槍胡牌）");
    }
    private void finish(List<Win> wins, int source, boolean selfDraw, String reason) {
        List<Payment> payments = new ArrayList<>();
        for (Win win : wins) for (int payer = 0; payer < 4; payer++)
            if (payer != win.seat() && (selfDraw || payer == source)) {
                int tai = win.score().tai() + (payer == dealer || win.seat() == dealer ? 1 + 2 * dealerStreak : 0);
                long diamonds = rules.baseDiamonds() + (long) rules.diamondsPerTai() * tai;
                payments.add(new Payment(payer, win.seat(), diamonds, tai));
            }
        outcome = new Outcome(wins, payments, source, selfDraw, false, reason); phase = Phase.FINISHED;
    }
    private void finishDraw() {
        outcome = new Outcome(List.of(), List.of(), -1, false, true, "流局：已到保留牌，未有人胡牌"); phase = Phase.FINISHED;
    }
    private void requireTurn(int seat) {
        seat(seat);
        if (phase != Phase.DISCARD || seat != currentTurn) throw new IllegalArgumentException("現在不是你的出牌回合");
    }
    private static void seat(int seat) { if (seat < 0 || seat > 3) throw new IllegalArgumentException("座位必須為 0～3"); }
    private static int count(List<Integer> list, int tile) { return Collections.frequency(list, tile); }
    private static void remove(List<Integer> list, int tile, int amount) {
        if (count(list, tile) < amount) throw new IllegalStateException("手牌張數不足");
        for (int i = 0; i < amount; i++) list.remove(Integer.valueOf(tile));
    }
}
