package tw.minecraft.mahjong.view;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.joml.Matrix4f;
import tw.minecraft.mahjong.engine.Meld;

/**
 * A real three-block-wide display table. It changes no world blocks and needs no
 * client mod. Tile art uses the automatic resource pack; concealed faces stay private.
 * All entry points must be called on the server thread.
 */
public final class TableView implements Listener, AutoCloseable {
    @FunctionalInterface
    public interface Callbacks {
        void onInteract(Player player, String action, int value);
    }

    public record SeatState(UUID owner, String playerName, List<String> hand,
            List<List<String>> melds, List<String> discards, List<String> flowers,
            List<Meld> actualMelds, int riichiDiscardIndex) {
        public SeatState(UUID owner, String playerName, List<String> hand,
                         List<List<String>> melds, List<String> discards, List<String> flowers) {
            this(owner, playerName, hand, melds, discards, flowers, List.of(), -1);
        }
        public SeatState(UUID owner, String playerName, List<String> hand,
                         List<List<String>> melds, List<String> discards, List<String> flowers, List<Meld> actualMelds) {
            this(owner, playerName, hand, melds, discards, flowers, actualMelds, -1);
        }
        public SeatState {
            playerName = Objects.requireNonNullElse(playerName, "空位");
            hand = List.copyOf(hand);
            melds = melds.stream().map(List::copyOf).toList();
            discards = List.copyOf(discards);
            flowers = List.copyOf(flowers);
            actualMelds = List.copyOf(actualMelds);
            if (riichiDiscardIndex < -1) throw new IllegalArgumentException("Invalid riichi discard index");
            if (hand.size() > 17) throw new IllegalArgumentException("Hand has too many tiles");
        }
        public static SeatState empty() {
            return new SeatState(null, "空位", List.of(), List.of(), List.of(), List.of());
        }
    }

    public record ViewState(List<SeatState> seats, int turnSeat, int dealerSeat,
            int remainingTiles, String status) {
        public ViewState {
            seats = List.copyOf(seats);
            if (seats.size() != 4) throw new IllegalArgumentException("Exactly four seats required");
            status = Objects.requireNonNullElse(status, "等待玩家");
        }
    }

    private record Binding(String action, int value, UUID owner) {}
    private record Group(Object signature, List<Entity> entities) {}
    private record HandSignature(UUID owner, List<String> tiles) {}
    private record SeatSignature(UUID owner, String name, boolean turn, int dealerSeat) {}
    private record MeldSignature(List<List<String>> labels, List<Meld> melds) {}
    private record RiverSignature(List<String> labels, int riichiDiscardIndex) {}
    private record ChunkKey(UUID world, int x, int z) {}
    private static final Map<ChunkKey, Integer> CHUNK_USERS = new HashMap<>();

    private final JavaPlugin plugin;
    private final String tableId;
    private final Location center;
    private final Callbacks callbacks;
    private final NamespacedKey tableKey;
    private final Map<UUID, Entity> entities = new HashMap<>();
    private final Map<UUID, Binding> bindings = new HashMap<>();
    private final Map<UUID, UUID> privateOwners = new HashMap<>();
    private final Map<String, Group> groups = new HashMap<>();
    private final Map<UUID, Long> lastClick = new HashMap<>();
    private final List<ChunkKey> chunkTickets = new ArrayList<>();
    private final TextDisplay status;
    private boolean closed;

