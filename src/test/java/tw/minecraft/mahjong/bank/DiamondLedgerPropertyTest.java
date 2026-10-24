package tw.minecraft.mahjong.bank;

import java.math.BigInteger;
import java.nio.file.*;
import java.util.*;
import tw.minecraft.mahjong.bank.DiamondLedger.Transfer;

/** Randomized independent BigInteger balance model and hostile public-API inputs. */
public final class DiamondLedgerPropertyTest {
    private static int checks;
    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory(args.length > 0 ? Path.of(args[0]) : Path.of("."), "bank-properties-");
        Path file = directory.resolve("model.bin");
        Random random = new Random(0x16DA1400L);
        List<UUID> players = List.of(new UUID(0, 1), new UUID(0, 2), new UUID(0, 3), new UUID(0, 4));
        Map<UUID, BigInteger> model = new HashMap<>();
        DiamondLedger ledger = new DiamondLedger(file);
        try {
            for (UUID player : players) {
                ledger.credit(player, 100_000, "initial:" + player);
                model.put(player, BigInteger.valueOf(100_000));
            }
            for (int round = 0; round < 120; round++) {
                String game = "game:" + round;
                ledger.reserve(game, players, 399, new byte[]{(byte) round});
                verifyTotal(ledger, 400_000);
                byte[] beforeAttack = Files.readAllBytes(file);
                try {
                    ledger.settle(game, List.of(new Transfer(players.get(0), new UUID(1, round), 1)));
                    throw new AssertionError("Foreign seat accepted");
                } catch (IllegalArgumentException expected) { checks++; }
                try {
                    ledger.settle(game, List.of(new Transfer(players.get(0), players.get(1), 400)));
                    throw new AssertionError("Unfunded payout accepted");
                } catch (IllegalStateException expected) { checks++; }
                check(Arrays.equals(beforeAttack, Files.readAllBytes(file)), "Rejected forged transactions touched persisted state");
                if (round % 11 == 0) {
                    ledger.cancel(game);
                    check(!ledger.cancel(game), "Refund duplicated");
                } else {
                    ArrayList<Transfer> payouts = new ArrayList<>();
                    for (UUID from : players) {
                        int left = 399;
                        ArrayList<UUID> targets = new ArrayList<>(players);
                        Collections.shuffle(targets, random);
                        for (UUID to : targets) {
                            if (from.equals(to)) continue;
                            int amount = random.nextInt(left + 1);
                            if (amount == 0) continue;
                            left -= amount;
                            payouts.add(new Transfer(from, to, amount));
                            model.put(from, model.get(from).subtract(BigInteger.valueOf(amount)));
                            model.put(to, model.get(to).add(BigInteger.valueOf(amount)));
                        }
                    }
                    ledger.settle(game, payouts);
                    // Split one liability and shuffle all entries. Same payment,
                    // different encoding must never charge the loser twice.
                    ArrayList<Transfer> alternative = new ArrayList<>();
                    for (Transfer payment : payouts) {
                        if (payment.amount() > 1) {
                            alternative.add(new Transfer(payment.from(), payment.to(), 1));
                            alternative.add(new Transfer(payment.from(), payment.to(), payment.amount() - 1));
                        } else alternative.add(payment);
                    }
                    Collections.shuffle(alternative, random);
                    check(!ledger.settle(game, alternative), "Equivalent settlement not idempotent");
                    try {
                        ledger.cancel(game);
                        throw new AssertionError("Settled stake refunded again");
                    } catch (IllegalStateException expected) { checks++; }
                }
                verifyTotal(ledger, 400_000);
                for (UUID player : players)
                    check(BigInteger.valueOf(ledger.balance(player)).equals(model.get(player)), "Balance differs from independent model");
                if (round % 7 == 0) {
                    ledger.close();
                    ledger = new DiamondLedger(file);
                    check(!ledger.reserve(game, players, 399), "Restart reopened finished game id");
                    check(ledger.openGames().isEmpty(), "Restart resurrected game");
                }
            }
        } finally { ledger.close(); }
        System.out.println("DiamondLedgerPropertyTest PASS: " + checks + " assertions, 120 randomized games; " + directory.toAbsolutePath());
    }
    private static void verifyTotal(DiamondLedger ledger, long expected) {
        check(ledger.totalHeld() == expected, "Diamond conservation violated");
    }
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
