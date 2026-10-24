package tw.minecraft.mahjong.bank;

import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/**
 * Integer-diamond wallet and game escrow. Every successful mutation is durable
 * before it becomes visible in memory. The caller must confirm physical item
 * movement separately, using a saved player receipt before complete().
 *
 * No database or inventory code is used here. A corrupt ledger fails closed;
 * it is never silently replaced with an empty ledger or an older snapshot.
 */
public final class DiamondLedger implements AutoCloseable {
    private static final int MAGIC = 0x544D4A42; // TMJB
    private static final int VERSION = 1;
    private static final int MAX_SNAPSHOT = 128 * 1024 * 1024;
    private static final int MAX_ATTACHMENT = 8 * 1024 * 1024;
    private final Path file;
    private final FileChannel lockChannel;
    private final FileLock fileLock;
    private State state;
    private boolean closed;

    public enum Direction { DEPOSIT, WITHDRAWAL }
    public enum PhysicalStatus { PENDING, COMPLETED, CANCELLED }
    public enum GameStatus { OPEN, SETTLED, CANCELLED }

    public record Transfer(UUID from, UUID to, long amount) implements Serializable {
        private static final long serialVersionUID = 1L;
        public Transfer {
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
            positive(amount);
            if (from.equals(to)) throw new IllegalArgumentException("Cannot transfer to self");
        }
    }

    public record PhysicalTransfer(String id, UUID player, long amount,
                                   Direction direction, PhysicalStatus status) implements Serializable {
        private static final long serialVersionUID = 1L;
    }

    public record GameView(String id, List<UUID> players, long amountEach,
                           Map<UUID, Long> escrow, GameStatus status, byte[] attachment) {
        public GameView {
            players = List.copyOf(players);
            escrow = Map.copyOf(escrow);
            attachment = attachment.clone();
        }
        @Override public byte[] attachment() { return attachment.clone(); }
    }

