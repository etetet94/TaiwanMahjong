package tw.minecraft.mahjong.engine;

import java.io.Serializable;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Persistent Japanese point ledger for an East round or hanchan. Currency moves only when the match ends.
 * No west extension, return bonus, or placement bonus. Unclaimed final riichi sticks go to the leader,
 * with initial seat order breaking a tie. Negative points end the match; exactly zero does not.
 */
public final class JapaneseMatch implements Serializable {
    private static final long serialVersionUID = 1L;
    public static final int STARTING_POINTS = 25_000;
    public record PointPayment(int fromSeat, int toSeat, long points) implements Serializable {}

    private final int rounds, diamondsPer1000, startingDealer;
    private long[] points = {STARTING_POINTS, STARTING_POINTS, STARTING_POINTS, STARTING_POINTS};
    private long[] handStartPoints = points.clone(), previousPoints = points.clone(), lastPointDeltas = new long[4];
    private boolean[] riichiDeposited = new boolean[4];
    private int roundIndex, honba, riichiPot;
    private long handId, lastAppliedHandId = -1;
    private boolean finished;
    private String endReason = "";

    public JapaneseMatch(int rounds, int diamondsPer1000) { this(rounds, diamondsPer1000, 0); }
    public JapaneseMatch(int rounds, int diamondsPer1000, int startingDealer) {
        if (rounds != 4 && rounds != 8) throw new IllegalArgumentException("日麻賽制必須為東風四局或半莊八局");
        if (diamondsPer1000 < 0) throw new IllegalArgumentException("每千點鑽石數不可為負數");
        seat(startingDealer);
        this.rounds = rounds; this.diamondsPer1000 = diamondsPer1000; this.startingDealer = startingDealer;
    }
    public int rounds() { return rounds; }
    public int diamondsPer1000() { return diamondsPer1000; }
    public int startingDealer() { return startingDealer; }
    public long handId() { return handId; }
    public int dealer() { return (startingDealer + roundIndex) % 4; }
    public int prevailingWind() { return roundIndex / 4; }
    public int roundIndex() { return roundIndex; }
    public int handNumberInRound() { return roundIndex % 4 + 1; }
    public int honba() { return honba; }
    public int riichiPot() { return riichiPot; }
    public int riichiSticks() { return riichiPot / 1000; }
    public boolean finished() { return finished; }
    public String endReason() { return endReason; }
    public long points(int seat) { seat(seat); return points[seat]; }
    public int[] points() { return Arrays.stream(points).mapToInt(Math::toIntExact).toArray(); }
    /** Snapshot for the game engine's riichi affordability check. */
    public int[] enginePoints() { return points(); }
    public long[] previousPoints() { return previousPoints.clone(); }
    /** Includes this hand's riichi deposit and any carried pot awarded at its conclusion. */
    public long[] lastPointDeltas() { return lastPointDeltas.clone(); }
    public long[] netPoints() { long[] net = points.clone(); for (int s = 0; s < 4; s++) net[s] -= STARTING_POINTS; return net; }
    public boolean canDeclareRiichi(int seat) {
        seat(seat); return !finished && !riichiDeposited[seat] && points[seat] >= 1000;
    }

    /** Returns false for a repeated record of the same seat's deposit; never charges twice. */
    public boolean declareRiichi(long expectedHandId, int seat) {
        seat(seat); requireCurrent(expectedHandId);
        if (riichiDeposited[seat]) return false;
        if (!canDeclareRiichi(seat)) throw new IllegalArgumentException("至少需要一千點才能立直");
        points[seat] -= 1000; riichiPot += 1000; riichiDeposited[seat] = true;
        return true;
    }

