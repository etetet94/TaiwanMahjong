package tw.minecraft.mahjong.arena;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.Rotatable;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/** One persistent, non-destructive sky arena. This class never spawns combat entities. */
public final class SkyArena implements AutoCloseable {
    private static final int FORMAT = 1;
    private final JavaPlugin plugin;
    private final Path manifest;
    private final Set<ChunkKey> ownedTickets = new HashSet<>();
    private final Map<Key, BlockData> plan = new HashMap<>();
    private World world;
    private int x, y, z;
    private boolean ready;
    private record Key(int x, int y, int z) {}
    private record ChunkKey(int x, int z) {}

    public SkyArena(JavaPlugin plugin) {
        this.plugin = java.util.Objects.requireNonNull(plugin);
        manifest = plugin.getDataFolder().toPath().resolve("sky-arena.yml");
        for (ArenaGeometry.Cell cell : ArenaGeometry.blocks()) plan.put(new Key(cell.x(), cell.y(), cell.z()), data(cell.kind()));
    }

    /** Main-thread only. A failed or interrupted build never overwrites unrelated blocks. */
    public void ensureBuilt() throws IOException {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Arena construction must run on the server thread");
        if (ready) return;
        try {
            if (Files.exists(manifest)) {
                YamlConfiguration saved = new YamlConfiguration();
                try { saved.load(manifest.toFile()); }
                catch (InvalidConfigurationException e) { throw new IOException("競技場位置檔無法讀取，為保護地圖已停止施工", e); }
                if (number(saved, "format") != FORMAT) throw new IOException("不支援的競技場位置檔版本");
                try { world = Bukkit.getWorld(UUID.fromString(saved.getString("world-uuid", ""))); }
                catch (IllegalArgumentException e) { throw new IOException("競技場世界 UUID 無效", e); }
                if (world == null) throw new IOException("競技場原本的世界尚未載入，未另建場地");
                x = number(saved, "x"); y = number(saved, "y"); z = number(saved, "z");
                if (!validPlacement()) throw new IOException("競技場原本位置超出世界高度或邊界，未修改方塊");
                String status = saved.getString("status", "");
                if (!status.equals("planned") && !status.equals("complete")) throw new IOException("競技場施工狀態無效");
                holdChunks();
                String conflict = conflict(status.equals("planned"));
                if (conflict != null) throw new IOException("競技場方塊與原計畫不同，未覆蓋或自動重建：" + conflict);
                if (status.equals("planned")) buildMissing();
            } else {
                chooseEmptyPlacement();
                saveManifest("planned"); // Durable intent before the first world modification.
                buildMissing();
            }
            ready = true;
        } catch (IOException | RuntimeException failure) {
            close();
            throw failure;
        }
    }

    private void chooseEmptyPlacement() throws IOException {
        world = Bukkit.getWorlds().stream().filter(w -> w.getEnvironment() == World.Environment.NORMAL).findFirst()
            .orElseThrow(() -> new IOException("沒有已載入的主世界可建立競技場"));
        Location origin = world.getSpawnLocation();
        // The nearest candidate is east of spawn; bounded alternatives avoid existing sky builds.
        int[][] offsets = {{80,0},{0,80},{-80,0},{0,-80},{80,80},{80,-80},{-80,80},{-80,-80},
            {160,0},{0,160},{-160,0},{0,-160},{160,160},{160,-160},{-160,160},{-160,-160}};
        String lastConflict = "世界高度或邊界不足";
        for (int[] offset : offsets) {
            releaseChunks();
            x = origin.getBlockX() + offset[0]; z = origin.getBlockZ() + offset[1];
            y = Math.min(220, world.getMaxHeight() - 32);
            if (!validPlacement()) continue;
            holdChunks();
            int highest = world.getMinHeight();
            for (int dx = ArenaGeometry.MIN; dx <= ArenaGeometry.MAX; dx++) for (int dz = ArenaGeometry.MIN; dz <= ArenaGeometry.MAX; dz++) {
                highest = Math.max(highest, world.getHighestBlockYAt(x + dx, z + dz));
            }
            y = Math.min(Math.max(highest + 40, 220), world.getMaxHeight() - 32);
            if (!validPlacement()) continue;
            String occupied = emptyConflict();
            if (occupied == null) return;
            lastConflict = occupied;
        }
        releaseChunks();
        throw new IOException("找不到完整空白的 26×26 高空範圍，未修改任何方塊。最後檢查：" + lastConflict);
    }