    public DiamondLedger(Path ledgerFile) throws IOException {
        file = Objects.requireNonNull(ledgerFile).toAbsolutePath().normalize();
        Files.createDirectories(file.getParent());
        lockChannel = FileChannel.open(file.resolveSibling(file.getFileName() + ".lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock acquired;
        try {
            acquired = lockChannel.tryLock();
            if (acquired == null) throw new IOException("Ledger is already open: " + file);
        } catch (IOException | RuntimeException e) {
            lockChannel.close();
            throw new IOException("Cannot lock ledger: " + file, e);
        }
        fileLock = acquired;
        try {
            state = Files.exists(file) ? read(file) : new State();
        } catch (IOException | RuntimeException e) {
            fileLock.release();
            lockChannel.close();
            throw e;
        }
    }

    public synchronized long balance(UUID player) {
        checkOpen();
        return state.wallets.getOrDefault(Objects.requireNonNull(player), 0L);
    }

    /** Administrative/import credit. Inventory deposits should use stage/complete. */
    public synchronized boolean credit(UUID player, long amount, String transactionId) throws IOException {
        return walletTransaction(player, amount, transactionId, true);
    }

    /** Administrative debit. Inventory withdrawals should use stage/complete. */
    public synchronized boolean debit(UUID player, long amount, String transactionId) throws IOException {
        return walletTransaction(player, amount, transactionId, false);
    }

    private boolean walletTransaction(UUID player, long amount, String id, boolean credit) throws IOException {
        checkOpen(); validId(id); positive(amount); Objects.requireNonNull(player);
        WalletTransaction request = new WalletTransaction(player, amount, credit);
        WalletTransaction prior = state.transactions.get(id);
        if (prior != null) {
            if (!prior.equals(request)) throw new IllegalStateException("Transaction id reused with different values");
            return false;
        }
        State next = new State(state);
        changeWallet(next, player, credit ? amount : -amount);
        next.transactions.put(id, request);
        commit(next);
        return true;
    }

    public synchronized boolean reserve(String gameId, List<UUID> players, long amountEach) throws IOException {
        return reserve(gameId, players, amountEach, new byte[0]);
    }

    /** Moves all seats' stakes together; one insufficient wallet changes nothing. */
    public synchronized boolean reserve(String gameId, List<UUID> players, long amountEach,
                                        byte[] attachment) throws IOException {
        checkOpen(); validId(gameId); positive(amountEach);
        List<UUID> seats = List.copyOf(players);
        if (seats.isEmpty() || seats.size() != new HashSet<>(seats).size())
            throw new IllegalArgumentException("Players must be nonempty and unique");
        byte[] bytes = attachmentCopy(attachment);
        Game prior = state.games.get(gameId);
        if (prior != null) {
            if (!prior.players.equals(seats) || prior.amountEach != amountEach)
                throw new IllegalStateException("Game id reused with different stakes or seats");
            // A retry cannot overwrite a later game checkpoint or reopen a closed game.
            return false;
        }
        State next = new State(state);
        LinkedHashMap<UUID, Long> escrow = new LinkedHashMap<>();
        for (UUID player : seats) {
            changeWallet(next, player, -amountEach);
            escrow.put(player, amountEach);
        }
        next.games.put(gameId, new Game(seats, amountEach, escrow, GameStatus.OPEN, bytes, List.of()));
        commit(next);
        return true;
    }

    public synchronized boolean saveGame(String gameId, byte[] attachment) throws IOException {
        checkOpen(); validId(gameId);
        Game game = requireGame(gameId);
        if (game.status != GameStatus.OPEN) throw new IllegalStateException("Game is closed");
        byte[] bytes = attachmentCopy(attachment);
        if (Arrays.equals(game.attachment, bytes)) return false;
        State next = new State(state);
        next.games.put(gameId, game.withAttachment(bytes));
        commit(next);
        return true;
    }

    public synchronized byte[] attachment(String gameId) {
        checkOpen();
        return requireGame(gameId).attachment.clone();
    }

    public synchronized Optional<GameView> game(String gameId) {
        checkOpen();
        Game game = state.games.get(gameId);
        return game == null ? Optional.empty() : Optional.of(view(gameId, game));
    }

    public synchronized Map<String, GameView> openGames() {
        checkOpen();
        LinkedHashMap<String, GameView> result = new LinkedHashMap<>();
        state.games.forEach((id, game) -> {
            if (game.status == GameStatus.OPEN) result.put(id, view(id, game));
        });
        return Collections.unmodifiableMap(result);
    }

    /** Read-only settled/cancelled history for idempotent recovery of table metadata. */
    public synchronized Map<String, GameView> closedGames() {
        checkOpen();
        LinkedHashMap<String, GameView> result = new LinkedHashMap<>();
        state.games.forEach((id, game) -> {
            if (game.status != GameStatus.OPEN) result.put(id, view(id, game));
        });
        return Collections.unmodifiableMap(result);
    }

    public synchronized boolean settle(String gameId, List<Transfer> transfers) throws IOException {
        return settle(gameId, transfers, null);
    }

    /**
     * Pays every winner, then returns each seat's residual escrow, atomically.
     * Outgoing liabilities must fit the original reserve even if the seat wins
     * money elsewhere. A different retry is rejected; the same payment is no-op.
     * finalAttachment may be null to retain the previous checkpoint.
     */
    public synchronized boolean settle(String gameId, List<Transfer> transfers,
                                       byte[] finalAttachment) throws IOException {
        checkOpen(); validId(gameId);
        Game game = requireGame(gameId);
        List<Transfer> normalized = normalize(transfers);
        if (game.status == GameStatus.SETTLED) {
            if (!game.payments.equals(normalized)) throw new IllegalStateException("Game already settled differently");
            return false;
        }
        if (game.status != GameStatus.OPEN) throw new IllegalStateException("Game was cancelled");
        Map<UUID, Long> outgoing = new HashMap<>();
        Map<UUID, Long> incoming = new HashMap<>();
        for (Transfer transfer : normalized) {
            if (!game.escrow.containsKey(transfer.from) || !game.escrow.containsKey(transfer.to))
                throw new IllegalArgumentException("Transfer includes a player outside the game");
            outgoing.merge(transfer.from, transfer.amount, Math::addExact);
            incoming.merge(transfer.to, transfer.amount, Math::addExact);
        }
        State next = new State(state);
        for (UUID player : game.players) {
            long original = game.escrow.get(player);
            long paid = outgoing.getOrDefault(player, 0L);
            if (paid > original) throw new IllegalStateException("Payout exceeds reserved diamonds");
            long refund = Math.addExact(original - paid, incoming.getOrDefault(player, 0L));
            changeWallet(next, player, refund);
        }
        byte[] bytes = finalAttachment == null ? game.attachment : attachmentCopy(finalAttachment);
        next.games.put(gameId, new Game(game.players, game.amountEach, Map.of(),
                GameStatus.SETTLED, bytes, normalized));
        commit(next);
        return true;
    }

    public synchronized boolean cancel(String gameId) throws IOException {
        checkOpen(); validId(gameId);
        Game game = requireGame(gameId);
        if (game.status == GameStatus.CANCELLED) return false;
        if (game.status != GameStatus.OPEN) throw new IllegalStateException("Game is already settled");
        State next = new State(state);
        for (Map.Entry<UUID, Long> entry : game.escrow.entrySet())
            changeWallet(next, entry.getKey(), entry.getValue());
        next.games.put(gameId, new Game(game.players, game.amountEach, Map.of(),
                GameStatus.CANCELLED, game.attachment, List.of()));
        commit(next);
        return true;
    }

    /**
     * DEPOSIT does not credit the account until complete. WITHDRAWAL reserves
     * the balance now, preventing another withdrawal/game from spending it.
     */
    public synchronized boolean stage(String id, UUID player, long amount, Direction direction) throws IOException {
        checkOpen(); validId(id); Objects.requireNonNull(player); Objects.requireNonNull(direction); positive(amount);
        PhysicalTransfer prior = state.physical.get(id);
        if (prior != null) {
            if (!prior.player.equals(player) || prior.amount != amount || prior.direction != direction)
                throw new IllegalStateException("Physical transfer id reused with different values");
            return false;
        }
        State next = new State(state);
        if (direction == Direction.WITHDRAWAL) changeWallet(next, player, -amount);
        next.physical.put(id, new PhysicalTransfer(id, player, amount, direction, PhysicalStatus.PENDING));
        commit(next);
        return true;
    }

    public synchronized boolean complete(String id) throws IOException {
        checkOpen(); validId(id);
        PhysicalTransfer transfer = requirePhysical(id);
        if (transfer.status == PhysicalStatus.COMPLETED) return false;
        if (transfer.status != PhysicalStatus.PENDING) throw new IllegalStateException("Transfer was cancelled");
        State next = new State(state);
        if (transfer.direction == Direction.DEPOSIT) changeWallet(next, transfer.player, transfer.amount);
        next.physical.put(id, new PhysicalTransfer(id, transfer.player, transfer.amount,
                transfer.direction, PhysicalStatus.COMPLETED));
        commit(next);
        return true;
    }

    /** Caller must first establish that no physical item movement took place. */
    public synchronized boolean cancelPhysical(String id) throws IOException {
        checkOpen(); validId(id);
        PhysicalTransfer transfer = requirePhysical(id);
        if (transfer.status == PhysicalStatus.CANCELLED) return false;
        if (transfer.status != PhysicalStatus.PENDING) throw new IllegalStateException("Transfer already completed");
        State next = new State(state);
        if (transfer.direction == Direction.WITHDRAWAL) changeWallet(next, transfer.player, transfer.amount);
        next.physical.put(id, new PhysicalTransfer(id, transfer.player, transfer.amount,
                transfer.direction, PhysicalStatus.CANCELLED));
        commit(next);
        return true;
    }

    public synchronized Optional<PhysicalTransfer> physical(String id) {
        checkOpen();
        return Optional.ofNullable(state.physical.get(id));
    }

    public synchronized List<PhysicalTransfer> pendingTransfers() {
        checkOpen();
        return state.physical.values().stream().filter(t -> t.status == PhysicalStatus.PENDING).toList();
    }

    /** All diamonds owned by this ledger, including stakes and pending withdrawals. */
    public synchronized long totalHeld() {
        checkOpen();
        long sum = 0;
        for (long value : state.wallets.values()) sum = Math.addExact(sum, value);
        for (Game game : state.games.values())
            for (long value : game.escrow.values()) sum = Math.addExact(sum, value);
        for (PhysicalTransfer transfer : state.physical.values())
            if (transfer.status == PhysicalStatus.PENDING && transfer.direction == Direction.WITHDRAWAL)
                sum = Math.addExact(sum, transfer.amount);
        return sum;
    }

    private Game requireGame(String id) {
        Game game = state.games.get(id);
        if (game == null) throw new IllegalArgumentException("Unknown game: " + id);
        return game;
    }

    private PhysicalTransfer requirePhysical(String id) {
        PhysicalTransfer transfer = state.physical.get(id);
        if (transfer == null) throw new IllegalArgumentException("Unknown physical transfer: " + id);
        return transfer;
    }

    private static GameView view(String id, Game game) {
        return new GameView(id, game.players, game.amountEach, game.escrow, game.status, game.attachment);
    }

    private static List<Transfer> normalize(List<Transfer> transfers) {
        record Pair(UUID from, UUID to) { }
        Map<Pair, Long> totals = new HashMap<>();
        for (Transfer transfer : Objects.requireNonNull(transfers)) {
            Objects.requireNonNull(transfer);
            totals.merge(new Pair(transfer.from, transfer.to), transfer.amount, Math::addExact);
        }
        ArrayList<Transfer> result = new ArrayList<>();
        totals.forEach((pair, amount) -> result.add(new Transfer(pair.from, pair.to, amount)));
        result.sort(Comparator.comparing((Transfer t) -> t.from.toString()).thenComparing(t -> t.to.toString()));
        return List.copyOf(result);
    }

    private static void changeWallet(State next, UUID player, long delta) {
        long after = Math.addExact(next.wallets.getOrDefault(player, 0L), delta);
        if (after < 0) throw new IllegalStateException("Insufficient diamonds for " + player);
        next.wallets.put(player, after);
    }

    private static void validId(String id) {
        if (id == null || id.isBlank() || id.length() > 200) throw new IllegalArgumentException("Invalid transaction/game id");
    }

    private static void positive(long value) {
        if (value <= 0) throw new IllegalArgumentException("Diamond amount must be positive");
    }

    private static byte[] attachmentCopy(byte[] bytes) {
        Objects.requireNonNull(bytes);
        if (bytes.length > MAX_ATTACHMENT) throw new IllegalArgumentException("Game checkpoint is too large");
        return bytes.clone();
    }

    private void checkOpen() {
        if (closed) throw new IllegalStateException("Ledger is closed");
    }

    private void commit(State next) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(buffer)) { output.writeObject(next); }
        byte[] bytes = buffer.toByteArray();
        if (bytes.length > MAX_SNAPSHOT) throw new IOException("Ledger snapshot exceeds configured safety limit");
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        try (FileOutputStream stream = new FileOutputStream(temporary.toFile());
             DataOutputStream output = new DataOutputStream(stream)) {
            output.writeInt(MAGIC);
            output.writeInt(VERSION);
            output.writeInt(bytes.length);
            output.write(sha256(bytes));
            output.write(bytes);
            output.flush();
            stream.getFD().sync();
        }
        // No non-atomic fallback: an unsupported filesystem must fail safely.
        atomicReplace(temporary, file);
        state = next;
        // Directory fsync is unavailable on Windows; the committed file itself
        // has already been fsynced. Best effort improves durability on Unix.
        try (FileChannel directory = FileChannel.open(file.getParent(), StandardOpenOption.READ)) {
            directory.force(true);
        } catch (IOException | UnsupportedOperationException ignored) { }
    }