    /**
     * Applies one finished hand atomically. The just-applied ID is an idempotent no-op on retry;
     * any other stale/future ID is rejected. Construct the next game from the resulting dealer/wind/honba.
     */
    public boolean applyHand(long expectedHandId, MahjongGame game) {
        if (expectedHandId == lastAppliedHandId && expectedHandId >= 0) return false;
        requireCurrent(expectedHandId);
        if (game == null || !game.isJapanese() || !game.isFinished() || game.outcome() == null)
            throw new IllegalArgumentException("只能結算已完成的日麻牌局");
        if (game.dealer() != dealer() || game.prevailingWind() != prevailingWind())
            throw new IllegalArgumentException("牌局的莊家或場風與賽局不符");
        MahjongGame.Outcome outcome = game.outcome();
        long[] next = points.clone(); int nextPot = riichiPot;
        for (int s = 0; s < 4; s++) {
            if (game.riichi(s) && !riichiDeposited[s]) throw new IllegalStateException("立直供託尚未記錄");
            // A ron on the declaration discard cancels that declaration and refunds its immediate record.
            if (riichiDeposited[s] && !game.riichi(s)) { next[s] += 1000; nextPot -= 1000; }
        }
        boolean dealerContinues;
        if (outcome.draw()) {
            if (!outcome.winners().isEmpty() || outcome.pointDeltas().size() != 4)
                throw new IllegalArgumentException("無效的流局點數");
            long sum = 0;
            for (int s = 0; s < 4; s++) { int delta = outcome.pointDeltas().get(s); sum += delta; next[s] += delta; }
            if (sum != 0) throw new IllegalArgumentException("流局點數必須守恆");
            dealerContinues = outcome.tenpaiSeats().contains(dealer());
        } else {
            if (outcome.winners().isEmpty()) throw new IllegalArgumentException("和牌結算缺少贏家");
            for (MahjongGame.Win win : outcome.winners()) {
                seat(win.seat());
                for (PointPayment payment : pointPayments(win.score(), win.seat(), dealer(), outcome.selfDraw(), outcome.sourceSeat(), honba)) {
                    next[payment.fromSeat()] -= payment.points(); next[payment.toSeat()] += payment.points();
                }
            }
            // The engine orders ron winners by head-bump priority; nagashi uses stable seat order.
            next[outcome.winners().getFirst().seat()] += nextPot; nextPot = 0;
            dealerContinues = outcome.winners().stream().anyMatch(w -> w.seat() == dealer());
        }
        int nextHonba = outcome.draw() || dealerContinues ? Math.addExact(honba, 1) : 0;
        int nextRound = roundIndex + (dealerContinues ? 0 : 1);
        boolean bankrupt = Arrays.stream(next).anyMatch(value -> value < 0);
        boolean ending = bankrupt || nextRound >= rounds;
        if (ending && nextPot > 0) {
            int leader = 0; for (int s = 1; s < 4; s++) if (next[s] > next[leader]) leader = s;
            next[leader] += nextPot; nextPot = 0;
        }
        if (Arrays.stream(next).sum() + nextPot != 4L * STARTING_POINTS)
            throw new IllegalStateException("日麻點數與供託總額不守恆");
        previousPoints = handStartPoints.clone(); lastPointDeltas = new long[4];
        for (int s = 0; s < 4; s++) lastPointDeltas[s] = next[s] - previousPoints[s];
        points = next; riichiPot = nextPot; honba = nextHonba; roundIndex = nextRound;
        handStartPoints = points.clone(); riichiDeposited = new boolean[4];
        lastAppliedHandId = handId; handId++;
        finished = ending;
        if (ending) endReason = bankrupt ? "負點終局" : rounds == 4 ? "東風戰結束" : "半莊結束";
        return true;
    }

    /** Standard base points: no kiriage; counted yakuman at 13 han; actual yakuman stack. */
    public static long basePoints(Score score) {
        if (score == null || score.tai() <= 0 || score.yakumanCount() < 0) throw new IllegalArgumentException("無效的日麻和牌分數");
        if (score.yakumanCount() > 0) return Math.multiplyExact(8000L, score.yakumanCount());
        int han = score.tai();
        if (han >= 13) return 8000;
        if (han >= 11) return 6000;
        if (han >= 8) return 4000;
        if (han >= 6) return 3000;
        if (han >= 5) return 2000;
        int fu = score.fu();
        if (fu < 20 || fu != 25 && fu % 10 != 0) throw new IllegalArgumentException("無效的符數");
        return Math.min(2000L, (long) fu << (han + 2));
    }
    public static List<PointPayment> pointPayments(Score score, int winner, int dealer, boolean selfDraw, int source, int honba) {
        seat(winner); seat(dealer);
        if (honba < 0) throw new IllegalArgumentException("本場數不可為負數");
        long base = basePoints(score); List<PointPayment> payments = new ArrayList<>();
        if (selfDraw) {
            for (int payer = 0; payer < 4; payer++) if (payer != winner) {
                long share = roundHundred(base * (winner == dealer || payer == dealer ? 2 : 1)) + 100L * honba;
                payments.add(new PointPayment(payer, winner, share));
            }
        } else {
            seat(source); if (source == winner) throw new IllegalArgumentException("放銃者不可同時是和牌者");
            payments.add(new PointPayment(source, winner, roundHundred(base * (winner == dealer ? 6 : 4)) + 300L * honba));
        }
        return List.copyOf(payments);
    }
    private static long roundHundred(long points) { return Math.multiplyExact(Math.floorDiv(Math.addExact(points, 99), 100), 100); }

