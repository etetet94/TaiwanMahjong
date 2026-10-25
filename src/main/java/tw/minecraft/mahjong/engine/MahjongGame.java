package tw.minecraft.mahjong.engine;

import java.io.Serializable;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;

/** One deterministic Taiwan 16-tile or Japanese riichi hand. Use a serialized table actor. */
public final class MahjongGame implements Serializable {
    private static final long serialVersionUID = 1L;
    public enum Phase { DISCARD, CLAIM, KAN_CLAIM, FINISHED, DRAW }
    public enum Action { PASS, CHI, PON, KAN, RON, TSUMO, DRAW, LISTEN }
    private enum DrawKind { DEAL, NORMAL, FLOWER, KAN, OPEN_KAN }
    public record Win(int seat, Score score) implements Serializable {}
    public record Payment(int fromSeat, int toSeat, long diamonds, int tai) implements Serializable {}
    public record Outcome(List<Win> winners, List<Payment> payments, int sourceSeat,
                          boolean selfDraw, boolean draw, String reason,
                          List<Integer> tenpaiSeats, List<Integer> pointDeltas) implements Serializable {
        public Outcome {
            winners = List.copyOf(winners); payments = List.copyOf(payments);
            tenpaiSeats = tenpaiSeats == null ? List.of() : List.copyOf(tenpaiSeats);
            pointDeltas = pointDeltas == null ? List.of(0, 0, 0, 0) : List.copyOf(pointDeltas);
            if (pointDeltas.size() != 4 || pointDeltas.stream().mapToInt(Integer::intValue).sum() != 0)
                throw new IllegalArgumentException("點數轉移必須四席合計為零");
        }
        public Outcome(List<Win> winners, List<Payment> payments, int sourceSeat,
                       boolean selfDraw, boolean draw, String reason) {
            this(winners, payments, sourceSeat, selfDraw, draw, reason, List.of(), List.of(0, 0, 0, 0));
        }
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
    // Non-final so old serialized games (without these fields) can migrate safely.
    private boolean[] declaredListening = new boolean[4];
    private List<List<Integer>> listeningWaits = new ArrayList<>(List.of(List.of(), List.of(), List.of(), List.of()));
    private boolean[] riichiPassedRon = new boolean[4], doubleRiichi = new boolean[4], ippatsu = new boolean[4];
    // Keep called-away discards for Japanese discard-furiten.
    private List<List<Integer>> discardHistory = new ArrayList<>();
    private List<Integer> deadWall = new ArrayList<>();
    private int kanCount;
    private int pendingRiichiSeat = -1;
    private List<Integer> forbiddenDiscards = new ArrayList<>();
    private int[] japanesePoints = {25000, 25000, 25000, 25000};
    private int[] riichiDiscardIndices = {-1, -1, -1, -1};
    private boolean[] riichiDiscardPending = new boolean[4];
    private transient int listeningCacheSeat = -1;
    private transient List<Integer> listeningCacheHand, listeningCacheOptions;
    private transient List<Meld> listeningCacheMelds;
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
            discardHistory.add(new ArrayList<>());
        }
        for (int t = 0; t < 34; t++) for (int n = 0; n < 4; n++) wall.add(t);
        if (!isJapanese()) for (int t = 34; t < 42; t++) wall.add(t);
        Collections.shuffle(wall, new Random(seed));
        if (isJapanese()) { deadWall.addAll(wall.subList(122, 136)); wall.subList(122, 136).clear(); }
        tail = wall.size() - 1; currentTurn = dealer;
        initialDeal = true;
        for (int round = 0; round < rules.ruleSet().handTiles(); round++) for (int offset = 0; offset < 4; offset++) drawTile((dealer + offset) % 4, DrawKind.DEAL);
        drawTile(dealer, DrawKind.DEAL); drawsTaken[dealer] = 1;
        initialDeal = false;
        detectFlowerWin();
    }
    public MahjongRules rules() { return rules; }
    public boolean isJapanese() { return rules.isJapanese(); }
    /** Match points are supplied by the table coordinator; this snapshot controls riichi eligibility. */
    public void setJapanesePoints(int[] points) {
        if (points == null || points.length != 4) throw new IllegalArgumentException("日本麻將點數必須有四席");
        japanesePoints = points.clone();
    }
    public int[] japanesePoints() { return japanesePoints.clone(); }
    public boolean riichi(int seat) { seat(seat); return isJapanese() && declaredListening[seat]; }
    public boolean riichiEstablished(int seat) { return riichi(seat) && pendingRiichiSeat != seat; }
    public int riichiDiscardIndex(int seat) { seat(seat); return isJapanese() ? riichiDiscardIndices[seat] : -1; }
    public boolean doubleRiichi(int seat) { seat(seat); return isJapanese() && doubleRiichi[seat]; }
    public boolean ippatsu(int seat) { seat(seat); return isJapanese() && ippatsu[seat]; }
    public int kanCount() { return kanCount; }
    public List<Integer> doraIndicators() { return indicators(false); }
    /** Public only after the hand ends with a riichi winner. */
    public List<Integer> uraDoraIndicators() {
        return isFinished() && outcome.winners().stream().anyMatch(w -> riichi(w.seat())) ? indicators(true) : List.of();
    }
    private List<Integer> indicators(boolean ura) {
        if (!isJapanese()) return List.of();
        List<Integer> result = new ArrayList<>();
        for (int i = 0; i <= kanCount; i++) result.add(deadWall.get(4 + 2 * i + (ura ? 1 : 0)));
        return List.copyOf(result);
    }
    public boolean manualDraw() { return manualDraw; }
    public int dealer() { return dealer; }
    public int prevailingWind() { return prevailingWind; }
    public int dealerStreak() { return dealerStreak; }
    public int currentTurn() { return currentTurn; }
    public int lastTile() { return lastTile; }
    public int lastDiscarder() { return lastDiscarder; }
    public int lastDraw() { return lastDraw; }
    public int remainingWall() { return Math.max(0, tail - head + 1 - (isJapanese() ? 0 : rules.reserveTiles())); }
    public Phase phase() { return phase; }
    public boolean isFinished() { return phase == Phase.FINISHED; }
    public Outcome outcome() { return outcome; }
    public List<Integer> hands(int seat) { seat(seat); return List.copyOf(hands.get(seat)); }
    public List<Integer> flowers(int seat) { seat(seat); return List.copyOf(flowers.get(seat)); }
    public List<Integer> discards(int seat) { seat(seat); return List.copyOf(discards.get(seat)); }
    public List<Meld> melds(int seat) { seat(seat); return List.copyOf(melds.get(seat)); }
    public int seatWind(int seat) { seat(seat); return (seat - dealer + 4) % 4; }
    public boolean hasResponded(int seat) { seat(seat); return responses[seat] != null; }
    public boolean declaredListening(int seat) { seat(seat); return declaredListening[seat]; }
    public List<Integer> listeningWaits(int seat) { seat(seat); return List.copyOf(listeningWaits.get(seat)); }
    public boolean isFuriten(int seat) {
        seat(seat);
        if (!isJapanese()) return passedRon[seat];
        if (passedRon[seat] || riichiPassedRon[seat]) return true;
        List<Integer> waiting = new ArrayList<>(hands.get(seat));
        if (phase == Phase.DISCARD && seat == currentTurn && lastDraw >= 0) waiting.remove(Integer.valueOf(lastDraw));
        List<Integer> waits = HandAnalyzer.waits(waiting, melds.get(seat), rules.ruleSet());
        return waits.stream().anyMatch(discardHistory.get(seat)::contains);
    }
    /** Legal discard types that leave a ready hand and can declare the house-rule hand lock. */
    public List<Integer> listeningDiscards(int seat) {
        seat(seat);
        if (phase != Phase.DISCARD || seat != currentTurn || declaredListening[seat]) return List.of();
        if (isJapanese() && (japanesePoints[seat] < 1000 || lastDraw < 0 || melds.get(seat).stream().anyMatch(Meld::open))) return List.of();
        if (listeningCacheSeat == seat && hands.get(seat).equals(listeningCacheHand) && melds.get(seat).equals(listeningCacheMelds))
            return listeningCacheOptions;
        List<Integer> options = new ArrayList<>();
        for (int tile : hands.get(seat)) if (!options.contains(tile)) {
            List<Integer> after = new ArrayList<>(hands.get(seat)); after.remove(Integer.valueOf(tile));
            if (canDiscard(seat, tile) && !HandAnalyzer.waits(after, melds.get(seat), rules.ruleSet()).isEmpty()) options.add(tile);
        }
        listeningCacheSeat = seat; listeningCacheHand = List.copyOf(hands.get(seat)); listeningCacheMelds = List.copyOf(melds.get(seat));
        listeningCacheOptions = List.copyOf(options);
        return listeningCacheOptions;
    }
    public boolean canDiscard(int seat, int tile) {
        seat(seat);
        return phase == Phase.DISCARD && seat == currentTurn && hands.get(seat).contains(tile)
                && (!declaredListening[seat] || tile == lastDraw) && (!isJapanese() || !forbiddenDiscards.contains(tile));
    }
    public void declareListening(int seat, int discardTile) {
        requireTurn(seat);
        if (!listeningDiscards(seat).contains(discardTile)) throw new IllegalArgumentException("必須選擇出牌後已聽牌的手牌，才能宣告聽牌");
        List<Integer> after = new ArrayList<>(hands.get(seat)); after.remove(Integer.valueOf(discardTile));
        List<Integer> waits = HandAnalyzer.waits(after, melds.get(seat), rules.ruleSet());
        boolean first = discardCounts[seat] == 0 && !callsOccurred;
        discard(seat, discardTile);
        if (!isJapanese() || !isFinished() || outcome.draw() || outcome.selfDraw()) {
            declaredListening[seat] = true; listeningWaits.set(seat, waits);
            if (isJapanese()) {
                doubleRiichi[seat] = first; ippatsu[seat] = true;
                riichiDiscardIndices[seat] = discards.get(seat).size() - 1;
                if (phase == Phase.CLAIM) pendingRiichiSeat = seat;
            }
        }
    }
    public EnumSet<Action> validActions(int seat) {
        seat(seat);
        if (phase == Phase.FINISHED) return EnumSet.noneOf(Action.class);
        if (phase == Phase.DRAW) return seat == currentTurn ? EnumSet.of(Action.DRAW) : EnumSet.noneOf(Action.class);
        if (phase == Phase.CLAIM || phase == Phase.KAN_CLAIM)
            return responses[seat] == null ? claimOptions.get(seat).clone() : EnumSet.noneOf(Action.class);
        EnumSet<Action> result = EnumSet.noneOf(Action.class);
        if (seat != currentTurn) return result;
        if (lastDraw >= 0 && (isJapanese() || drawKind != DrawKind.OPEN_KAN) && canWin(seat, true, false)) result.add(Action.TSUMO);
        if (!concealedKanOptions(seat).isEmpty() || !addedKanOptions(seat).isEmpty()) result.add(Action.KAN);
        if (!listeningDiscards(seat).isEmpty()) result.add(Action.LISTEN);
        return result;
    }
    /** Starts of all legal same-suit runs, e.g. 0 means one/two/three man. */
    public List<Integer> chiOptions(int seat) {
        seat(seat);
        if (declaredListening[seat] || phase != Phase.CLAIM || seat != (lastDiscarder + 1) % 4 || lastTile < 0 || lastTile >= 27 || responses[seat] != null)
            return List.of();
        List<Integer> result = new ArrayList<>();
        int min = Math.max(lastTile / 9 * 9, lastTile - 2), max = Math.min(lastTile, lastTile / 9 * 9 + 6);
        for (int start = min; start <= max; start++) {
            boolean ok = true;
            for (int tile = start; tile < start + 3; tile++) if (tile != lastTile && count(hands.get(seat), tile) == 0) ok = false;
            if (ok && (!isJapanese() || remainingWall() > 0 && hasDiscardAfterCall(seat, Action.CHI, start))) result.add(start);
        }
        return List.copyOf(result);
    }
    public List<Integer> concealedKanOptions(int seat) {
        seat(seat);
        if (declaredListening[seat] && !isJapanese() || phase != Phase.DISCARD || seat != currentTurn || !canKan() || lastDraw < 0) return List.of();
        List<Integer> result = new ArrayList<>();
        for (int t = 0; t < 34; t++) if (count(hands.get(seat), t) == 4 && (!riichi(seat) || riichiKanPreservesHand(seat, t))) result.add(t);
        return List.copyOf(result);
    }
    public List<Integer> addedKanOptions(int seat) {
        seat(seat);
        if (phase != Phase.DISCARD || seat != currentTurn || !canKan() || lastDraw < 0 || riichi(seat)) return List.of();
        List<Integer> result = new ArrayList<>();
        for (Meld m : melds.get(seat)) if (m.kind() == Meld.Kind.TRIPLET && m.open() && hands.get(seat).contains(m.tile())) {
            if (declaredListening[seat]) {
                if (m.tile() != lastDraw) continue;
                List<Integer> after = new ArrayList<>(hands.get(seat)); after.remove(Integer.valueOf(m.tile()));
                List<Meld> upgraded = new ArrayList<>(melds.get(seat));
                upgraded.set(upgraded.indexOf(m), new Meld(Meld.Kind.QUAD, m.tile(), true, m.fromSeat(), m.calledTile()));
                if (!HandAnalyzer.waits(after, upgraded).equals(listeningWaits.get(seat))) continue;
            }
            result.add(m.tile());
        }
        return List.copyOf(result);
    }
    private boolean canKan() { return remainingWall() > 0 && (!isJapanese() || kanCount < 4); }
    private boolean riichiKanPreservesHand(int seat, int tile) {
        if (tile != lastDraw) return false;
        List<Integer> before = new ArrayList<>(hands.get(seat)); before.remove(Integer.valueOf(tile));
        List<Integer> after = new ArrayList<>(hands.get(seat)); remove(after, tile, 4);
        List<Meld> declared = new ArrayList<>(melds.get(seat)); declared.add(new Meld(Meld.Kind.QUAD, tile, false, seat));
        if (!HandAnalyzer.waits(after, declared, rules.ruleSet()).equals(listeningWaits.get(seat))) return false;
        for (int winning = 0; winning < 34; winning++) {
            List<Integer> complete = new ArrayList<>(before); complete.add(winning);
            if (HandAnalyzer.isSevenPairs(complete) || HandAnalyzer.isThirteenOrphans(complete)) return false;
            for (var d : HandAnalyzer.structureDecompositions(complete, melds.get(seat), rules.ruleSet()))
                if (d.concealedMelds().stream().noneMatch(m -> m.kind() == Meld.Kind.TRIPLET && m.tile() == tile)) return false;
        }
        return true;
    }
    private void interruptIppatsu() { if (isJapanese()) java.util.Arrays.fill(ippatsu, false); }
    private List<Integer> callForbidden(Action action, int start) {
        List<Integer> result = new ArrayList<>(); result.add(lastTile);
        if (action == Action.CHI) {
            if (lastTile == start && start % 9 <= 5) result.add(start + 3);
            if (lastTile == start + 2 && start % 9 >= 1) result.add(start - 1);
        }
        return result;
    }
    private boolean hasDiscardAfterCall(int seat, Action action, int start) {
        List<Integer> after = new ArrayList<>(hands.get(seat));
        if (action == Action.CHI) {
            for (int tile = start; tile < start + 3; tile++) if (tile != lastTile) after.remove(Integer.valueOf(tile));
        } else remove(after, lastTile, 2);
        List<Integer> banned = callForbidden(action, start);
        return after.stream().anyMatch(t -> !banned.contains(t));
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
        if (declaredListening[seat] && tile != lastDraw) throw new IllegalArgumentException(isJapanese()
                ? "已立直，只能打出剛摸到的牌；可和牌或不改聽口及牌型的暗槓"
                : "已宣告聽牌，只能打出剛摸到的牌；可胡牌或不改聽口的加槓");
        if (isJapanese() && forbiddenDiscards.contains(tile)) throw new IllegalArgumentException("吃碰後不能立即換食同牌或筋牌");
        if (isJapanese()) { passedRon[seat] = false; ippatsu[seat] = false; discardHistory.get(seat).add(tile); forbiddenDiscards.clear(); }
        if (riichi(seat) && riichiDiscardPending[seat]) {
            riichiDiscardIndices[seat] = discards.get(seat).size(); riichiDiscardPending[seat] = false;
        }
        hands.get(seat).remove(Integer.valueOf(tile)); discards.get(seat).add(tile);
        lastTile = tile; lastDiscarder = seat; totalDiscards++; discardCounts[seat]++;
        lastDiscardAtWallEnd = remainingWall() == 0 && drawKind != DrawKind.DEAL;
        lastDraw = -1; phase = Phase.CLAIM;
        prepareClaims(false);
    }
    public void claim(int seat, Action action, int chiStart) {
        seat(seat);
        if (action == null || phase != Phase.CLAIM && phase != Phase.KAN_CLAIM || !validActions(seat).contains(action))
            throw new IllegalArgumentException("現在不能執行這個動作");
        if (action == Action.CHI && !chiOptions(seat).contains(chiStart)) throw new IllegalArgumentException("不合法的吃牌組合");
        if (action != Action.RON && claimOptions.get(seat).contains(Action.RON)) {
            passedRon[seat] = true;
            if (riichi(seat)) riichiPassedRon[seat] = true;
        }
        responses[seat] = new Response(action, chiStart);
        resolveIfReady();
    }
    public void tsumo(int seat) {
        requireTurn(seat);
        if (!validActions(seat).contains(Action.TSUMO)) throw new IllegalArgumentException(isJapanese() ? "尚未構成有役的日本麻將和牌，不能自摸" : "尚未構成五組面子加一對將眼，不能自摸");
        finishNormal(List.of(seat), -1, true, false);
    }
    public void concealedKan(int seat, int tile) {
        requireTurn(seat);
        if (!concealedKanOptions(seat).contains(tile)) throw new IllegalArgumentException("現在不能暗槓這張牌");
        remove(hands.get(seat), tile, 4); melds.get(seat).add(new Meld(Meld.Kind.QUAD, tile, false, seat));
        callsOccurred = true; interruptIppatsu(); drawForTurn(seat, DrawKind.KAN);
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
            else discard(seat, lastDraw >= 0 && canDiscard(seat, lastDraw) ? lastDraw : hands.get(seat).stream().filter(t -> canDiscard(seat, t)).reduce((a,b) -> b).orElseThrow());
        }
    }
    private void prepareClaims(boolean robKong) {
        for (int s = 0; s < 4; s++) { responses[s] = null; claimOptions.get(s).clear(); }
        for (int s = 0; s < 4; s++) if (s != lastDiscarder) {
            EnumSet<Action> allowed = claimOptions.get(s);
            boolean win = !isFuriten(s) && canWin(s, false, robKong);
            if (win) allowed.add(Action.RON);
            else if (isJapanese()) {
                List<Integer> complete = new ArrayList<>(hands.get(s)); complete.add(lastTile);
                if (HandAnalyzer.canWin(complete, melds.get(s), rules.ruleSet())) {
                    passedRon[s] = true;
                    if (riichi(s)) riichiPassedRon[s] = true;
                }
            }
            if (!robKong && !declaredListening[s] && (!isJapanese() || remainingWall() > 0)) {
                int have = count(hands.get(s), lastTile);
                if (have >= 2 && (!isJapanese() || hasDiscardAfterCall(s, Action.PON, -1))) allowed.add(Action.PON);
                if (have >= 3 && canKan()) allowed.add(Action.KAN);
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
            if (isJapanese() && pendingRiichiSeat == lastDiscarder) {
                declaredListening[lastDiscarder] = false; doubleRiichi[lastDiscarder] = false; ippatsu[lastDiscarder] = false;
                riichiDiscardIndices[lastDiscarder] = -1; riichiDiscardPending[lastDiscarder] = false;
                listeningWaits.set(lastDiscarder, List.of()); pendingRiichiSeat = -1;
            }
            finishNormal(isJapanese() ? List.of(ronWinners.getFirst()) : ronWinners, lastDiscarder, false, rob); return;
        }
        if (pendingOther) return;
        pendingRiichiSeat = -1;
        if (phase == Phase.KAN_CLAIM) {
            int s = lastDiscarder; Meld previous = melds.get(s).get(pendingAddedMeld);
            remove(hands.get(s), lastTile, 1);
            melds.get(s).set(pendingAddedMeld, new Meld(Meld.Kind.QUAD, lastTile, true, previous.fromSeat(), previous.calledTile()));
            pendingAddedMeld = -1; callsOccurred = true; interruptIppatsu(); drawForTurn(s, DrawKind.KAN); return;
        }
        for (int priority = 0; priority < 2; priority++) for (int offset = 1; offset <= 3; offset++) {
            int s = (lastDiscarder + offset) % 4; Response r = responses[s];
            if (r == null) continue;
            boolean match = priority == 0 ? r.action() == Action.PON || r.action() == Action.KAN : r.action() == Action.CHI;
            if (!match) continue;
            if (riichi(lastDiscarder) && riichiDiscardIndices[lastDiscarder] == discards.get(lastDiscarder).size() - 1) {
                riichiDiscardIndices[lastDiscarder] = -1; riichiDiscardPending[lastDiscarder] = true;
            }
            discards.get(lastDiscarder).remove(discards.get(lastDiscarder).size() - 1);
            if (r.action() == Action.CHI) {
                for (int tile = r.chiStart(); tile < r.chiStart() + 3; tile++) if (tile != lastTile) remove(hands.get(s), tile, 1);
                melds.get(s).add(new Meld(Meld.Kind.SEQUENCE, r.chiStart(), true, lastDiscarder, lastTile));
            } else {
                remove(hands.get(s), lastTile, r.action() == Action.KAN ? 3 : 2);
                melds.get(s).add(new Meld(r.action() == Action.KAN ? Meld.Kind.QUAD : Meld.Kind.TRIPLET, lastTile, true, lastDiscarder, lastTile));
            }
            callsOccurred = true; currentTurn = s; phase = Phase.DISCARD; lastDraw = -1;
            interruptIppatsu();
            if (isJapanese()) {
                if (r.action() != Action.KAN) forbiddenDiscards = callForbidden(r.action(), r.chiStart());
            }
            if (r.action() == Action.KAN) drawForTurn(s, DrawKind.OPEN_KAN);
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
        currentTurn = s; phase = Phase.DISCARD; lastDraw = -1;
        if (!isJapanese()) passedRon[s] = false;
        if (isJapanese()) forbiddenDiscards.clear();
        if (kind == DrawKind.NORMAL) drawsTaken[s]++;
        drawTile(s, kind);
    }
    private void drawTile(int s, DrawKind kind) {
        DrawKind effective = kind;
        boolean supplement = kind == DrawKind.KAN || kind == DrawKind.OPEN_KAN;
        if (isJapanese() && supplement) {
            if (kanCount >= 4 || remainingWall() == 0) throw new IllegalStateException("王牌補牌已用盡");
            int tile = deadWall.set(kanCount++, wall.get(tail--));
            hands.get(s).add(tile); Collections.sort(hands.get(s)); lastDraw = tile; drawKind = kind;
            return;
        }
        while (head <= tail) {
            int tile = supplement ? wall.get(tail--) : wall.get(head++);
            if (Tiles.flower(tile)) {
                flowers.get(s).add(tile); Collections.sort(flowers.get(s));
                if (!initialDeal && detectFlowerWin()) return;
                supplement = true;
                if (effective != DrawKind.KAN && effective != DrawKind.OPEN_KAN) effective = DrawKind.FLOWER;
                // Last live tile may itself be a flower; allow its replacement from the retained tail.
            } else {
                hands.get(s).add(tile); Collections.sort(hands.get(s));
                lastDraw = tile; drawKind = effective; return;
            }
        }
        finishDraw();
    }
    private boolean detectFlowerWin() {
        if (isJapanese()) return false;
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
            if (isJapanese()) {
                Score score = japaneseScore(winner, selfDraw, robKong);
                if (score == null) throw new IllegalStateException("日本麻將必須有役才能和牌");
                wins.add(new Win(winner, score)); continue;
            }
            List<Integer> complete = new ArrayList<>(hands.get(winner));
            int tile = selfDraw ? lastDraw : lastTile;
            if (!selfDraw) complete.add(tile);
            boolean heavenly = selfDraw && winner == dealer && totalDiscards == 0 && !callsOccurred;
            boolean earthly = selfDraw && winner != dealer && drawsTaken[winner] == 1 && discardCounts[winner] == 0 && !callsOccurred;
            boolean human = !selfDraw && !robKong && winner != dealer && source == dealer && totalDiscards == 1 && !callsOccurred;
            HandScorer.Context ctx = new HandScorer.Context(tile, selfDraw, seatWind(winner), prevailingWind,
                    heavenly, earthly, human,
                    selfDraw && remainingWall() == 0 && drawKind != DrawKind.DEAL,
                    !selfDraw && !robKong && lastDiscardAtWallEnd,
                    selfDraw && (drawKind == DrawKind.KAN || drawKind == DrawKind.FLOWER || remainingWall() == 0 && drawKind == DrawKind.NORMAL), robKong);
            Score score = HandScorer.score(complete, melds.get(winner), flowers.get(winner), ctx, rules);
            if (score == null) throw new IllegalStateException("已通過胡牌檢查的手牌無法計分");
            wins.add(new Win(winner, score));
        }
        finish(wins, source, selfDraw, robKong ? "搶槓胡" : selfDraw ? "自摸" : "榮和（放槍胡牌）");
    }
    private boolean canWin(int winner, boolean selfDraw, boolean robKong) {
        if (isJapanese()) return japaneseScore(winner, selfDraw, robKong) != null;
        List<Integer> complete = new ArrayList<>(hands.get(winner));
        if (!selfDraw) complete.add(lastTile);
        return HandAnalyzer.canWin(complete, melds.get(winner));
    }
    private Score japaneseScore(int winner, boolean selfDraw, boolean robKong) {
        List<Integer> complete = new ArrayList<>(hands.get(winner));
        int tile = selfDraw ? lastDraw : lastTile;
        if (!selfDraw) complete.add(tile);
        RiichiScorer.Context ctx = new RiichiScorer.Context(tile, selfDraw, seatWind(winner), prevailingWind,
                riichi(winner), doubleRiichi[winner], ippatsu[winner],
                selfDraw && winner == dealer && totalDiscards == 0 && !callsOccurred,
                selfDraw && winner != dealer && drawsTaken[winner] == 1 && discardCounts[winner] == 0 && !callsOccurred,
                selfDraw && remainingWall() == 0 && drawKind == DrawKind.NORMAL,
                !selfDraw && !robKong && lastDiscardAtWallEnd,
                selfDraw && (drawKind == DrawKind.KAN || drawKind == DrawKind.OPEN_KAN), robKong,
                doraIndicators(), riichi(winner) ? indicators(true) : List.of(),
                !selfDraw && !robKong && winner != dealer && drawsTaken[winner] == 0 && discardCounts[winner] == 0 && !callsOccurred);
        return RiichiScorer.score(complete, melds.get(winner), ctx);
    }
    private void finish(List<Win> wins, int source, boolean selfDraw, String reason) {
        List<Payment> payments = new ArrayList<>();
        for (Win win : wins) for (int payer = 0; payer < 4; payer++)
            if (payer != win.seat() && (selfDraw || payer == source)) {
                int tai = win.score().tai() + (!isJapanese() && (payer == dealer || win.seat() == dealer) ? 1 + 2 * dealerStreak : 0);
                long diamonds = rules.baseDiamonds() + (long) rules.diamondsPerTai() * tai;
                payments.add(new Payment(payer, win.seat(), diamonds, tai));
            }
        outcome = new Outcome(wins, payments, source, selfDraw, false, reason); phase = Phase.FINISHED;
    }
    private void finishDraw() {
        if (isJapanese()) {
            List<Win> nagashi = new ArrayList<>();
            for (int s = 0; s < 4; s++) if (isNagashiMangan(s))
                nagashi.add(new Win(s, new Score(5, List.of(new Score.Tai("nagashi_mangan", "流局滿貫", 5)), rules.unitLabel(), 0, 0)));
            if (!nagashi.isEmpty()) { finish(nagashi, -1, true, "流局滿貫"); return; }
            List<Integer> ready = tenpaiSeats();
            List<Integer> points = new ArrayList<>(List.of(0, 0, 0, 0));
            if (!ready.isEmpty() && ready.size() < 4) for (int s = 0; s < 4; s++)
                points.set(s, ready.contains(s) ? 3000 / ready.size() : -3000 / (4 - ready.size()));
            outcome = new Outcome(List.of(), List.of(), -1, false, true,
                    "流局：牌山已盡", ready, points);
        } else outcome = new Outcome(List.of(), List.of(), -1, false, true, "流局：已到保留牌，未有人胡牌");
        phase = Phase.FINISHED;
    }
    /** Structural tenpai is sufficient; furiten and lack of yaku do not make a hand noten. */
    public List<Integer> tenpaiSeats() {
        List<Integer> result = new ArrayList<>();
        for (int s = 0; s < 4; s++) if (!HandAnalyzer.waits(hands.get(s), melds.get(s), rules.ruleSet()).isEmpty()) result.add(s);
        return List.copyOf(result);
    }
    public boolean isNagashiMangan(int seat) {
        seat(seat);
        return isJapanese() && !discards.get(seat).isEmpty()
                && melds.get(seat).stream().noneMatch(Meld::open)
                && discardHistory.get(seat).size() == discards.get(seat).size()
                && discards.get(seat).stream().allMatch(Tiles::terminalOrHonor);
    }
    private void requireTurn(int seat) {
        seat(seat);
        if (phase != Phase.DISCARD || seat != currentTurn) throw new IllegalArgumentException("現在不是你的出牌回合");
    }
    private void readObject(ObjectInputStream input) throws IOException, ClassNotFoundException {
        input.defaultReadObject();
        if (declaredListening == null) declaredListening = new boolean[4];
        if (listeningWaits == null) listeningWaits = new ArrayList<>(List.of(List.of(), List.of(), List.of(), List.of()));
        if (riichiPassedRon == null) riichiPassedRon = new boolean[4];
        if (doubleRiichi == null) doubleRiichi = new boolean[4];
        if (ippatsu == null) ippatsu = new boolean[4];
        if (deadWall == null) deadWall = new ArrayList<>();
        if (forbiddenDiscards == null) forbiddenDiscards = new ArrayList<>();
        if (japanesePoints == null) japanesePoints = new int[]{25000, 25000, 25000, 25000};
        if (riichiDiscardIndices == null) riichiDiscardIndices = new int[]{-1, -1, -1, -1};
        if (riichiDiscardPending == null) riichiDiscardPending = new boolean[4];
        if (discardHistory == null) {
            discardHistory = new ArrayList<>();
            for (List<Integer> pile : discards) discardHistory.add(new ArrayList<>(pile));
            pendingRiichiSeat = -1;
        }
        // Version 1.1.3 used KAN for every supplement. An exposed kong appends an
        // open quad claimed from another seat; added kong instead sets discarder=self,
        // while a closed kong appends a closed quad. Preserve those distinctions on load.
        if (drawKind == DrawKind.KAN && phase == Phase.DISCARD && lastDiscarder >= 0 && lastDiscarder != currentTurn
                && !melds.get(currentTurn).isEmpty()) {
            Meld latest = melds.get(currentTurn).getLast();
            if (latest.kind() == Meld.Kind.QUAD && latest.open() && latest.tile() == lastTile && latest.fromSeat() == lastDiscarder)
                drawKind = DrawKind.OPEN_KAN;
        }
    }
    private static void seat(int seat) { if (seat < 0 || seat > 3) throw new IllegalArgumentException("座位必須為 0～3"); }
    private static int count(List<Integer> list, int tile) { return Collections.frequency(list, tile); }
    private static void remove(List<Integer> list, int tile, int amount) {
        if (count(list, tile) < amount) throw new IllegalStateException("手牌張數不足");
        for (int i = 0; i < amount; i++) list.remove(Integer.valueOf(tile));
    }
}