    private static void atomicReplace(Path temporary, Path file) throws IOException {
        // Windows antivirus/indexing readers can briefly deny replacement of a
        // freshly closed file. Retry the same fsynced snapshot, never a partial
        // copy or a delete-then-move operation. Permanent denial still fails.
        for (int attempt = 0; ; attempt++) {
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                return;
            } catch (AccessDeniedException blocked) {
                if (attempt == 4) throw blocked;
                try { Thread.sleep(10L << attempt); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while committing ledger", blocked);
                }
            }
        }
    }

    private static State read(Path file) throws IOException {
        try (DataInputStream input = new DataInputStream(Files.newInputStream(file))) {
            if (input.readInt() != MAGIC || input.readInt() != VERSION) throw new IOException("Unsupported ledger format");
            int length = input.readInt();
            if (length < 0 || length > MAX_SNAPSHOT) throw new IOException("Invalid ledger length");
            byte[] expected = input.readNBytes(32);
            byte[] bytes = input.readNBytes(length);
            if (expected.length != 32 || bytes.length != length || input.read() != -1
                    || !MessageDigest.isEqual(expected, sha256(bytes))) throw new IOException("Ledger checksum mismatch");
            try (ObjectInputStream object = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
                object.setObjectInputFilter(info -> {
                    if (info.depth() > 40 || info.references() > 2_000_000 || info.streamBytes() > MAX_SNAPSHOT)
                        return ObjectInputFilter.Status.REJECTED;
                    Class<?> type = info.serialClass();
                    if (type == null) return ObjectInputFilter.Status.UNDECIDED;
                    while (type.isArray()) type = type.componentType();
                    String name = type.getName();
                    return type.isPrimitive() || name.startsWith("tw.minecraft.mahjong.bank.DiamondLedger$")
                            || name.startsWith("java.util.") || name.startsWith("java.lang.")
                            ? ObjectInputFilter.Status.ALLOWED : ObjectInputFilter.Status.REJECTED;
                });
                Object loaded = object.readObject();
                if (!(loaded instanceof State state)) throw new IOException("Invalid ledger root object");
                return state;
            } catch (ClassNotFoundException | RuntimeException e) { throw new IOException("Unreadable ledger", e); }
        }
    }

    private static byte[] sha256(byte[] bytes) {
        try { return MessageDigest.getInstance("SHA-256").digest(bytes); }
        catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }

    @Override public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        try { fileLock.release(); } finally { lockChannel.close(); }
    }

    private record WalletTransaction(UUID player, long amount, boolean credit) implements Serializable {
        private static final long serialVersionUID = 1L;
    }

    private static final class Game implements Serializable {
        private static final long serialVersionUID = 1L;
        final List<UUID> players;
        final long amountEach;
        final Map<UUID, Long> escrow;
        final GameStatus status;
        final byte[] attachment;
        final List<Transfer> payments;
        Game(List<UUID> players, long amountEach, Map<UUID, Long> escrow, GameStatus status,
             byte[] attachment, List<Transfer> payments) {
            this.players = List.copyOf(players);
            this.amountEach = amountEach;
            this.escrow = new LinkedHashMap<>(escrow);
            this.status = status;
            this.attachment = attachment.clone();
            this.payments = List.copyOf(payments);
        }
        Game withAttachment(byte[] bytes) { return new Game(players, amountEach, escrow, status, bytes, payments); }
    }

    private static final class State implements Serializable {
        private static final long serialVersionUID = 1L;
        final Map<UUID, Long> wallets;
        final Map<String, WalletTransaction> transactions;
        final Map<String, Game> games;
        final Map<String, PhysicalTransfer> physical;
        State() {
            wallets = new LinkedHashMap<>(); transactions = new LinkedHashMap<>();
            games = new LinkedHashMap<>(); physical = new LinkedHashMap<>();
        }
        State(State original) {
            wallets = new LinkedHashMap<>(original.wallets);
            transactions = new LinkedHashMap<>(original.transactions);
            games = new LinkedHashMap<>(original.games);
            physical = new LinkedHashMap<>(original.physical);
        }
    }
}
