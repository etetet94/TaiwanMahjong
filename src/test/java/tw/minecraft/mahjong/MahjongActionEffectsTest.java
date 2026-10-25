package tw.minecraft.mahjong;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.ExplosionResult;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Firework;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.inventory.meta.FireworkMeta;
import org.junit.jupiter.api.Test;

class MahjongActionEffectsTest {
    @Test void localAudienceIncludesSeatsAndNearbyViewersWithoutGlobalTitles() {
        World world = world(), other = world();
        Location table = new Location(world, 0, 64, 0);
        Player seat = player("Seat", new Location(other, 99, 64, 0));
        Player near = player("Viewer", new Location(world, 16, 64, 0));
        Player far = player("Far", new Location(world, 16.01, 64, 0));
        Player elsewhere = player("Elsewhere", new Location(other, 0, 64, 0));
        assertEquals(List.of(seat, near), MahjongActionEffects.selectAudience(table,
                List.of(seat, near, far, elsewhere, near), new String[]{"seat", "Bot", null}, 16));
        assertEquals(List.of(seat), MahjongActionEffects.selectAudience(table, List.of(seat, near), new String[]{"Seat"}, 0));
        assertTrue(MahjongActionEffects.selectAudience(null, List.of(near), null, 16).isEmpty());
    }

    @Test void acceptedActionsUseLargeChineseTitlesWithoutLaunchingWinFireworks() {
        FakeDelivery delivery = new FakeDelivery();
        Player viewer = player("Viewer", table()); delivery.viewers.add(viewer);
        var effects = helper(defaults(), delivery);
        var actions = List.of(MahjongActionEffects.Action.CHI, MahjongActionEffects.Action.PON, MahjongActionEffects.Action.KAN);
        var expected = List.of("吃！", "碰！", "槓！");
        for (int i = 0; i < actions.size(); i++) {
            effects.announceAction(table(), new String[]{"Actor"}, "Actor", actions.get(i));
            assertEquals(expected.get(i), plain(delivery.titles.get("Viewer").title()));
            assertTrue(plain(delivery.titles.get("Viewer").subtitle()).contains("Actor"));
        }
        assertEquals(0, delivery.rockets); assertEquals(3, delivery.sounds);
    }

    @Test void eachWinnerGetsThreeRocketsAndDiscarderSeesDealInTitle() {
        FakeDelivery delivery = new FakeDelivery();
        delivery.viewers.add(player("Winner", table())); delivery.viewers.add(player("Loser", table()));
        delivery.viewers.add(player("Viewer", table()));
        var effects = helper(defaults(), delivery);
        effects.announceWin(table(), new String[]{"Winner", "Loser"}, "Winner", 4, "飜", false, "Loser");
        assertEquals(3, delivery.rockets);
        assertEquals("胡！", plain(delivery.titles.get("Winner").title()));
        assertEquals("胡！", plain(delivery.titles.get("Viewer").title()));
        assertEquals("放槍！", plain(delivery.titles.get("Loser").title()));
        assertTrue(plain(delivery.titles.get("Winner").subtitle()).contains("4 飜"));
        effects.announceWin(table(), null, "Winner", 6, "台", true, null);
        assertEquals(6, delivery.rockets);
        assertEquals("自摸！", plain(delivery.titles.get("Loser").title()));
        assertTrue(plain(delivery.titles.get("Winner").subtitle()).contains("6 台"));
    }

