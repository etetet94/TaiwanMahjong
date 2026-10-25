package tw.minecraft.mahjong;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.SoundCategory;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/** Local, transient cosmetics. Invoke only after a real action or a first settled win is committed. */
public final class MahjongActionEffects implements Listener, AutoCloseable {
    static final NamespacedKey FIREWORK_KEY = Objects.requireNonNull(NamespacedKey.fromString("taiwanmahjong:cosmetic_firework"));
    static final int FIREWORKS_PER_WIN = 3;
    private static final Title.Times ACTION_TIMES = Title.Times.times(Duration.ofMillis(100), Duration.ofMillis(900), Duration.ofMillis(300));
    private static final Title.Times WIN_TIMES = Title.Times.times(Duration.ofMillis(150), Duration.ofMillis(2200), Duration.ofMillis(550));
    private final Settings settings;
    private final Delivery delivery;
    private final Logger logger;
    private boolean closed;

    public enum Action {
        CHI("吃！", "接得漂亮！", NamedTextColor.GREEN, "block.note_block.harp", 1.4f),
        PON("碰！", "好牌成一組！", NamedTextColor.AQUA, "block.note_block.xylophone", 1.1f),
        KAN("槓！", "再補一張好運！", NamedTextColor.LIGHT_PURPLE, "block.note_block.bell", 0.8f),
        WIN("胡！", "手氣旺旺！", NamedTextColor.GOLD, "entity.player.levelup", 1.2f),
        DEAL_IN("放槍！", "下把再來！", NamedTextColor.RED, "block.note_block.bass", 0.8f);

        final String title, subtitle, sound;
        final NamedTextColor color;
        final float pitch;
        Action(String title, String subtitle, NamedTextColor color, String sound, float pitch) {
            this.title = title; this.subtitle = subtitle; this.color = color; this.sound = sound; this.pitch = pitch;
        }
    }

