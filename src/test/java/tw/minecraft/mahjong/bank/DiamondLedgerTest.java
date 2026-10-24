package tw.minecraft.mahjong.bank;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import tw.minecraft.mahjong.bank.DiamondLedger.*;

/** Standalone assertions; no Bukkit/test-framework dependency. */
public final class DiamondLedgerTest {
    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID C = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID D = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final List<UUID> PLAYERS = List.of(A, B, C, D);
    private static int checks;

    public static void main(String[] args) throws Exception {
        if (args.length == 2 && args[0].equals("crash-worker")) {
            DiamondLedger ledger = new DiamondLedger(Path.of(args[1]));
            for (UUID player : PLAYERS) ledger.credit(player, 400, "seed:" + player);
            ledger.reserve("crash-game", PLAYERS, 399, new byte[]{1, 3, 5});
            ledger.stage("pending-deposit", A, 7, Direction.DEPOSIT);
            ledger.stage("pending-withdrawal", B, 1, Direction.WITHDRAWAL);
            Runtime.getRuntime().halt(0); // No close or shutdown hooks: actual process crash recovery.
        }
        Path directory = Files.createTempDirectory(args.length > 0 ? Path.of(args[0]) : Path.of("."), "bank-tests-");
        walletAndEscrow(directory.resolve("normal.bin"));
        physical(directory.resolve("physical.bin"));
        rollback(directory.resolve("rollback.bin"));
        overflow(directory.resolve("overflow.bin"));
        crashRecovery(directory.resolve("crash.bin"));
        corruptFailClosed(directory.resolve("corrupt.bin"));
        System.out.println("DiamondLedgerTest PASS: " + checks + " assertions; " + directory.toAbsolutePath());
    }

    private static void walletAndEscrow(Path file) throws Exception {
        try (DiamondLedger ledger = new DiamondLedger(file)) {
            for (UUID player : PLAYERS) ledger.credit(player, 400, "seed:" + player);
            check(ledger.totalHeld() == 1600, "Initial conservation");
            check(!ledger.credit(A, 400, "seed:" + A), "Credit retry is idempotent");
            expect(IllegalStateException.class, () -> ledger.credit(A, 1, "seed:" + A));
            expect(IllegalArgumentException.class, () -> ledger.credit(A, -1, "negative"));
            expect(IllegalArgumentException.class, () -> ledger.debit(A, 0, "zero"));
            check(ledger.reserve("triple-win", PLAYERS, 399, new byte[]{4, 5}), "Reserve applies");
            check(ledger.balance(A) == 1 && ledger.totalHeld() == 1600, "Reserve holds total" );
            check(!ledger.reserve("triple-win", PLAYERS, 399), "Reserve retry no-op");
            expect(IllegalStateException.class, () -> ledger.reserve("triple-win", PLAYERS, 398));
            expect(IllegalStateException.class, () -> ledger.reserve("insufficient", PLAYERS, 2));
            check(ledger.openGames().size() == 1 && ledger.balance(A) == 1, "Failed reserve rolls back");
            byte[] checkpoint = new byte[]{8, 9};
            ledger.saveGame("triple-win", checkpoint);
            checkpoint[0] = 0;
            byte[] read = ledger.attachment("triple-win");
            read[0] = 0;
            check(ledger.attachment("triple-win")[0] == 8, "Checkpoint arrays isolated");
            ledger.openGames().get("triple-win").attachment()[0] = 0;
            check(ledger.attachment("triple-win")[0] == 8, "Views cannot mutate checkpoint");
            List<Transfer> payouts = List.of(new Transfer(A, B, 133), new Transfer(A, C, 133), new Transfer(A, D, 133));
            expect(IllegalStateException.class, () -> ledger.settle("triple-win", List.of(new Transfer(A, B, 400))));
            expect(IllegalArgumentException.class, () -> ledger.settle("triple-win", List.of(new Transfer(A, UUID.randomUUID(), 1))));
            check(ledger.balance(A) == 1 && ledger.openGames().size() == 1, "Invalid settlement rolls back");
            check(ledger.settle("triple-win", payouts, new byte[]{99}), "Three winners settled");
            check(ledger.balance(A) == 1 && ledger.balance(B) == 533 && ledger.balance(C) == 533
                    && ledger.balance(D) == 533 && ledger.totalHeld() == 1600, "Three winners conserve diamonds");
            check(!ledger.settle("triple-win", List.of(payouts.get(2), payouts.get(0), payouts.get(1))), "Order-independent settlement retry");
            check(!ledger.reserve("triple-win", PLAYERS, 399), "Closed id cannot be reserved again");
            expect(IllegalStateException.class, () -> ledger.settle("triple-win", List.of()));
            expect(IllegalStateException.class, () -> ledger.cancel("triple-win"));
            expect(IllegalStateException.class, () -> ledger.saveGame("triple-win", new byte[0]));
            expect(IOException.class, () -> { try (DiamondLedger ignored = new DiamondLedger(file)) {} });
        }
        try (DiamondLedger ledger = new DiamondLedger(file)) {
            check(ledger.balance(B) == 533 && ledger.openGames().isEmpty(), "Reload retains settlement");
            check(ledger.closedGames().get("triple-win").status() == GameStatus.SETTLED, "Closed history survives reload");
            expect(UnsupportedOperationException.class, () -> ledger.closedGames().clear());
            check(ledger.attachment("triple-win")[0] == 99, "Settlement checkpoint atomic");
            check(!ledger.credit(A, 400, "seed:" + A), "Credit dedupe survives reload");
            ledger.reserve("refund", List.of(B, C), 500);
            check(ledger.balance(B) == 33, "New game reserve");
            check(ledger.cancel("refund") && !ledger.cancel("refund"), "Cancel exactly once");
            check(ledger.balance(B) == 533 && ledger.totalHeld() == 1600, "Cancel conserves funds");
            check(ledger.closedGames().get("refund").status() == GameStatus.CANCELLED, "Closed history includes cancellation");
        }
    }