    @Test void ownedFireworksCannotDamagePlayersOrMobsButOrdinaryRocketsAreUnchanged() throws Exception {
        var effects = helper(defaults(), new FakeDelivery());
        for (Entity victim : List.of(player("Player", table()), entity(LivingEntity.class, false))) {
            var owned = damage(entity(Firework.class, true), victim, null);
            effects.onFireworkDamage(owned);
            assertTrue(owned.isCancelled()); assertEquals(0, owned.getDamage());
            var normal = damage(entity(Firework.class, false), victim, null);
            effects.onFireworkDamage(normal);
            assertFalse(normal.isCancelled()); assertEquals(9, normal.getDamage());
        }
        var indirect = damage(entity(Entity.class, false), entity(LivingEntity.class, false), entity(Firework.class, true));
        effects.onFireworkDamage(indirect); assertTrue(indirect.isCancelled());
        var annotation = MahjongActionEffects.class.getMethod("onFireworkDamage", EntityDamageByEntityEvent.class).getAnnotation(EventHandler.class);
        assertEquals(EventPriority.HIGHEST, annotation.priority()); assertFalse(annotation.ignoreCancelled());
    }

    @Test void ownedExplosionCannotAlterBlocks() {
        var effects = helper(defaults(), new FakeDelivery());
        var blocks = new ArrayList<org.bukkit.block.Block>(); blocks.add(null);
        var owned = new EntityExplodeEvent(entity(Firework.class, true), table(), blocks, 1, ExplosionResult.DESTROY);
        effects.onFireworkExplosion(owned);
        assertTrue(owned.isCancelled()); assertTrue(owned.blockList().isEmpty()); assertEquals(0, owned.getYield());
        var normal = new EntityExplodeEvent(entity(Firework.class, false), table(), new ArrayList<>(), 1, ExplosionResult.DESTROY);
        effects.onFireworkExplosion(normal); assertFalse(normal.isCancelled()); assertEquals(1, normal.getYield());
    }

    @Test void switchesAndShutdownPreventEffectsAndCosmeticFailuresDoNotBreakSettlement() {
        FakeDelivery delivery = new FakeDelivery(); delivery.viewers.add(player("Player", table()));
        var disabled = helper(new MahjongActionEffects.Settings(false, true, true, true, true, 16), delivery);
        disabled.announceAction(table(), null, "Player", MahjongActionEffects.Action.CHI);
        disabled.announceWin(table(), null, "Player", 2, "台", true, null);
        assertEquals(0, delivery.rockets); assertEquals(0, delivery.sounds); assertTrue(delivery.titles.isEmpty());
        var enabled = helper(defaults(), delivery);
        enabled.close(); enabled.close();
        enabled.announceWin(table(), null, "Player", 2, "台", true, null);
        assertEquals(1, delivery.closes); assertEquals(0, delivery.rockets);
        var silent = helper(new MahjongActionEffects.Settings(true, false, false, false, true, 16), delivery);
        silent.announceWin(table(), null, "Player", 2, "台", true, null);
        assertTrue(delivery.titles.isEmpty()); assertEquals(0, delivery.rockets); assertEquals(0, delivery.sounds);
        delivery.failTitle = true;
        assertDoesNotThrow(() -> helper(defaults(), delivery).announceWin(table(), null, "Player", 2, "台", true, null));
        assertEquals(3, delivery.rockets);
    }

    @Test void defaultsEnableRequestedEffectsWithBoundedRadius() {
        var settings = defaults();
        assertTrue(settings.enabled()); assertTrue(settings.titles()); assertTrue(settings.fireworks()); assertEquals(16, settings.radius());
        var config = new MemoryConfiguration(); config.set("celebrations.effect-radius", 100); config.set("celebrations.win-fireworks", false);
        assertEquals(32, MahjongActionEffects.Settings.from(config).radius()); assertFalse(MahjongActionEffects.Settings.from(config).fireworks());
    }

