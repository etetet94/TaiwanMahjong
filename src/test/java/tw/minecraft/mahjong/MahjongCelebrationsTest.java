package tw.minecraft.mahjong;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.MemoryConfiguration;
import org.junit.jupiter.api.Test;
import tw.minecraft.mahjong.engine.MahjongGame;
import tw.minecraft.mahjong.engine.Score;

class MahjongCelebrationsTest {
    @Test void defaultsEnableAnnouncementsAndKeepEffectsLocal() {
        var settings = MahjongCelebrations.Settings.from(new MemoryConfiguration());
        assertTrue(settings.enabled()); assertTrue(settings.listening()); assertTrue(settings.wins()); assertTrue(settings.draws());
        assertTrue(settings.sounds()); assertTrue(settings.particles()); assertEquals(16, settings.radius());
        assertEquals(32, new MahjongCelebrations.Settings(true,true,true,true,true,true,999).radius());
        assertEquals(0, new MahjongCelebrations.Settings(true,true,true,true,true,true,-1).radius());
        assertEquals(16, new MahjongCelebrations.Settings(true,true,true,true,true,true,Double.NaN).radius());
    }

    @Test void messagesAreTraditionalChineseBriefAndPracticeIsClearlyMarked() {
        String listening = MahjongCelebrations.listeningMessage("小明", true);
        assertEquals("【麻將喜報】【練習局】 小明 宣告聽牌！好牌就差臨門一腳！", listening);
        assertFalse(listening.contains("聽口")); assertFalse(listening.contains("手牌"));
        assertEquals("【麻將喜報】 小明 自摸！牌型 3 台，手氣旺旺！", MahjongCelebrations.winMessage("小明",3,true,false));
        assertEquals("【麻將喜報】【練習局】 小華 胡牌！牌型 0 台，手氣旺旺！", MahjongCelebrations.winMessage("小華",0,false,true));
        assertEquals("【麻將喜報】 小明 自摸！牌型 3 番，手氣旺旺！", MahjongCelebrations.winMessage("小明",3,true,false,"番"));
        assertEquals("【麻將喜報】 小明 自摸！牌型 3 飜，手氣旺旺！", MahjongCelebrations.winMessage("小明",3,true,false,"飜"));
        assertEquals("【麻將喜報】 小明 宣告立直！好牌就差臨門一腳！", MahjongCelebrations.listeningMessage("小明",false,true));
        assertFalse(MahjongCelebrations.listeningMessage("甲\n乙\r丙",false).contains("\n"));
    }

    @Test void masterAndPerEventSwitchesPreventMessagesAndEffects() {
        for (var settings : List.of(new MahjongCelebrations.Settings(false,true,true,true,true,true,16),
                new MahjongCelebrations.Settings(true,false,false,false,true,true,16))) {
            FakeDelivery delivery = new FakeDelivery();
            MahjongCelebrations helper = helper(settings,delivery);
            helper.announceListening(new Location(null,0,0,0),"玩家",false);
            helper.announceWin(new Location(null,0,0,0),"玩家",3,true,false);
            helper.announceFinished(null,null,new MahjongGame.Outcome(List.of(),List.of(),-1,false,true,"流局"),false);
            assertTrue(delivery.messages.isEmpty()); assertEquals(0,delivery.effects);
        }
        MemoryConfiguration config = new MemoryConfiguration();
        config.set("celebrations.listening-announcements",false);
        assertFalse(MahjongCelebrations.Settings.from(config).listening());
    }

    @Test void multipleWinnersShareOneShortEffectAndDrawsHaveOnlyBriefText() {
        FakeDelivery delivery = new FakeDelivery();
        MahjongCelebrations helper = helper(new MahjongCelebrations.Settings(true,true,true,true,true,true,16),delivery);
        var outcome = new MahjongGame.Outcome(List.of(new MahjongGame.Win(1,new Score(2,List.of())),new MahjongGame.Win(3,new Score(4,List.of()))),List.of(),0,false,false,"榮和");
        helper.announceFinished(new Location(null,0,0,0),new String[]{"東","南","西","北"},outcome,true);
        assertEquals(2,delivery.messages.size()); assertEquals(1,delivery.effects);
        assertTrue(delivery.messages.get(0).contains("南 胡牌！牌型 2 台"));
        assertTrue(delivery.messages.get(1).contains("北 胡牌！牌型 4 台"));
        assertTrue(delivery.messages.stream().allMatch(m -> m.contains("【練習局】")));
        helper.announceFinished(null,null,new MahjongGame.Outcome(List.of(),List.of(),-1,false,true,"流局"),true);
        assertEquals(3,delivery.messages.size()); assertEquals(1,delivery.effects);
        assertEquals("【麻將牌桌】【練習局】 本局流局，下回好運再來！",delivery.messages.get(2));
    }

    @Test void disablingEffectsStillBroadcastsAndFailuresNeverEscapeToSettlement() {
        FakeDelivery delivery = new FakeDelivery();
        helper(new MahjongCelebrations.Settings(true,true,true,true,false,false,16),delivery).announceListening(new Location(null,0,0,0),"玩家",false);
        assertEquals(1,delivery.messages.size()); assertEquals(0,delivery.effects);
        MahjongCelebrations.Delivery broken = new MahjongCelebrations.Delivery() {
            @Override public void broadcast(Component message) { throw new IllegalStateException("Test broadcast failure"); }
            @Override public void effects(Location table, boolean win, MahjongCelebrations.Settings settings) { throw new IllegalStateException("Test effect failure"); }
        };
        assertDoesNotThrow(() -> helper(new MahjongCelebrations.Settings(true,true,true,true,true,true,16),broken)
                .announceWin(new Location(null,0,0,0),"玩家",4,true,false));
    }

    @Test void effectsExcludeOtherWorldsAndPlayersOutsideRadius() {
        World world = world(), other = world();
        Location table = new Location(world,0,64,0);
        assertTrue(MahjongCelebrations.nearby(table,new Location(world,16,64,0),16));
        assertFalse(MahjongCelebrations.nearby(table,new Location(world,16.01,64,0),16));
        assertFalse(MahjongCelebrations.nearby(table,new Location(other,0,64,0),16));
        assertFalse(MahjongCelebrations.nearby(table,new Location(world,0,64,0),0));
    }

    private static MahjongCelebrations helper(MahjongCelebrations.Settings settings, MahjongCelebrations.Delivery delivery) {
        Logger logger = Logger.getAnonymousLogger(); logger.setLevel(Level.OFF);
        return new MahjongCelebrations(settings,delivery,logger);
    }

    private static World world() {
        UUID id = UUID.randomUUID();
        return (World)Proxy.newProxyInstance(World.class.getClassLoader(),new Class<?>[]{World.class},(proxy,method,args) -> switch(method.getName()) {
            case "equals" -> proxy == args[0]; case "hashCode" -> id.hashCode(); case "getUID" -> id;
            default -> throw new UnsupportedOperationException(method.getName());
        });
    }

    private static final class FakeDelivery implements MahjongCelebrations.Delivery {
        final List<String> messages = new ArrayList<>(); int effects;
        @Override public void broadcast(Component message) { messages.add(PlainTextComponentSerializer.plainText().serialize(message)); }
        @Override public void effects(Location table, boolean win, MahjongCelebrations.Settings settings) { effects++; }
    }
}
