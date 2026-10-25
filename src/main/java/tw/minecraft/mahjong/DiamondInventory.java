package tw.minecraft.mahjong;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.*;
import java.util.logging.Level;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import tw.minecraft.mahjong.bank.DiamondLedger;
import tw.minecraft.mahjong.bank.PlayerReceiptFile;

/** Durable inventory receipts bridge player.dat and the independent escrow ledger. */
final class DiamondInventory {
    private final TaiwanMahjongPlugin plugin;
    private final DiamondLedger ledger;
    private final NamespacedKey receipt;
    private final Set<UUID> blocked = new HashSet<>();

    DiamondInventory(TaiwanMahjongPlugin plugin, DiamondLedger ledger) {
        this.plugin = plugin; this.ledger = ledger;
        receipt = new NamespacedKey(plugin, "physical_transfer_receipt");
    }
    static boolean plain(ItemStack item) {
        return item != null && item.getType() == Material.DIAMOND && item.isSimilar(new ItemStack(Material.DIAMOND));
    }
    static int count(Player player) {
        int total = 0;
        for (ItemStack item : player.getInventory().getStorageContents()) if (plain(item)) total += item.getAmount();
        return total;
    }
    static int capacity(Player player) {
        int total = 0;
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (item == null || item.getType().isAir()) total += 64;
            else if (plain(item)) total += Math.max(0, 64 - item.getAmount());
        }
        return total;
    }

    /** Recovery is decided by verified disk proof, never just the live marker. */
    void recover(Player player) throws IOException {
        mainThread();
        var pending = ledger.pendingTransfers().stream().filter(t -> t.player().equals(player.getUniqueId())).toList();
        if (pending.isEmpty()) { blocked.remove(player.getUniqueId()); return; }
        try {
            if (pending.size() != 1) throw new IOException("同一玩家有多筆未確認的鑽石轉移，停止自動處理。");
            var transfer = pending.getFirst();
            String disk = diskMarker(player);
            String live = player.getPersistentDataContainer().get(receipt, PersistentDataType.STRING);
            if (!Objects.equals(disk, live)) throw new IOException("玩家記憶體與存檔收據不一致，需重新登入後確認。");
            if (transfer.id().equals(disk)) ledger.complete(transfer.id());
            else if (before(transfer.id()).equals(disk)) ledger.cancelPhysical(transfer.id());
            else throw new IOException("未確認轉移缺少對應的前／後存檔收據，鑽石帳戶暫停。");
            blocked.remove(player.getUniqueId());
            // Keep the last receipt. No second, potentially ambiguous cleanup save.
        } catch (IOException | RuntimeException ex) {
            pause(player, ex);
            if (ex instanceof IOException io) throw io;
            throw new IOException("無法安全恢復鑽石轉移", ex);
        }
    }

    void transfer(Player player, int amount, boolean deposit) throws IOException {
        transfer(player,amount,deposit,-1);
    }
    void transfer(Player player, int amount, boolean deposit,int preferredDepositSlot) throws IOException {
        mainThread();
        if (amount < 1 || amount > 2304) throw new IllegalArgumentException("數量必須為 1～2304。");
        if (blocked.contains(player.getUniqueId())) throw new IllegalArgumentException("鑽石帳戶正在確認存檔，請先重新登入；若仍被暫停請通知管理員。");
        recover(player);
        ItemStack[] original = cloneStorage(player.getInventory().getStorageContents());
        ItemStack[] replacement = prepare(original, amount, deposit,preferredDepositSlot);
        if (!deposit && ledger.balance(player.getUniqueId()) < amount) throw new IllegalArgumentException("鑽石帳戶餘額不足。");
        String id = UUID.randomUUID().toString();

        // A durable preimage proves that a crash before applying inventory
        // moved no physical diamonds. saveData alone does not report failures.
        try {
            player.getPersistentDataContainer().set(receipt, PersistentDataType.STRING, before(id));
            saveVerified(player, before(id));
        } catch (IOException | RuntimeException ex) {
            pause(player, ex);
            throw new IOException("無法確認玩家存檔，未開始存領鑽石。", ex);
        }
        ledger.stage(id, player.getUniqueId(), amount, deposit ? DiamondLedger.Direction.DEPOSIT : DiamondLedger.Direction.WITHDRAWAL);
        boolean inventoryApplied = false;
        try {
            player.getInventory().setStorageContents(replacement);
            inventoryApplied = true;
            player.getPersistentDataContainer().set(receipt, PersistentDataType.STRING, id);
            saveVerified(player, id);
            ledger.complete(id);
        } catch (IOException | RuntimeException ex) {
            // Preserve a matching inventory+receipt image and the pending entry.
            // Rejoin loads the durable preimage or postimage; disk proof then
            // cancels or completes exactly once. Never guess after an I/O error.
            try {
                player.getInventory().setStorageContents(inventoryApplied ? replacement : original);
                player.getPersistentDataContainer().set(receipt, PersistentDataType.STRING, inventoryApplied ? id : before(id));
                player.saveData();
            } catch (RuntimeException repairFailure) { ex.addSuppressed(repairFailure); }
            pause(player, ex);
            throw new IOException("存領結果正在等待存檔確認，已保留交易資料；請重新登入。", ex);
        }
        plugin.tell(player, (deposit ? "已存入 " : "已領回 ") + amount + " 顆鑽石。帳戶餘額：" + ledger.balance(player.getUniqueId()));
    }

    private static ItemStack[] prepare(ItemStack[] original, int amount, boolean deposit) {
        return prepare(original,amount,deposit,-1);
    }
    private static ItemStack[] prepare(ItemStack[] original,int amount,boolean deposit,int preferredSlot) {
        ItemStack[] result = cloneStorage(original);
        int left = amount;
        if (deposit) {
            if(preferredSlot!=-1){
                if(preferredSlot<0||preferredSlot>=result.length||!plain(result[preferredSlot])||result[preferredSlot].getAmount()<amount)
                    throw new IllegalArgumentException("這組鑽石已變動，請重新點選。");
                int rest=result[preferredSlot].getAmount()-amount;
                if(rest==0)result[preferredSlot]=null;else result[preferredSlot].setAmount(rest);
                left=0;
            }
            for (int i = 0; i < result.length && left > 0; i++) {
                if (!plain(result[i])) continue;
                int take = Math.min(left, result[i].getAmount()); left -= take;
                if (take == result[i].getAmount()) result[i] = null;
                else result[i].setAmount(result[i].getAmount() - take);
            }
            if (left != 0) throw new IllegalArgumentException("背包的原版鑽石不足；有名稱、附魔或特殊資料的寶石不會被扣除。");
        } else {
            // Split into real stacks, never use oversized addItem input.
            for (int i = 0; i < result.length && left > 0; i++) {
                if (!plain(result[i])) continue;
                int give = Math.min(left, Math.max(0, 64 - result[i].getAmount())); left -= give;
                result[i].setAmount(result[i].getAmount() + give);
            }
            for (int i = 0; i < result.length && left > 0; i++) {
                if (result[i] != null && !result[i].getType().isAir()) continue;
                int give = Math.min(left, 64); left -= give;
                result[i] = new ItemStack(Material.DIAMOND, give);
            }
            if (left != 0) throw new IllegalArgumentException("背包空間不足，請先整理空間。");
        }
        return result;
    }
    private static ItemStack[] cloneStorage(ItemStack[] source) {
        ItemStack[] result = source.clone();
        for (int i = 0; i < result.length; i++) if (result[i] != null) result[i] = result[i].clone();
        return result;
    }
    private static String before(String id) { return "before:" + id; }
    private static void mainThread() {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Diamond inventory changes require the server thread");
    }
    private Path playerFile(Player player) throws IOException {
        if (Bukkit.getWorlds().isEmpty()) throw new IOException("找不到主要世界的玩家資料夾。");
        Path world = Bukkit.getWorlds().getFirst().getWorldFolder().toPath().toAbsolutePath().normalize();
        // 26.3 returns <save>/dimensions/minecraft/overworld for World#getWorldFolder.
        // PlayerDataStorage belongs to <save>/players/data, not to the dimension.
        Path namespace = world.getParent();
        Path dimensions = namespace == null ? null : namespace.getParent();
        if (dimensions != null && dimensions.getFileName() != null
                && dimensions.getFileName().toString().equals("dimensions") && dimensions.getParent() != null)
            world = dimensions.getParent();
        Path directory = world.resolve("players").resolve("data");
        // Legacy layouts may still exist in isolated upgrade fixtures. Prefer
        // the current layout when both exist, matching 26.3 LevelResource.
        if (!Files.isDirectory(directory) && Files.isDirectory(world.resolve("playerdata")))
            directory = world.resolve("playerdata");
        return directory.resolve(player.getUniqueId()+".dat");
    }
    private String diskMarker(Player player) throws IOException {
        return PlayerReceiptFile.readAndSync(playerFile(player), player.getUniqueId(), receipt.toString());
    }
    private void saveVerified(Player player, String expected) throws IOException {
        player.saveData();
        if (!expected.equals(diskMarker(player))) throw new IOException("玩家存檔尚未寫入本次鑽石轉移收據。");
    }
    private void pause(Player player, Throwable failure) {
        blocked.add(player.getUniqueId());
        plugin.getLogger().log(Level.SEVERE, "鑽石帳戶暫停，保留未確認交易："+player.getUniqueId(), failure);
        // Disconnect before another tick can trade/drop an ambiguous inventory.
        if (player.isOnline()) player.kick(net.kyori.adventure.text.Component.text("麻將鑽石存領正在確認存檔，請重新登入；若仍發生請通知管理員。"));
    }
}