    @Test void nativeDeliverySpawnsExactlyThreeTaggedEntitiesAndAlwaysCleansThemUp() {
        NativeHarness nativeEffects = new NativeHarness();
        var delivery = new MahjongActionEffects.BukkitDelivery(nativeEffects.plugin);
        delivery.fireworks(new Location(nativeEffects.world, 0, 64, 0), MahjongActionEffects.FIREWORKS_PER_WIN);
        assertEquals(3, nativeEffects.rockets.size());
        for (Rocket rocket : nativeEffects.rockets) {
            assertTrue(rocket.tagged); assertFalse(rocket.persistent); assertEquals(1, rocket.visualEffects);
            assertTrue(MahjongActionEffects.owned(rocket.entity));
        }
        assertEquals(List.of(12L, 80L, 17L, 80L, 22L, 80L), nativeEffects.tasks.stream().map(t -> t.ticks).toList());
        nativeEffects.tasks.stream().filter(t -> t.ticks < 80).toList().forEach(t -> t.run.run());
        assertEquals(3, nativeEffects.rockets.stream().filter(r -> r.detonated).count());
        nativeEffects.tasks.stream().filter(t -> t.ticks == 80).toList().forEach(t -> t.run.run());
        assertEquals(3, nativeEffects.rockets.stream().filter(r -> r.removed).count());
        delivery.close();
        assertEquals(0, nativeEffects.tasks.stream().filter(t -> t.cancelled).count(), "completed tasks are removed from tracking");

        NativeHarness shutdown = new NativeHarness();
        var shutdownDelivery = new MahjongActionEffects.BukkitDelivery(shutdown.plugin);
        shutdownDelivery.fireworks(new Location(shutdown.world, 0, 64, 0), 3);
        shutdownDelivery.close();
        assertEquals(3, shutdown.rockets.stream().filter(r -> r.removed).count());
        assertEquals(6, shutdown.tasks.stream().filter(t -> t.cancelled).count());
    }