    public MahjongActionEffects(JavaPlugin plugin) {
        this(Settings.from(plugin.getConfig()), new BukkitDelivery(plugin), plugin.getLogger());
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    MahjongActionEffects(Settings settings, Delivery delivery, Logger logger) {
        this.settings = Objects.requireNonNull(settings);
        this.delivery = Objects.requireNonNull(delivery);
        this.logger = Objects.requireNonNull(logger);
    }

    /** Seat holders receive the title even away from the table; other recipients must be nearby. */
    public void announceAction(Location table, String[] participantNames, String actor, Action action) {
        if (closed || !settings.enabled() || action == null) return;
        safely(() -> {
            for (Player viewer : delivery.audience(table, participantNames, settings.radius())) {
                show(viewer, table, action, action.title, clean(actor) + " · " + action.subtitle, ACTION_TIMES);
            }
        });
    }

    /** One invocation per settled winner, never on load/replay: exactly three cosmetic rockets. */
    public void announceWin(Location table, String[] participantNames, String winner, int points,
                            String unit, boolean selfDraw, String discarder) {
        if (closed || !settings.enabled() || !settings.wins()) return;
        safely(() -> {
            for (Player viewer : delivery.audience(table, participantNames, settings.radius())) {
                boolean dealtIn = !selfDraw && discarder != null && discarder.equalsIgnoreCase(viewer.getName());
                Action action = dealtIn ? Action.DEAL_IN : Action.WIN;
                String label = dealtIn ? action.title : selfDraw ? "自摸！" : action.title;
                String subtitle = clean(winner) + " · " + Math.max(0, points) + " " + MahjongCelebrations.unitLabel(unit)
                        + (dealtIn ? " · 下把再來！" : !selfDraw && discarder != null ? " · " + clean(discarder) + " 放槍" : " · 手氣旺旺！");
                show(viewer, table, action, label, subtitle, WIN_TIMES);
            }
        });
        if (settings.fireworks() && table != null && table.getWorld() != null) {
            safely(() -> delivery.fireworks(table, FIREWORKS_PER_WIN));
        }
    }

    private void show(Player viewer, Location table, Action action, String label, String subtitle, Title.Times times) {
        if (settings.titles()) safely(() -> delivery.title(viewer, Title.title(
                Component.text(label, action.color, TextDecoration.BOLD), Component.text(subtitle, NamedTextColor.WHITE), times)));
        if (settings.sounds()) safely(() -> delivery.sound(viewer, table, action));
    }

    /** Never exempt mobs: decorative rockets must not harm any entity, even at point blank range. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onFireworkDamage(EntityDamageByEntityEvent event) {
        if (owned(event.getDamager()) || owned(event.getDamageSource().getDirectEntity())) {
            event.setDamage(0);
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onFireworkExplosion(EntityExplodeEvent event) {
        if (owned(event.getEntity())) {
            event.blockList().clear();
            event.setYield(0);
            event.setCancelled(true);
        }
    }

    static boolean owned(Entity entity) {
        return entity instanceof Firework firework && firework.getPersistentDataContainer().has(FIREWORK_KEY, PersistentDataType.BYTE);
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        safely(delivery::close);
    }

    private void safely(Runnable operation) {
        try { operation.run(); }
        catch (RuntimeException failure) { logger.log(Level.WARNING, "麻將動作特效未能送出；不影響牌局與結算。", failure); }
    }

    private static String clean(String value) {
        if (value == null || value.isBlank()) return "玩家";
        StringBuilder result = new StringBuilder();
        value.codePoints().filter(c -> !Character.isISOControl(c)).limit(48).forEach(result::appendCodePoint);
        return result.isEmpty() ? "玩家" : result.toString();
    }

    record Settings(boolean enabled, boolean titles, boolean sounds, boolean fireworks, boolean wins, double radius) {
        Settings { radius = Double.isFinite(radius) ? Math.max(0, Math.min(32, radius)) : 16; }
        static Settings from(ConfigurationSection config) {
            return new Settings(config.getBoolean("celebrations.enabled", true), config.getBoolean("celebrations.action-titles", true),
                    config.getBoolean("celebrations.sounds", true), config.getBoolean("celebrations.win-fireworks", true),
                    config.getBoolean("celebrations.win-announcements", true), config.getDouble("celebrations.effect-radius", 16));
        }
    }

    interface Delivery {
        Collection<Player> audience(Location table, String[] names, double radius);
        void title(Player player, Title title);
        void sound(Player player, Location table, Action action);
        void fireworks(Location table, int count);
        void close();
    }

    static Collection<Player> selectAudience(Location table, Collection<Player> online, String[] names, double radius) {
        Set<String> participants = new LinkedHashSet<>();
        if (names != null) for (String name : names) if (name != null) participants.add(name.toLowerCase(java.util.Locale.ROOT));
        LinkedHashMap<UUID, Player> recipients = new LinkedHashMap<>();
        for (Player player : online) {
            if (participants.contains(player.getName().toLowerCase(java.util.Locale.ROOT))
                    || table != null && MahjongCelebrations.nearby(table, player.getLocation(), radius)) {
                recipients.put(player.getUniqueId(), player);
            }
        }
        return List.copyOf(recipients.values());
    }

    static final class BukkitDelivery implements Delivery {
        private final Plugin plugin;
        private final Set<Firework> fireworks = new LinkedHashSet<>();
        private final Set<BukkitTask> tasks = new LinkedHashSet<>();
        BukkitDelivery(Plugin plugin) { this.plugin = plugin; }

        @Override public Collection<Player> audience(Location table, String[] names, double radius) {
            return selectAudience(table, new ArrayList<>(plugin.getServer().getOnlinePlayers()), names, radius);
        }
        @Override public void title(Player player, Title title) { player.showTitle(title); }
        @Override public void sound(Player player, Location table, Action action) {
            Location source = table != null && table.getWorld() != null && table.getWorld().equals(player.getWorld()) ? table : player.getLocation();
            player.playSound(source, action.sound, SoundCategory.BLOCKS, 0.5f, action.pitch);
        }
        @Override public void fireworks(Location table, int count) {
            Color[] colors = {Color.YELLOW, Color.LIME, Color.AQUA};
            for (int i = 0; i < count; i++) {
                final Color color = colors[i % colors.length];
                Location launch = table.clone().add((i - 1) * 0.75, 2.0, 0);
                Firework rocket = table.getWorld().spawn(launch, Firework.class, entity -> {
                    // Apply protection before the entity joins the world and before any tick can explode it.
                    entity.getPersistentDataContainer().set(FIREWORK_KEY, PersistentDataType.BYTE, (byte) 1);
                    entity.setPersistent(false);
                    entity.setShotAtAngle(false);
                    FireworkMeta meta = entity.getFireworkMeta();
                    meta.clearEffects();
                    meta.addEffect(FireworkEffect.builder().with(FireworkEffect.Type.BALL).withColor(color)
                            .withFade(Color.WHITE).trail(true).flicker(true).build());
                    meta.setPower(0);
                    entity.setFireworkMeta(meta);
                });
                fireworks.add(rocket);
                try {
                    later(() -> { if (rocket.isValid()) rocket.detonate(); }, 12L + i * 5L);
                    later(() -> { rocket.remove(); fireworks.remove(rocket); }, 80L);
                } catch (RuntimeException failure) {
                    rocket.remove();
                    fireworks.remove(rocket);
                    throw failure;
                }
            }
        }

        private void later(Runnable action, long ticks) {
            BukkitTask[] scheduled = new BukkitTask[1];
            scheduled[0] = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                tasks.remove(scheduled[0]);
                action.run();
            }, ticks);
            tasks.add(scheduled[0]);
        }

        @Override public void close() {
            for (BukkitTask task : List.copyOf(tasks)) task.cancel();
            tasks.clear();
            for (Firework rocket : List.copyOf(fireworks)) rocket.remove();
            fireworks.clear();
        }
    }
}