    public TableView(JavaPlugin plugin, String tableId, Location center, Callbacks callbacks) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Use the server thread");
        this.plugin = Objects.requireNonNull(plugin);
        this.tableId = Objects.requireNonNull(tableId);
        this.center = Objects.requireNonNull(center).clone();
        this.center.setYaw(0);
        this.center.setPitch(0);
        Objects.requireNonNull(this.center.getWorld(), "Table world");
        this.callbacks = Objects.requireNonNull(callbacks);
        this.tableKey = new NamespacedKey(plugin, "mahjong_table");
        holdChunks();
        List<Entity> base = new ArrayList<>();
        block(base, location(0, 0.74, 0), Material.DARK_OAK_PLANKS, 3, 0.12, 3, 0);
        block(base, location(0, 0.861, 0), Material.GREEN_CONCRETE, 2.84, 0.025, 2.84, 0);
        for (double x : new double[]{-1.26, 1.26}) {
            for (double z : new double[]{-1.26, 1.26}) {
                block(base, location(x, 0, z), Material.DARK_OAK_LOG, 0.22, 0.74, 0.22, 0);
            }
        }
        status = text(base, location(0, 1.53, 0), "麻將牌桌", NamedTextColor.GOLD,
                0.32F, 0, false, null, true);
        interaction(base, location(0, 0.89, 0), 0.25F, 0.4F, "actions", -1, null);
        groups.put("base", new Group("base", base));
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    /** Clean up marked, loaded entities left by an earlier enable/reload. */
    public static void cleanupOwnedEntities(JavaPlugin plugin) {
        NamespacedKey key = new NamespacedKey(plugin, "mahjong_table");
        Bukkit.getWorlds().forEach(world -> world.getEntities().stream()
                .filter(entity -> entity.getPersistentDataContainer().has(key, PersistentDataType.STRING))
                .forEach(Entity::remove));
    }

    public Location center() { return center.clone(); }
    public String tableId() { return tableId; }
    public int entityCount() { return entities.size(); }

