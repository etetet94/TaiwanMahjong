package tw.minecraft.mahjong.engine;

import java.math.BigInteger;
import java.util.*;
import tw.minecraft.mahjong.engine.MahjongGame.Payment;

/** Proportionally limits each payer's gross liability to that seat's available funds. */
public final class EscrowSettlement {
    private EscrowSettlement() { }

    /**
     * Keeps original order, seat identities and tai; only diamond amounts change.
     * Each payer is apportioned independently using exact largest remainders.
     * Tied remainders go to the earlier original payment. Zero payments remain.
     * The caller supplies recorded escrow plus any allowed wallet balance, never a configured stake.
     */
    public static List<Payment> cap(List<Payment> payments, long[] seatCaps) {
        Objects.requireNonNull(payments, "payments");
        Objects.requireNonNull(seatCaps, "seatCaps");
        if (seatCaps.length != 4) throw new IllegalArgumentException("Exactly four seat caps are required");
        long[] caps = seatCaps.clone();
        for (long value : caps) if (value < 0) throw new IllegalArgumentException("Seat caps cannot be negative");
        List<Payment> source = List.copyOf(payments);
        ArrayList<Payment> result = new ArrayList<>(source);
        for (Payment payment : source) {
            if (payment.fromSeat() < 0 || payment.fromSeat() >= 4 || payment.toSeat() < 0 || payment.toSeat() >= 4
                    || payment.fromSeat() == payment.toSeat() || payment.diamonds() < 0)
                throw new IllegalArgumentException("Invalid payment seats or diamond amount");
        }
        for (int payer = 0; payer < 4; payer++) {
            BigInteger total = BigInteger.ZERO;
            for (Payment payment : source) if (payment.fromSeat() == payer)
                total = total.add(BigInteger.valueOf(payment.diamonds()));
            BigInteger limit = BigInteger.valueOf(caps[payer]);
            if (total.compareTo(limit) <= 0) continue;
            ArrayList<Share> shares = new ArrayList<>();
            long allocated = 0;
            for (int index = 0; index < source.size(); index++) {
                Payment payment = source.get(index);
                if (payment.fromSeat() != payer) continue;
                BigInteger[] division = BigInteger.valueOf(payment.diamonds()).multiply(limit).divideAndRemainder(total);
                long amount = division[0].longValueExact();
                allocated = Math.addExact(allocated, amount);
                shares.add(new Share(index, division[1]));
                result.set(index, withAmount(payment, amount));
            }
            long remaining = caps[payer] - allocated;
            shares.sort(Comparator.comparing(Share::remainder).reversed().thenComparingInt(Share::index));
            // Largest-remainder apportionment leaves fewer units than shares.
            if (remaining < 0 || remaining > shares.size()) throw new IllegalStateException("Invalid apportionment remainder");
            for (int i = 0; i < remaining; i++) {
                int index = shares.get(i).index();
                Payment prior = result.get(index);
                result.set(index, withAmount(prior, Math.addExact(prior.diamonds(), 1)));
            }
        }
        return List.copyOf(result);
    }

    private static Payment withAmount(Payment source, long amount) {
        return new Payment(source.fromSeat(), source.toSeat(), amount, source.tai());
    }
    private record Share(int index, BigInteger remainder) { }
}