    private static void physical(Path file) throws Exception {
        try (DiamondLedger ledger = new DiamondLedger(file)) {
            check(ledger.stage("deposit", A, 40, Direction.DEPOSIT), "Stage deposit");
            check(ledger.balance(A) == 0 && ledger.totalHeld() == 0, "Unconfirmed deposit is not spendable");
            check(!ledger.stage("deposit", A, 40, Direction.DEPOSIT), "Staging is idempotent");
            expect(IllegalStateException.class, () -> ledger.stage("deposit", A, 41, Direction.DEPOSIT));
            ledger.complete("deposit");
            check(!ledger.complete("deposit") && ledger.balance(A) == 40, "Deposit only credited once");
            expect(IllegalStateException.class, () -> ledger.cancelPhysical("deposit"));
            ledger.stage("out", A, 30, Direction.WITHDRAWAL);
            check(ledger.balance(A) == 10 && ledger.totalHeld() == 40, "Withdrawal immediately reserves");
            expect(IllegalStateException.class, () -> ledger.stage("double-spend", A, 11, Direction.WITHDRAWAL));
            check(ledger.physical("double-spend").isEmpty(), "Failed withdrawal absent");
            check(ledger.pendingTransfers().size() == 1, "Only pending returned");
        }
        try (DiamondLedger ledger = new DiamondLedger(file)) {
            check(ledger.balance(A) == 10 && ledger.pendingTransfers().size() == 1, "Pending transfer survives reload");
            ledger.cancelPhysical("out");
            check(!ledger.cancelPhysical("out") && ledger.balance(A) == 40, "Withdraw cancellation refunds once");
            expect(IllegalStateException.class, () -> ledger.complete("out"));
            ledger.stage("out2", A, 30, Direction.WITHDRAWAL);
            ledger.complete("out2");
            check(ledger.balance(A) == 10 && ledger.totalHeld() == 10 && ledger.pendingTransfers().isEmpty(), "Confirmed physical withdrawal leaves ledger");
            ledger.stage("unused-deposit", A, 20, Direction.DEPOSIT);
            ledger.cancelPhysical("unused-deposit");
            check(ledger.balance(A) == 10, "Cancelled deposit creates no value");
        }
    }

