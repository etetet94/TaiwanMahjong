package tw.minecraft.mahjong;

import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Server;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import tw.minecraft.mahjong.engine.MahjongGame;

/** Cosmetic only. The caller persists first-announcement flags before invoking these methods. */
public final class MahjongCelebrations {
    private final Settings settings;
    private final Delivery delivery;
    private final Logger logger;

    public MahjongCelebrations(JavaPlugin plugin) {
        this(Settings.from(plugin.getConfig()), new BukkitDelivery(plugin.getServer()), plugin.getLogger());
    }

    MahjongCelebrations(Settings settings, Delivery delivery, Logger logger) {
        this.settings = Objects.requireNonNull(settings);
        this.delivery = Objects.requireNonNull(delivery);
        this.logger = Objects.requireNonNull(logger);
    }

    /** Announce only the declaration; this API intentionally accepts no hand or winning-tile data. */
    public void announceListening(Location table, String playerName, boolean practice) {
        announceListening(table, playerName, practice, false);
    }

    public void announceListening(Location table, String playerName, boolean practice, boolean japanese) {
        if (!settings.enabled() || !settings.listening()) return;
        safely(() -> delivery.broadcast(Component.text(listeningMessage(playerName, practice, japanese), NamedTextColor.GOLD)));
        effects(table, false);
    }

    /** A finished game emits one short message per winner and at most one set of local effects. */
    public void announceFinished(Location table, String[] names, MahjongGame.Outcome outcome, boolean practice) {
        announceFinished(table, names, outcome, practice, "台");
    }

    public void announceFinished(Location table, String[] names, MahjongGame.Outcome outcome, boolean practice, String unit) {
        if (!settings.enabled() || outcome == null) return;
        if (outcome.draw()) {
            if (settings.draws()) safely(() -> delivery.broadcast(Component.text(drawMessage(practice), NamedTextColor.YELLOW)));
            return;
        }
        if (!settings.wins()) return;
        boolean announced = false;
        for (var winner : outcome.winners()) {
            String name = names != null && winner.seat() >= 0 && winner.seat() < names.length ? names[winner.seat()] : "玩家";
            safely(() -> delivery.broadcast(Component.text(winMessage(name, winner.score().tai(), outcome.selfDraw(), practice, unit), NamedTextColor.GOLD)));
            announced = true;
        }
        if (announced) effects(table, true);
    }

    public void announceWin(Location table, String winnerName, int tai, boolean selfDraw, boolean practice) {
        announceWin(table, winnerName, tai, selfDraw, practice, "台");
    }

    public void announceWin(Location table, String winnerName, int tai, boolean selfDraw, boolean practice, String unit) {
        if (!settings.enabled() || !settings.wins()) return;
        safely(() -> delivery.broadcast(Component.text(winMessage(winnerName, tai, selfDraw, practice, unit), NamedTextColor.GOLD)));
        effects(table, true);
    }

    private void effects(Location table, boolean win) {
        if (table != null && (settings.sounds() || settings.particles())) {
            safely(() -> delivery.effects(table, win, settings));
        }
    }

    private void safely(Runnable action) {
        try { action.run(); }
        catch (RuntimeException failure) {
            logger.log(Level.WARNING, "麻將喜報或特效未能送出；不影響牌局與結算。", failure);
        }
    }

    static String listeningMessage(String playerName, boolean practice) {
        return listeningMessage(playerName, practice, false);
    }

    static String listeningMessage(String playerName, boolean practice, boolean japanese) {
        return prefix(practice) + name(playerName) + (japanese ? " 宣告立直！好牌就差臨門一腳！" : " 宣告聽牌！好牌就差臨門一腳！");
    }

    static String winMessage(String winnerName, int tai, boolean selfDraw, boolean practice) {
        return winMessage(winnerName, tai, selfDraw, practice, "台");
    }

    static String winMessage(String winnerName, int tai, boolean selfDraw, boolean practice, String unit) {
        return prefix(practice) + name(winnerName) + (selfDraw ? " 自摸！" : " 胡牌！")
                + "牌型 " + Math.max(0, tai) + " " + unitLabel(unit) + "，手氣旺旺！";
    }

    static String unitLabel(String unit) { return "飜".equals(unit) ? "飜" : "番".equals(unit) ? "番" : "台"; }

    static String drawMessage(boolean practice) {
        return "【麻將牌桌】" + (practice ? "【練習局】" : "") + " 本局流局，下回好運再來！";
    }

    private static String prefix(boolean practice) { return "【麻將喜報】" + (practice ? "【練習局】" : "") + " "; }

    private static String name(String value) {
        if (value == null || value.isBlank()) return "玩家";
        StringBuilder clean = new StringBuilder();
        value.codePoints().filter(c -> !Character.isISOControl(c)).limit(48).forEach(clean::appendCodePoint);
        return clean.isEmpty() ? "玩家" : clean.toString();
    }

    record Settings(boolean enabled, boolean listening, boolean wins, boolean draws, boolean sounds, boolean particles, double radius) {
        Settings {
            radius = Double.isFinite(radius) ? Math.max(0, Math.min(32, radius)) : 16;
        }
        static Settings from(ConfigurationSection config) {
            return new Settings(config.getBoolean("celebrations.enabled", true),
                    config.getBoolean("celebrations.listening-announcements", true),
                    config.getBoolean("celebrations.win-announcements", true),
                    config.getBoolean("celebrations.draw-announcements", true),
                    config.getBoolean("celebrations.sounds", true),
                    config.getBoolean("celebrations.particles", true),
                    config.getDouble("celebrations.effect-radius", 16));
        }
    }

    interface Delivery {
        void broadcast(Component message);
        void effects(Location table, boolean win, Settings settings);
    }

    private record BukkitDelivery(Server server) implements Delivery {
        @Override public void broadcast(Component message) { server.broadcast(message); }

        @Override public void effects(Location table, boolean win, Settings settings) {
            if (table.getWorld() == null || settings.radius() <= 0) return;
            Location sparkle = table.clone().add(0, 1.5, 0);
            for (Player player : table.getWorld().getPlayers()) {
                if (!nearby(table, player.getLocation(), settings.radius())) continue;
                if (settings.sounds()) player.playSound(table,
                        win ? Sound.ENTITY_PLAYER_LEVELUP : Sound.BLOCK_NOTE_BLOCK_PLING,
                        SoundCategory.BLOCKS, win ? 0.45f : 0.35f, win ? 1.2f : 1.5f);
                // Per-player packets, no firework entities, damage, blocks, or lasting world state.
                if (settings.particles()) player.spawnParticle(win ? Particle.FIREWORK : Particle.HAPPY_VILLAGER,
                        sparkle, win ? 12 : 5, 0.65, 0.3, 0.65, 0.015);
            }
        }
    }

    static boolean nearby(Location table, Location viewer, double radius) {
        return table.getWorld() != null && table.getWorld().equals(viewer.getWorld())
                && radius > 0 && table.distanceSquared(viewer) <= radius * radius;
    }
}