    /**
     * Converts net match changes only, without uma/oka. Total converted gain is rounded up once.
     * Credit and debt sides each receive that same integer total, allocated by largest fractional
     * remainder (initial seat order breaks ties). Thus transfers are deterministic and conserve diamonds.
     */
    public List<MahjongGame.Payment> finalPayments() {
        if (!finished) throw new IllegalStateException("日麻賽局尚未結束");
        return convertNetPoints(netPoints(), diamondsPer1000);
    }
    public static List<MahjongGame.Payment> convertNetPoints(long[] netPoints, int diamondsPer1000) {
        if (netPoints == null || netPoints.length != 4 || diamondsPer1000 < 0) throw new IllegalArgumentException("無效的點數換算");
        BigInteger[] credit = new BigInteger[4], debt = new BigInteger[4];
        BigInteger sum = BigInteger.ZERO, total = BigInteger.ZERO, rate = BigInteger.valueOf(diamondsPer1000);
        for (int s = 0; s < 4; s++) {
            BigInteger net = BigInteger.valueOf(netPoints[s]); sum = sum.add(net);
            BigInteger amount = net.abs().multiply(rate);
            credit[s] = net.signum() > 0 ? amount : BigInteger.ZERO;
            debt[s] = net.signum() < 0 ? amount : BigInteger.ZERO;
            total = total.add(credit[s]);
        }
        if (sum.signum() != 0) throw new IllegalArgumentException("淨點數必須守恆");
        long target = total.add(BigInteger.valueOf(999)).divide(BigInteger.valueOf(1000)).longValueExact();
        long[] receives = allocate(credit, target), pays = allocate(debt, target);
        List<MahjongGame.Payment> payments = new ArrayList<>();
        for (int from = 0; from < 4; from++) for (int to = 0; to < 4 && pays[from] > 0; to++) {
            long amount = Math.min(pays[from], receives[to]);
            if (amount > 0) { payments.add(new MahjongGame.Payment(from, to, amount, 0)); pays[from] -= amount; receives[to] -= amount; }
        }
        return List.copyOf(payments);
    }
    private static long[] allocate(BigInteger[] weights, long target) {
        long[] amounts = new long[4]; BigInteger[] remainder = new BigInteger[4]; long used = 0;
        for (int s = 0; s < 4; s++) {
            BigInteger[] division = weights[s].divideAndRemainder(BigInteger.valueOf(1000));
            amounts[s] = division[0].longValueExact(); remainder[s] = division[1]; used = Math.addExact(used, amounts[s]);
        }
        List<Integer> seats = new ArrayList<>(List.of(0, 1, 2, 3));
        seats.sort(Comparator.<Integer, BigInteger>comparing(s -> remainder[s]).reversed().thenComparingInt(Integer::intValue));
        long extra = target - used;
        if (extra < 0 || extra > 4) throw new IllegalStateException("換算餘數無效");
        for (int n = 0; n < extra; n++) amounts[seats.get(n)]++;
        return amounts;
    }
    private void requireCurrent(long expectedHandId) {
        if (finished) throw new IllegalStateException("日麻賽局已結束");
        if (expectedHandId != handId) throw new IllegalArgumentException("牌局編號不符，不能重複或越序結算");
    }
    private static void seat(int seat) { if (seat < 0 || seat >= 4) throw new IllegalArgumentException("座位必須為0至3"); }
}