    private boolean validPlacement() {
        if (world == null || y < world.getMinHeight() + 2 || y + ArenaGeometry.TOP >= world.getMaxHeight()) return false;
        if (Math.abs((long) x) > 29_999_900 || Math.abs((long) z) > 29_999_900) return false;
        var border = world.getWorldBorder();
        for (int dx : new int[] {ArenaGeometry.MIN, ArenaGeometry.MAX + 1}) for (int dz : new int[] {ArenaGeometry.MIN, ArenaGeometry.MAX + 1}) {
            if (!border.isInside(new Location(world, x + dx, y, z + dz))) return false;
        }
        return true;
    }

    private String emptyConflict() {
        for (int dx = ArenaGeometry.MIN; dx <= ArenaGeometry.MAX; dx++) for (int dz = ArenaGeometry.MIN; dz <= ArenaGeometry.MAX; dz++)
            for (int dy = 0; dy <= ArenaGeometry.TOP; dy++) {
                Block block = world.getBlockAt(x + dx, y + dy, z + dz);
                if (!block.getType().isAir()) return where(block);
            }
        return null;
    }

    /** Resumption accepts only original planned materials or air; completion is read-only. */
    private String conflict(boolean resume) {
        for (int dx = ArenaGeometry.MIN; dx <= ArenaGeometry.MAX; dx++) for (int dz = ArenaGeometry.MIN; dz <= ArenaGeometry.MAX; dz++)
            for (int dy = 0; dy <= ArenaGeometry.TOP; dy++) {
                Block block = world.getBlockAt(x + dx, y + dy, z + dz);
                BlockData expected = plan.get(new Key(dx, dy, dz));
                if (expected == null) { if (!block.getType().isAir()) return where(block); }
                else if (block.getType().isAir()) { if (!resume) return where(block); }
                else if (!block.getBlockData().matches(expected)) return where(block);
            }
        return null;
    }

    private void buildMissing() throws IOException {
        // Geometry order places support floors before seats/signs. No physics or natural-block replacement.
        for (ArenaGeometry.Cell cell : ArenaGeometry.blocks()) {
            Block block = world.getBlockAt(x + cell.x(), y + cell.y(), z + cell.z());
            BlockData expected = plan.get(new Key(cell.x(), cell.y(), cell.z()));
            if (block.getType().isAir()) {
                block.setBlockData(expected.clone(), false);
                if (cell.kind() == ArenaGeometry.Kind.SIGN && block.getState() instanceof Sign sign) {
                    var front = sign.getSide(Side.FRONT);
                    front.line(0, Component.text("伏守者高空擂台", NamedTextColor.DARK_AQUA).decorate(TextDecoration.BOLD));
                    front.line(1, Component.text("下層：十隻伏守者", NamedTextColor.BLACK));
                    front.line(2, Component.text("此層：安全觀戰席", NamedTextColor.BLACK));
                    front.line(3, Component.text("使用選單進入／離開", NamedTextColor.BLACK));
                    sign.setWaxed(true);
                    if (!sign.update(false, false)) throw new IOException("競技場告示牌儲存失敗");
                }
            } else if (!block.getBlockData().matches(expected)) throw new IOException("施工時偵測到新的方塊，已停止：" + where(block));
        }
        String problem = conflict(false);
        if (problem != null) throw new IOException("競技場施工檢查失敗：" + problem);
        // Persist all affected chunks before marking complete. If the process dies earlier, planned can resume safely.
        world.save();
        saveManifest("complete");
    }

    private static BlockData data(ArenaGeometry.Kind kind) {
        Material material = switch (kind) {
            case FLOOR -> Material.POLISHED_DEEPSLATE;
            case EDGE -> Material.DEEPSLATE_TILES;
            case LIGHT -> Material.SEA_LANTERN;
            case ACCENT -> Material.CYAN_TERRACOTTA;
            case GLASS -> Material.GLASS;
            case DECK -> Material.LIGHT_BLUE_STAINED_GLASS;
            case RAIL -> Material.CYAN_STAINED_GLASS;
            case SEAT_NORTH, SEAT_SOUTH -> Material.POLISHED_DEEPSLATE_STAIRS;
            case SIGN -> Material.OAK_SIGN;
        };
        BlockData result = material.createBlockData();
        if (result instanceof Directional facing) facing.setFacing(kind == ArenaGeometry.Kind.SEAT_SOUTH ? BlockFace.SOUTH : BlockFace.NORTH);
        if (result instanceof Rotatable facing) facing.setRotation(BlockFace.NORTH);
        return result;
    }