    private static MahjongActionEffects.Settings defaults() { return MahjongActionEffects.Settings.from(new MemoryConfiguration()); }
    private static MahjongActionEffects helper(MahjongActionEffects.Settings settings, FakeDelivery delivery) {
        Logger logger = Logger.getAnonymousLogger(); logger.setLevel(Level.OFF);
        return new MahjongActionEffects(settings, delivery, logger);
    }
    private static String plain(net.kyori.adventure.text.Component text) { return PlainTextComponentSerializer.plainText().serialize(text); }
    private static Location table() { return new Location(world(), 0, 64, 0); }
    private static World world() {
        UUID id = UUID.randomUUID();
        return (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[]{World.class}, (proxy, method, args) -> switch (method.getName()) {
            case "equals" -> proxy == args[0]; case "hashCode" -> id.hashCode(); case "getUID" -> id; default -> null;
        });
    }
    private static Player player(String name, Location location) {
        UUID id = UUID.randomUUID();
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class}, (proxy, method, args) -> switch (method.getName()) {
            case "getName" -> name; case "getUniqueId" -> id; case "getLocation" -> location; case "getWorld" -> location.getWorld();
            case "equals" -> proxy == args[0]; case "hashCode" -> id.hashCode(); default -> null;
        });
    }
    private static <T extends Entity> T entity(Class<T> type, boolean tagged) {
        PersistentDataContainer data = (PersistentDataContainer) Proxy.newProxyInstance(PersistentDataContainer.class.getClassLoader(),
                new Class<?>[]{PersistentDataContainer.class}, (proxy, method, args) -> method.getName().equals("has")
                        && tagged && args[0].equals(MahjongActionEffects.FIREWORK_KEY));
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> switch (method.getName()) {
            case "getPersistentDataContainer" -> data; case "equals" -> proxy == args[0]; case "hashCode" -> System.identityHashCode(proxy); default -> null;
        }));
    }
    private static EntityDamageByEntityEvent damage(Entity damager, Entity victim, Entity direct) {
        DamageSource source = (DamageSource) Proxy.newProxyInstance(DamageSource.class.getClassLoader(), new Class<?>[]{DamageSource.class},
                (proxy, method, args) -> method.getName().equals("getDirectEntity") ? direct : null);
        return new EntityDamageByEntityEvent(damager, victim, DamageCause.PROJECTILE, source, 9);
    }
    private static final class FakeDelivery implements MahjongActionEffects.Delivery {
        final List<Player> viewers = new ArrayList<>();
        final Map<String, Title> titles = new LinkedHashMap<>();
        int rockets, sounds, closes; boolean failTitle;
        @Override public Collection<Player> audience(Location table, String[] names, double radius) { return viewers; }
        @Override public void title(Player player, Title title) { if (failTitle) throw new IllegalStateException("test"); titles.put(player.getName(), title); }
        @Override public void sound(Player player, Location table, MahjongActionEffects.Action action) { sounds++; }
        @Override public void fireworks(Location table, int count) { rockets += count; }
        @Override public void close() { closes++; }
    }

    private static final class NativeHarness {
        final List<Rocket> rockets = new ArrayList<>();
        final List<Scheduled> tasks = new ArrayList<>();
        final World world = (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[]{World.class}, (proxy, method, args) -> {
            if (method.getName().equals("spawn")) {
                assertEquals(Firework.class, args[1]);
                Rocket rocket = new Rocket();
                @SuppressWarnings("unchecked") java.util.function.Consumer<Firework> prepare = (java.util.function.Consumer<Firework>) args[2];
                prepare.accept(rocket.entity);
                assertTrue(rocket.tagged, "protection must exist before the firework joins the world");
                rockets.add(rocket);
                return rocket.entity;
            }
            return null;
        });
        final BukkitScheduler scheduler = (BukkitScheduler) Proxy.newProxyInstance(BukkitScheduler.class.getClassLoader(), new Class<?>[]{BukkitScheduler.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("runTaskLater")) {
                        Scheduled scheduled = new Scheduled((Runnable) args[1], (long) args[2]); tasks.add(scheduled); return scheduled.task;
                    }
                    return null;
                });
        final Server server = (Server) Proxy.newProxyInstance(Server.class.getClassLoader(), new Class<?>[]{Server.class},
                (proxy, method, args) -> method.getName().equals("getScheduler") ? scheduler : null);
        final Plugin plugin = (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(), new Class<?>[]{Plugin.class},
                (proxy, method, args) -> method.getName().equals("getServer") ? server : null);
    }

    private static final class Rocket {
        boolean tagged, detonated, removed, persistent = true;
        int visualEffects;
        final PersistentDataContainer data = (PersistentDataContainer) Proxy.newProxyInstance(PersistentDataContainer.class.getClassLoader(),
                new Class<?>[]{PersistentDataContainer.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "set" -> { tagged = args[0].equals(MahjongActionEffects.FIREWORK_KEY); yield null; }
                    case "has" -> tagged && args[0].equals(MahjongActionEffects.FIREWORK_KEY);
                    default -> null;
                });
        final FireworkMeta meta = (FireworkMeta) Proxy.newProxyInstance(FireworkMeta.class.getClassLoader(), new Class<?>[]{FireworkMeta.class},
                (proxy, method, args) -> { if (method.getName().equals("addEffect")) visualEffects++; return null; });
        final Firework entity = (Firework) Proxy.newProxyInstance(Firework.class.getClassLoader(), new Class<?>[]{Firework.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getPersistentDataContainer" -> data; case "getFireworkMeta" -> meta;
                    case "setPersistent" -> { persistent = (boolean) args[0]; yield null; }
                    case "detonate" -> { detonated = true; yield null; }
                    case "remove" -> { removed = true; yield null; }
                    case "isValid" -> !removed;
                    case "hashCode" -> System.identityHashCode(proxy); case "equals" -> proxy == args[0]; default -> null;
                });
    }

    private static final class Scheduled {
        final Runnable run;
        final long ticks;
        boolean cancelled;
        final BukkitTask task = (BukkitTask) Proxy.newProxyInstance(BukkitTask.class.getClassLoader(), new Class<?>[]{BukkitTask.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "cancel" -> { cancelled = true; yield null; }
                    case "hashCode" -> System.identityHashCode(proxy); case "equals" -> proxy == args[0]; default -> null;
                });
        Scheduled(Runnable run, long ticks) { this.run = run; this.ticks = ticks; }
    }
}