    private void holdChunks() {
        int minX = ((int) Math.floor(center.getX() - 1.6)) >> 4;
        int maxX = ((int) Math.floor(center.getX() + 1.6)) >> 4;
        int minZ = ((int) Math.floor(center.getZ() - 1.6)) >> 4;
        int maxZ = ((int) Math.floor(center.getZ() + 1.6)) >> 4;
        for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++) {
            ChunkKey key = new ChunkKey(center.getWorld().getUID(), x, z);
            int previous = CHUNK_USERS.getOrDefault(key, 0);
            if (previous == 0) center.getWorld().addPluginChunkTicket(x, z, plugin);
            CHUNK_USERS.put(key, previous + 1);
            chunkTickets.add(key);
        }
    }

    private void releaseChunks() {
        for (ChunkKey key : chunkTickets) {
            int remaining = CHUNK_USERS.getOrDefault(key, 1) - 1;
            if (remaining <= 0) {
                CHUNK_USERS.remove(key);
                center.getWorld().removePluginChunkTicket(key.x(), key.z(), plugin);
            } else CHUNK_USERS.put(key, remaining);
        }
        chunkTickets.clear();
    }

    public void render(ViewState state) {
        if (closed) return;
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Use the server thread");
        status.text(Component.text("麻將牌桌  ·  " + tableId, NamedTextColor.GOLD)
                .append(Component.newline()).append(Component.text(state.status(), NamedTextColor.WHITE))
                .append(Component.newline()).append(Component.text("牌山 " + state.remainingTiles()
                        + " 張  ·  右鍵座位／手牌操作", NamedTextColor.GRAY)));
        for (int s = 0; s < 4; s++) {
            final int seat = s;
            SeatState player = state.seats().get(seat);
            replace("seat-" + seat, new SeatSignature(player.owner(), player.playerName(),
                    seat == state.turnSeat(), state.dealerSeat()), list -> {
                TableGeometry.Point p = TableGeometry.rotate(seat, 0, 1.43);
                String label = seatWind(Math.floorMod(seat - state.dealerSeat(), 4))
                        + (seat == state.dealerSeat() ? "【莊】" : "")
                        + " " + player.playerName();
                if (seat == state.turnSeat() && player.owner() != null) label = "▶ " + label;
                String action = player.owner() == null ? "join" : "actions";
                text(list, location(p.x(), 1.27, p.z()), label + "\n右鍵"
                        + (player.owner() == null ? "入座" : "操作"),
                        seat == state.turnSeat() ? NamedTextColor.YELLOW : NamedTextColor.AQUA,
                        0.31F, TableGeometry.yaw(seat), false, null, false);
                interaction(list, location(p.x(), 1.2, p.z()), 0.5F, 0.36F, action, seat, null);
            });
            replace("hand-" + seat, new HandSignature(player.owner(), player.hand()), list -> {
                for (int index = 0; index < player.hand().size(); index++) {
                    TableGeometry.Point p = TableGeometry.hand(seat, index, player.hand().size());
                    standingTile(list, seat, p, player.hand().get(index), player.owner());
                    interaction(list, location(p.x(), 0.89, p.z()), 0.135F, 0.29F,
                            "hand", index, player.owner());
                }
            });
            replace("melds-" + seat, new MeldSignature(player.melds(), player.actualMelds()), list -> {
                List<Integer> sizes = player.melds().stream().map(List::size).toList();
                List<Integer> called = new ArrayList<>();
                for (int group = 0; group < sizes.size(); group++) {
                    Meld meld = group < player.actualMelds().size() ? player.actualMelds().get(group) : null;
                    called.add(meld != null && meld.open() ? TableGeometry.calledTileIndex(seat, meld.fromSeat(), sizes.get(group)) : -1);
                }
                List<TableGeometry.MeldSlot> positions = TableGeometry.meldLayout(seat, sizes, called);
                int index = 0;
                for (int group = 0; group < sizes.size(); group++) {
                    Meld meld = group < player.actualMelds().size() ? player.actualMelds().get(group) : null;
                    for (String tile : displayedMeld(player.melds().get(group), meld, called.get(group))) {
                        TableGeometry.MeldSlot slot = positions.get(index++);
                        flatTile(list, seat, slot.center(), tile, slot.width(), slot.depth(), 0.24F, slot.sideways());
                    }
                }
            });
            replace("flowers-" + seat, player.flowers(), list -> {
                for (int index = 0; index < player.flowers().size(); index++) {
                    TableGeometry.Point p = TableGeometry.rotate(seat,
                            (index - (player.flowers().size() - 1) / 2.0) * 0.11, 0.82);
                    flatTile(list, seat, p, player.flowers().get(index), 0.095, 0.12, 0.22F);
                }
            });
            replace("discards-" + seat, new RiverSignature(player.discards(), player.riichiDiscardIndex()), list -> {
                List<String> river = player.discards();
                int start = Math.max(0, river.size() - TableGeometry.MAX_VISIBLE_DISCARDS);
                for (int index = start; index < river.size(); index++) {
                    flatTile(list, seat, TableGeometry.discard(seat, index - start),
                            river.get(index), 0.095, 0.105, 0.2F, index == player.riichiDiscardIndex());
                }
            });
        }
    }

    private void standingTile(List<Entity> list, int seat, TableGeometry.Point p, String label, UUID owner) {
        block(list, location(p.x(), 0.89, p.z()), Material.SMOOTH_QUARTZ,
                TableGeometry.TILE_WIDTH, TableGeometry.TILE_HEIGHT, TableGeometry.TILE_DEPTH,
                TableGeometry.yaw(seat));
        // The back faces the centre. Faces are separate private entities, never public metadata.
        TableGeometry.Point inward = TableGeometry.rotate(seat, 0, -0.047);
        block(list, location(p.x() + inward.x(), 0.904, p.z() + inward.z()),
                Material.GREEN_CONCRETE, 0.105, 0.222, 0.006, TableGeometry.yaw(seat));
        if (owner != null) {
            TableGeometry.Point outward = TableGeometry.rotate(seat, 0, 0.048);
            text(list, location(p.x() + outward.x(), 1.0085, p.z() + outward.z()),
                    TileArt.face(label), 0.26F, TableGeometry.yaw(seat), false, owner, false);
        }
    }

    private void flatTile(List<Entity> list, int seat, TableGeometry.Point p, String label,
            double width, double depth, float textScale) {
        flatTile(list, seat, p, label, width, depth, textScale, false);
    }

    private void flatTile(List<Entity> list, int seat, TableGeometry.Point p, String label,
            double width, double depth, float textScale, boolean sideways) {
        boolean hidden = label == null || label.isBlank() || label.equals("?");
        float yaw = TableGeometry.yaw(seat) + (sideways ? 90 : 0);
        block(list, location(p.x(), 0.89, p.z()), hidden ? Material.GREEN_CONCRETE : Material.SMOOTH_QUARTZ,
                width, 0.045, depth, yaw);
        if (!hidden) text(list, location(p.x(), 0.938, p.z()), TileArt.face(label),
                (float) (0.9 * Math.min(width / 0.4, depth / 0.8)),
                yaw, true, null, false);
    }

    /** Move the actual claimed tile to its source marker without changing the meld contents. */
    static List<String> displayedMeld(List<String> labels, Meld meld, int calledIndex) {
        if (meld == null || !meld.open() || meld.calledTile() == null || calledIndex < 0 || calledIndex >= labels.size()) return labels;
        int original = meld.tiles().indexOf(meld.calledTile());
        if (original < 0 || original >= labels.size()) return labels;
        List<String> ordered = new ArrayList<>(labels);
        String called = ordered.remove(original);
        ordered.add(calledIndex, called);
        return List.copyOf(ordered);
    }

    private void replace(String name, Object signature, Consumer<List<Entity>> renderer) {
        Group previous = groups.get(name);
        if (previous != null && previous.signature().equals(signature)
                && previous.entities().stream().allMatch(Entity::isValid)) return;
        if (previous != null) previous.entities().forEach(this::remove);
        List<Entity> replacement = new ArrayList<>();
        try {
            renderer.accept(replacement);
            groups.put(name, new Group(signature, replacement));
        } catch (RuntimeException exception) {
            replacement.forEach(this::remove);
            groups.remove(name);
            throw exception;
        }
    }

    private BlockDisplay block(List<Entity> group, Location at, Material material,
            double width, double height, double depth, float yaw) {
        BlockDisplay display = center.getWorld().spawn(at, BlockDisplay.class, entity -> {
            setup(entity);
            entity.setBlock(material.createBlockData());
            // Bukkit yaw is clockwise, while the matrix convention is counterclockwise.
            entity.setTransformationMatrix(new Matrix4f().rotateY((float) Math.toRadians(-yaw))
                    .translate((float) (-width / 2), 0, (float) (-depth / 2))
                    .scale((float) width, (float) height, (float) depth));
        });
        add(group, display);
        return display;
    }

    private TextDisplay text(List<Entity> group, Location at, String content, TextColor color,
            float scale, float yaw, boolean flat, UUID owner, boolean billboard) {
        return text(group, at, Component.text(content, color), scale, yaw, flat, owner, billboard);
    }

    private TextDisplay text(List<Entity> group, Location at, Component content,
            float scale, float yaw, boolean flat, UUID owner, boolean billboard) {
        at.setYaw(yaw);
        at.setPitch(flat ? -90 : 0);
        TextDisplay display = center.getWorld().spawn(at, TextDisplay.class, entity -> {
            setup(entity);
            if (owner != null) entity.setVisibleByDefault(false);
            entity.text(content);
            entity.setAlignment(TextDisplay.TextAlignment.CENTER);
            entity.setLineWidth(500);
            entity.setShadowed(false);
            entity.setSeeThrough(false);
            entity.setDefaultBackground(false);
            entity.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            entity.setBillboard(billboard ? Display.Billboard.CENTER : Display.Billboard.FIXED);
            entity.setTransformationMatrix(new Matrix4f().scale(scale));
        });
        add(group, display);
        if (owner != null) {
            privateOwners.put(display.getUniqueId(), owner);
            Player player = Bukkit.getPlayer(owner);
            if (player != null) player.showEntity(plugin, display);
        }
        return display;
    }

    private void interaction(List<Entity> group, Location at, float width, float height,
            String action, int value, UUID owner) {
        Interaction interaction = center.getWorld().spawn(at, Interaction.class, entity -> {
            setup(entity);
            entity.setInteractionWidth(width);
            entity.setInteractionHeight(height);
            entity.setResponsive(true);
        });
        add(group, interaction);
        bindings.put(interaction.getUniqueId(), new Binding(action, value, owner));
    }

    private void setup(Entity entity) {
        entity.setPersistent(false);
        entity.setInvulnerable(true);
        entity.setGravity(false);
        entity.setSilent(true);
        entity.getPersistentDataContainer().set(tableKey, PersistentDataType.STRING, tableId);
        entity.addScoreboardTag("twmahjong_display");
        if (entity instanceof Display display) {
            display.setViewRange(0.55F);
            display.setBrightness(new Display.Brightness(15, 15));
            display.setShadowRadius(0);
            display.setShadowStrength(0);
            display.setInterpolationDuration(0);
            display.setDisplayWidth(4);
            display.setDisplayHeight(3);
        }
    }

    private void add(List<Entity> group, Entity entity) {
        group.add(entity);
        entities.put(entity.getUniqueId(), entity);
    }

    private void remove(Entity entity) {
        UUID id = entity.getUniqueId();
        entity.remove();
        entities.remove(id);
        bindings.remove(id);
        privateOwners.remove(id);
    }

    private Location location(double x, double y, double z) { return center.clone().add(x, y, z); }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void interactAt(PlayerInteractAtEntityEvent event) { handle(event); }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void interact(PlayerInteractEntityEvent event) {
        if (!(event instanceof PlayerInteractAtEntityEvent)) handle(event);
    }

    private void handle(PlayerInteractEntityEvent event) {
        Binding binding = bindings.get(event.getRightClicked().getUniqueId());
        if (binding == null || closed) return;
        event.setCancelled(true);
        if (event.getHand() != EquipmentSlot.HAND) return;
        Player player = event.getPlayer();
        if (!player.getWorld().equals(center.getWorld())
                || player.getLocation().distanceSquared(center) > 36) return;
        if (binding.owner() != null && !binding.owner().equals(player.getUniqueId())) {
            player.sendActionBar(Component.text("這是其他玩家的手牌；右鍵自己的座位操作。", NamedTextColor.YELLOW));
            return;
        }
        long now = System.nanoTime();
        Long previous = lastClick.get(player.getUniqueId());
        if (previous != null && now - previous < 150_000_000L) return;
        lastClick.put(player.getUniqueId(), now);
        callbacks.onInteract(player, binding.action(), binding.value());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void damage(EntityDamageEvent event) {
        if (entities.containsKey(event.getEntity().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler
    public void join(PlayerJoinEvent event) { revealLater(event.getPlayer()); }

    @EventHandler
    public void worldChanged(PlayerChangedWorldEvent event) { revealLater(event.getPlayer()); }

    /** Also safe to call when a root controller refreshes a reconnecting player's GUI. */
    public void refreshVisibility(Player player) {
        if (closed || !player.isOnline()) return;
        privateOwners.forEach((id, owner) -> {
            Entity entity = entities.get(id);
            if (entity != null && entity.isValid() && owner.equals(player.getUniqueId())) {
                player.showEntity(plugin, entity);
            }
        });
    }

    private void revealLater(Player player) {
        plugin.getServer().getScheduler().runTask(plugin, () -> refreshVisibility(player));
    }

    private static String seatWind(int seat) {
        return switch (seat) { case 0 -> "東"; case 1 -> "南"; case 2 -> "西"; default -> "北"; };
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        HandlerList.unregisterAll(this);
        new ArrayList<>(entities.values()).forEach(this::remove);
        releaseChunks();
        groups.clear();
        lastClick.clear();
    }
}