    private static void rollback(Path file) throws Exception {
        try (DiamondLedger ledger = new DiamondLedger(file)) {
            ledger.credit(A, 10, "seed");
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            Files.createDirectory(temporary); // Force write failure before atomic rename.
            try { expect(IOException.class, () -> ledger.credit(A, 5, "failed-write")); }
            finally { Files.delete(temporary); }
            check(ledger.balance(A) == 10, "I/O failure does not mutate memory");
            ledger.credit(A, 5, "failed-write");
            check(ledger.balance(A) == 15, "Failed transaction can retry correctly");
        }
        try (DiamondLedger ledger = new DiamondLedger(file)) {
            check(ledger.balance(A) == 15, "Disk and memory agree after failure");
        }
    }

    private static void overflow(Path file) throws Exception {
        try (DiamondLedger ledger = new DiamondLedger(file)) {
            ledger.credit(A, Long.MAX_VALUE, "max");
            expect(ArithmeticException.class, () -> ledger.credit(A, 1, "overflow"));
            check(ledger.balance(A) == Long.MAX_VALUE, "Credit overflow rolls back");
            ledger.credit(B, 2, "b");
            ledger.reserve("overflow-game", List.of(A, B), 1);
            expect(ArithmeticException.class, () -> ledger.settle("overflow-game", List.of(new Transfer(B, A, 1))));
            check(ledger.openGames().size() == 1 && ledger.balance(B) == 1, "Settlement overflow rolls back all seats");
            ledger.cancel("overflow-game");
            expect(IllegalArgumentException.class, () -> ledger.reserve("duplicates", List.of(A, A), 1));
        }
    }

    private static void crashRecovery(Path file) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        Process process = new ProcessBuilder(java, "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                DiamondLedgerTest.class.getName(), "crash-worker", file.toString()).inheritIO().start();
        check(process.waitFor() == 0, "Crash worker completed writes");
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(temporary, new byte[]{0, 1, 2}); // Orphan from interrupted subsequent snapshot.
        try (DiamondLedger ledger = new DiamondLedger(file)) {
            check(ledger.totalHeld() == 1600 && ledger.balance(A) == 1 && ledger.balance(B) == 0, "Hard crash conserves all stakes");
            check(ledger.pendingTransfers().size() == 2 && ledger.openGames().size() == 1, "Crash recovers pending transfer and game");
            check(Arrays.equals(ledger.attachment("crash-game"), new byte[]{1, 3, 5}), "Crash recovers checkpoint");
            ledger.cancel("crash-game");
            ledger.cancelPhysical("pending-withdrawal");
            ledger.cancelPhysical("pending-deposit");
            check(ledger.balance(A) == 400 && ledger.balance(B) == 400 && ledger.totalHeld() == 1600, "Recovery refund is exact");
        }
    }

    private static void corruptFailClosed(Path file) throws Exception {
        try (DiamondLedger ledger = new DiamondLedger(file)) { ledger.credit(A, 25, "seed"); }
        byte[] bytes = Files.readAllBytes(file);
        bytes[bytes.length - 1] ^= 1;
        Files.write(file, bytes);
        expect(IOException.class, () -> { try (DiamondLedger ignored = new DiamondLedger(file)) {} });
        check(Arrays.equals(Files.readAllBytes(file), bytes), "Corrupt snapshot is not overwritten");
    }

    private static void check(boolean condition, String description) {
        checks++;
        if (!condition) throw new AssertionError(description);
    }

    private static void expect(Class<? extends Throwable> expected, ThrowingRunnable operation) throws Exception {
        checks++;
        try { operation.run(); }
        catch (Throwable actual) {
            if (expected.isInstance(actual)) return;
            throw new AssertionError("Expected " + expected + ", got " + actual, actual);
        }
        throw new AssertionError("Expected " + expected);
    }

    @FunctionalInterface private interface ThrowingRunnable { void run() throws Exception; }
}