    private void holdChunks() {
        for (int cx = (x + ArenaGeometry.MIN) >> 4; cx <= (x + ArenaGeometry.MAX) >> 4; cx++)
            for (int cz = (z + ArenaGeometry.MIN) >> 4; cz <= (z + ArenaGeometry.MAX) >> 4; cz++) {
                ChunkKey key = new ChunkKey(cx, cz);
                if (!ownedTickets.contains(key) && world.addPluginChunkTicket(cx, cz, plugin)) ownedTickets.add(key);
            }
    }
    private void releaseChunks() {
        if (world != null) for (ChunkKey key : ownedTickets) world.removePluginChunkTicket(key.x(), key.z(), plugin);
        ownedTickets.clear();
    }

    private void saveManifest(String status) throws IOException {
        Files.createDirectories(manifest.getParent());
        YamlConfiguration out = new YamlConfiguration();
        out.set("format", FORMAT); out.set("status", status); out.set("world-uuid", world.getUID().toString());
        out.set("world-name", world.getName()); out.set("x", x); out.set("y", y); out.set("z", z);
        Path temp = manifest.resolveSibling("sky-arena.yml.tmp");
        try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            ByteBuffer bytes = StandardCharsets.UTF_8.encode(out.saveToString());
            while (bytes.hasRemaining()) channel.write(bytes);
            channel.force(true);
        }
        for (int attempt = 0; ; attempt++) {
            try { Files.move(temp, manifest, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); return; }
            catch (AccessDeniedException busy) {
                if (attempt >= 4) throw busy;
                try { Thread.sleep(10L << attempt); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException("競技場位置檔儲存中斷", interrupted); }
            }
        }
    }
    private static int number(YamlConfiguration saved, String key) throws IOException {
        Object raw = saved.get(key);
        if (!(raw instanceof Number number) || number.doubleValue() != number.intValue()) throw new IOException("競技場位置檔欄位無效：" + key);
        return number.intValue();
    }
    private static String where(Block block) { return block.getX() + "," + block.getY() + "," + block.getZ() + " (" + block.getType() + ")"; }
    private Location location(ArenaGeometry.Point point) { requireReady(); return new Location(world, x + point.x(), y + point.y(), z + point.z()); }
    private boolean sameWorld(Location location) { return ready && location != null && world.equals(location.getWorld()); }
    private void requireReady() { if (!ready) throw new IllegalStateException("高空競技場尚未就緒"); }

    public boolean isReady() { return ready; }
    public World world() { requireReady(); return world; }
    public Location spawn() { return location(ArenaGeometry.fighter()); }
    public Location fighterSpawn() { return spawn(); }
    public Location spectatorSpawn() { return location(ArenaGeometry.spectator()); }
    public boolean containsFight(Location at) { return sameWorld(at) && ArenaGeometry.fight(at.getX() - x, at.getY() - y, at.getZ() - z); }
    public boolean isSpectator(Location at) { return sameWorld(at) && ArenaGeometry.spectator(at.getX() - x, at.getY() - y, at.getZ() - z); }
    public boolean containsStructure(Location at) { return sameWorld(at) && ArenaGeometry.structure(at.getX() - x, at.getY() - y, at.getZ() - z); }
    public List<Location> wardenSpawns() {
        requireReady();
        List<Location> result = new ArrayList<>();
        for (ArenaGeometry.Point point : ArenaGeometry.wardens()) result.add(location(point));
        return List.copyOf(result);
    }
    public Location returnSafeFallback() {
        World home = world != null ? world : Bukkit.getWorlds().stream().findFirst().orElseThrow();
        Location spawn = home.getSpawnLocation();
        int safeY = Math.max(home.getMinHeight() + 1, home.getHighestBlockYAt(spawn.getBlockX(), spawn.getBlockZ()) + 1);
        return new Location(home, spawn.getBlockX() + 0.5, safeY, spawn.getBlockZ() + 0.5, spawn.getYaw(), 0);
    }
    public String describe() { return ready ? "20×20 伏守者擂台／上層安全觀戰席（" + world.getName() + " " + x + "," + y + "," + z + "）" : "高空競技場尚未就緒"; }
    /** The physical structure is permanent; shutdown releases tickets only. */
    @Override public void close() { ready = false; releaseChunks(); }
}
