package tw.minecraft.mahjong.engine;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

/** Dependency-free fixtures covering scoring boundaries, not just hand-shape recognition. */
public final class RiichiScorerTest {
    private static int assertions;
    private enum Flag { RIICHI, DOUBLE, IPPATSU, LAST_DRAW, LAST_DISCARD, RINSHAN, CHANKAN }
    public static void main(String[] args) throws Exception {
        yakuRequirement(); sequences(); sets(); outsideAndFlushes(); specialHands(); yakuman();
        circumstances(); fu(); dora(); serialization();
        System.out.println("PASS Japanese scorer: " + assertions + " assertions");
    }

    private static void yakuRequirement() {
        Score noYaku = score("123m456m234p789s55p", List.of(), "7s", false);
        check(noYaku == null, "closed hand without yaku cannot ron");
        Score doraOnly = score("123m456m234p789s55p", List.of(), "7s", false, "4p", "", new Flag[0]);
        check(doraOnly == null, "two dora never satisfy the yaku requirement");
        Score open = score("456m234p678s55p", List.of(seq("123m", true)), "8s", true);
        check(open == null, "open self-draw is not a yaku");
        Score tanyao = score("456m234p678s55p", List.of(seq("234m", true)), "8s", false);
        expect(tanyao, "tanyao", 1); check(tanyao.tai() == 1, "open tanyao allowed");
        check(RiichiScorer.score(h("11111m234p567s789s"), List.of(), context(tile("9s"), false)) == null, "fifth copies rejected");
        check(RiichiScorer.score(h("123m456m234p678s55p"), List.of(), context(tile("1z"), false)) == null, "winning tile must be present");
        check(RiichiScorer.score(h("123m456m234p678s555p11z"), List.of(), context(tile("1z"), false)) == null, "Taiwan seventeen tiles rejected");
    }

    private static void sequences() {
        Score pinfu = score("123m456m234p678s55p", List.of(), "8s", false);
        expect(pinfu, "pinfu", 1); check(pinfu.tai() == 1 && pinfu.fu() == 30, "closed pinfu ron 1 han30fu");
        Score tsumo = score("123m456m234p678s55p", List.of(), "8s", true);
        expect(tsumo, "menzen_tsumo", 1); check(tsumo.tai() == 2 && tsumo.fu() == 20, "pinfu tsumo20fu");
        Score twin = score("123123m456p678s55p", List.of(), "8s", false);
        expect(twin, "iipeikou", 1);
        Score twinOpen = score("123m456p678s55p", List.of(seq("123m", true)), "8s", false, Flag.RIICHI);
        check(twinOpen == null, "iipeikou and riichi require closed hand");
        Score doubleTwin = score("123123m456456p77s", List.of(), "6p", false);
        expect(doubleTwin, "ryanpeikou", 3); check(!has(doubleTwin, "iipeikou") && !has(doubleTwin, "chiitoitsu"), "ryanpeikou replaces weaker alternatives");
        Score oneSuitTwin = score("123123123123m55p", List.of(), "3m", true);
        expect(oneSuitTwin, "ryanpeikou", 3);
        Score mixed = score("123m123p123s789s55p", List.of(), "9s", false);
        expect(mixed, "sanshoku_doujun", 2);
        Score mixedOpen = score("123p123s789s55p", List.of(seq("123m", true)), "9s", false);
        expect(mixedOpen, "sanshoku_doujun", 1);
        Score straight = score("123456789m234p55s", List.of(), "4p", false);
        expect(straight, "ittsu", 2);
        Score straightOpen = score("456789m234p55s", List.of(seq("123m", true)), "4p", false);
        expect(straightOpen, "ittsu", 1);
        Score ambiguous = score("123456m234p678s55p", List.of(), "3m", false);
        check(ambiguous == null, "edge 123 completion never receives pinfu");
        Score bestPlacement = score("123345m456p678s55p", List.of(), "3m", false);
        expect(bestPlacement, "pinfu", 1); check(bestPlacement.fu() == 30, "winning tile may use 345 two-sided placement instead of 123 edge");
    }

    private static void sets() {
        Score all = score("111m222p333s555p77s", List.of(), "5p", false);
        expect(all, "toitoi", 2); expect(all, "sanankou", 2);
        check(!has(all, "suuankou"), "ron-completed fourth triplet is not concealed");
        Score three = score("111m222p333s456m77p", List.of(), "3s", false);
        check(three == null, "ron-completed third triplet prevents sanankou and no other yaku");
        Score threeDrawn = score("111m222p333s456m77p", List.of(), "3s", true);
        expect(threeDrawn, "sanankou", 2);
        Score colored = score("222m222p222s456m77p", List.of(), "2s", false);
        expect(colored, "sanshoku_doukou", 2); expect(colored, "tanyao", 1);
        Score dragons = score("555666z123m456p77z", List.of(), "6p", false);
        expect(dragons, "shousangen", 2); expect(dragons, "yakuhai_dragon_33", 1); expect(dragons, "yakuhai_dragon_32", 1);
        Score doubleWind = RiichiScorer.score(h("111z234m456p678s55p"), List.of(),
                new RiichiScorer.Context(tile("8s"), false, 0, 0, false, false, false, false, false, false, false, false, false, List.of(), List.of()));
        expect(doubleWind, "yakuhai_seat", 1); expect(doubleWind, "yakuhai_round", 1);
        Score quads = score("234m55p", List.of(quad("1111m", false), quad("2222p", false), quad("3333s", true)), "4m", false);
        expect(quads, "sankantsu", 2); check(quads.fu() == 80, "quad fu includes all physical triplet forms");
    }

    private static void outsideAndFlushes() {
        Score chanta = score("123m789m123p111z99s", List.of(), "3p", false);
        expect(chanta, "chanta", 2); check(!has(chanta, "junchan"), "honors select chanta");
        Score chantaOpen = score("789m123p111z99s", List.of(seq("123m", true)), "3p", false);
        expect(chantaOpen, "chanta", 1);
        Score junchan = score("123m789m123p789s99p", List.of(), "9s", false);
        expect(junchan, "junchan", 3); check(!has(junchan, "chanta"), "junchan replaces chanta");
        Score junchanOpen = score("789m123p789s99p", List.of(seq("123m", true)), "9s", false);
        expect(junchanOpen, "junchan", 2);
        Score outside = score("111m999p111z555z99s", List.of(), "5z", false);
        expect(outside, "honroutou", 2); expect(outside, "toitoi", 2); check(!has(outside, "chanta"), "honroutou not chanta");
        Score half = score("123456789m111z55m", List.of(), "9m", false);
        expect(half, "honitsu", 3);
        Score halfOpen = score("456789m111z55m", List.of(seq("123m", true)), "9m", false);
        expect(halfOpen, "honitsu", 2);
        Score full = score("123345456678m99m", List.of(), "8m", false);
        expect(full, "chinitsu", 6); check(!has(full, "honitsu"), "chinitsu replaces honitsu");
        Score fullOpen = score("345456678m99m", List.of(seq("123m", true)), "8m", false);
        expect(fullOpen, "chinitsu", 5);
    }

    private static void specialHands() {
        Score pairs = score("11m22m44p66p88s11z22z", List.of(), "2z", false);
        expect(pairs, "chiitoitsu", 2); check(pairs.tai() == 2 && pairs.fu() == 25, "seven different pairs fixed25fu");
        Score pairsTsumo = score("22m33m44p66p77s88s55m", List.of(), "8s", true, Flag.RIICHI);
        expect(pairsTsumo, "chiitoitsu", 2); expect(pairsTsumo, "tanyao", 1);
        check(pairsTsumo.fu() == 25, "seven pairs tsumo never adds or rounds fu");
        check(score("1111m22p44p66s88s11z", List.of(), "1z", false) == null, "quad cannot count as two pairs");
        Score pairOutside = score("11m99m11p99p11s11z55z", List.of(), "5z", false);
        expect(pairOutside, "chiitoitsu", 2); expect(pairOutside, "honroutou", 2);
        Score kokushi = score("19m19p19s1234567z1m", List.of(), "1m", false);
        expect(kokushi, "kokushi_13", 26); check(kokushi.yakumanCount() == 2 && kokushi.fu() == 0, "thirteen-sided kokushi double yakuman");
        Score ordinaryKokushi = score("19m19p19s1234567z1m", List.of(), "9m", false);
        expect(ordinaryKokushi, "kokushi", 13); check(ordinaryKokushi.yakumanCount() == 1, "single-wait kokushi remains single yakuman");
        Score honorsPairs = score("11223344556677z", List.of(), "7z", false);
        expect(honorsPairs, "tsuuiisou", 13); check(!has(honorsPairs, "chiitoitsu"), "all honors seven pairs only yakuman");
    }

    private static void yakuman() {
        Score bigDragons = score("555666777z123m22p", List.of(), "2p", false);
        expect(bigDragons, "daisangen", 13); check(bigDragons.tai() == 13 && !has(bigDragons, "shousangen"), "yakuman suppress ordinary components");
        Score littleWinds = score("111222333z123m44z", List.of(), "3m", false);
        expect(littleWinds, "shousuushii", 13);
        Score bigWinds = score("111222333444z55z", List.of(), "4z", false);
        expect(bigWinds, "daisuushii", 13); expect(bigWinds, "tsuuiisou", 13);
        check(bigWinds.yakumanCount() == 2 && bigWinds.tai() == 26, "distinct yakuman stack without special-wait doubling");
        Score concealed = score("111m222p333s555p77s", List.of(), "7s", false);
        expect(concealed, "suuankou_tanki", 26); check(concealed.yakumanCount() == 2, "suuankou pair ron double yakuman");
        Score concealedTsumo = score("111m222p333s555p77s", List.of(), "5p", true);
        expect(concealedTsumo, "suuankou", 13);
        Score terminals = score("111m999m111p999s11s", List.of(), "9s", false);
        expect(terminals, "chinroutou", 13);
        Score green = score("234234s666s888s66z", List.of(), "6z", false);
        expect(green, "ryuuiisou", 13);
        Score greenNoDragon = score("234234s666s888s22s", List.of(), "2s", false);
        expect(greenNoDragon, "ryuuiisou", 13);
        Score nine = score("11123456789995m", List.of(), "5m", false);
        expect(nine, "junsei_chuuren", 26); check(nine.yakumanCount() == 2, "nine-sided nine gates doubled");
        Score ordinaryNine = score("11123456789995m", List.of(), "1m", false);
        expect(ordinaryNine, "chuuren", 13); check(ordinaryNine.yakumanCount() == 1, "non-nine-sided nine gates single yakuman");
        Score nineKan = score("2345678999m5m", List.of(quad("1111m", false)), "5m", true);
        check(nineKan != null && !has(nineKan, "chuuren"), "declared quad prevents nine gates");
        Score fourKan = score("55p", List.of(quad("1111m", false), quad("2222p", true), quad("3333s", true), quad("7777z", false)), "5p", false);
        expect(fourKan, "suukantsu", 13);
        Score heaven = RiichiScorer.score(h("123m456m234p678s55p"), List.of(),
                new RiichiScorer.Context(tile("8s"), true, 0, 0, false, false, false, true, false, false, false, false, false, h("4p"), h("4p")));
        expect(heaven, "tenhou", 13); check(heaven.tai() == 13 && !has(heaven, "dora"), "heavenly suppresses ordinary yaku and dora");
        Score earth = RiichiScorer.score(h("123m456m234p678s55p"), List.of(),
                new RiichiScorer.Context(tile("8s"), true, 1, 0, false, false, false, false, true, false, false, false, false, List.of(), List.of()));
        expect(earth, "chiihou", 13);
        Score renhou = RiichiScorer.score(h("123m456m234p789s55p"), List.of(),
                new RiichiScorer.Context(tile("7s"), false, 1, 0, false, false, false, false, false, false, false, false, false, List.of(), List.of(), true));
        expect(renhou, "renhou", 13); check(renhou.yakumanCount() == 1, "renhou is an actual yakuman before first own draw");
    }

    private static void circumstances() {
        String edge = "123m456m234p789s55p";
        expect(score(edge, List.of(), "7s", false, Flag.RIICHI), "riichi", 1);
        Score doubled = score(edge, List.of(), "7s", false, Flag.DOUBLE, Flag.IPPATSU);
        expect(doubled, "double_riichi", 2); expect(doubled, "ippatsu", 1); check(!has(doubled, "riichi"), "double riichi replaces riichi");
        check(score(edge, List.of(), "7s", false, Flag.IPPATSU) == null, "ippatsu requires riichi");
        expect(score(edge, List.of(), "7s", true, Flag.LAST_DRAW), "haitei", 1);
        expect(score(edge, List.of(), "7s", false, Flag.LAST_DISCARD), "houtei", 1);
        expect(score(edge, List.of(), "7s", false, Flag.CHANKAN), "chankan", 1);
        Score rinshan = score("456m234p678s55p", List.of(quad("1111m", true)), "8s", true, Flag.RINSHAN, Flag.LAST_DRAW);
        expect(rinshan, "rinshan", 1); check(!has(rinshan, "haitei"), "rinshan and last live draw mutually exclusive");
        check(score(edge, List.of(), "7s", false, Flag.LAST_DRAW, Flag.RINSHAN) == null, "selfdraw flags do not supply ron yaku");
        Score chankanLast = score(edge, List.of(), "7s", false, Flag.CHANKAN, Flag.LAST_DISCARD);
        check(has(chankanLast, "chankan") && !has(chankanLast, "houtei"), "robbed kan is not a last discard");
    }

    private static void fu() {
        Score edge = score("123m456m234p789s55p", List.of(), "7s", false, Flag.RIICHI);
        check(edge.fu() == 40, "closed ron edge 20+10+2 rounded40");
        Score middle = score("123m456m234p678s55p", List.of(), "7s", false, Flag.RIICHI);
        check(middle.fu() == 40 && !has(middle, "pinfu"), "middle wait2fu");
        Score pair = score("123m456m234p678s55p", List.of(), "5p", false, Flag.RIICHI);
        check(pair.fu() == 40 && !has(pair, "pinfu"), "pair wait2fu");
        Score honorPair = score("123m456m234p678s11z", List.of(), "8s", true);
        check(honorPair.fu() == 30 && !has(honorPair, "pinfu"), "value pair prevents pinfu");
        Score valuelessPair = score("123m456m234p678s33z", List.of(), "8s", false);
        expect(valuelessPair, "pinfu", 1);
        Score openPinfu = score("456789m234p55s", List.of(seq("123m", true)), "4p", false);
        check(openPinfu.fu() == 30, "open zero-fu shape has30fu floor");
        Score ronTriplet = score("111m222p333s456m77p", List.of(), "3s", false, Flag.RIICHI);
        check(ronTriplet.fu() == 50, "ron triplet open2fu while hand retains closed ron10fu");
        Score tsumoTriplet = score("111m222p333s456m77p", List.of(), "3s", true);
        check(tsumoTriplet.fu() == 40, "tsumo keeps concealed tripletfu");
        Score doublePair = RiichiScorer.score(h("111m222p333s456m11z"), List.of(),
                new RiichiScorer.Context(tile("6m"), true, 0, 0, false, false, false, false, false, false, false, false, false, List.of(), List.of()));
        check(doublePair.fu() == 50, "double wind pair adds4fu (20+2+8+4+4+4=42 rounded50)");
    }

    private static void dora() {
        check(RiichiScorer.doraTile(tile("9m")) == tile("1m"), "number wrap");
        check(RiichiScorer.doraTile(tile("9p")) == tile("1p"), "circle wrap");
        check(RiichiScorer.doraTile(tile("9s")) == tile("1s"), "bamboo wrap");
        check(RiichiScorer.doraTile(tile("4z")) == tile("1z"), "north wraps east");
        check(RiichiScorer.doraTile(tile("5z")) == tile("6z"), "white points green with saved ID order");
        check(RiichiScorer.doraTile(tile("6z")) == tile("7z"), "green points red");
        check(RiichiScorer.doraTile(tile("7z")) == tile("5z"), "red points white");
        Score bonus = score("123m456m234p678s55p", List.of(), "8s", false, "44p", "4p", Flag.RIICHI);
        expect(bonus, "dora", 4); expect(bonus, "ura_dora", 2); check(bonus.tai() == 8, "duplicate indicators count independently");
        Score noRiichi = score("123m456m234p678s55p", List.of(), "8s", false, "", "4p", new Flag[0]);
        check(!has(noRiichi, "ura_dora") && noRiichi.tai() == 1, "ura without riichi ignored");
        Score quadBonus = score("456m234p678s55p", List.of(quad("1111m", true)), "8s", true, "9m", "", Flag.RINSHAN);
        expect(quadBonus, "dora", 4);
        Score dragonBonus = score("555z123m456p678s22p", List.of(), "8s", false, "7z", "", new Flag[0]);
        expect(dragonBonus, "dora", 3);
        Score yakuman = score("555666777z123m22p", List.of(), "2p", false, "77777z", "77777z", Flag.RIICHI);
        check(yakuman.tai() == 13 && !has(yakuman, "dora") && !has(yakuman, "ura_dora"), "yakuman excludes even many bonus tiles");
        Score ambiguousYakuman = score("111222333m444p55s", List.of(), "5s", true, "99999m", "99999m", Flag.RIICHI);
        expect(ambiguousYakuman, "suuankou_tanki", 26);
        check(ambiguousYakuman.tai() == 26, "ordinary alternate decomposition cannot replace yakuman with thirty dora");
    }

    private static void serialization() throws Exception {
        Score taiwan = new Score(3, List.of(new Score.Tai("closed", "門清", 1)));
        check(taiwan.unitLabel().equals("台") && taiwan.fu() == 0 && taiwan.yakumanCount() == 0, "Taiwan constructor preserved");
        Score japanese = score("123m456m234p678s55p", List.of(), "8s", false);
        check(japanese.unitLabel().equals("飜"), "Japanese units correctly labelled");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) { out.writeObject(japanese); }
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            check(japanese.equals(in.readObject()), "new score serialization roundtrip");
        }
        Score restoredDefaults = new Score(3, taiwan.breakdown(), null, 0, 0);
        check(restoredDefaults.equals(taiwan), "missing serialized unit normalized for historical saves");
        // Produced using the pre-Japanese, two-component Score.class, not the current constructor.
        String historical = "rO0ABXNyACF0dy5taW5lY3JhZnQubWFoam9uZy5lbmdpbmUuU2NvcmUAAAAAAAAAAAIAAkkAA3RhaUwACWJyZWFrZG93bnQAEExqYXZhL3V0aWwvTGlzdDt4cAAAAANzcgARamF2YS51dGlsLkNvbGxTZXJXjqu2OhuoEQMAAUkAA3RhZ3hwAAAAAXcEAAAAAXNyACV0dy5taW5lY3JhZnQubWFoam9uZy5lbmdpbmUuU2NvcmUkVGFpAAAAAAAAAAACAANJAAN0YWlMAANrZXl0ABJMamF2YS9sYW5nL1N0cmluZztMAARuYW1lcQB+AAZ4cAAAAAF0AAZjbG9zZWR0AANvbGR4";
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(historical)))) {
            Score restored = (Score) in.readObject();
            check(restored.tai() == 3 && restored.unitLabel().equals("台") && restored.fu() == 0
                    && restored.yakumanCount() == 0 && restored.breakdown().get(0).key().equals("closed"), "pre-upgrade serialized score is compatible");
        }
    }

    private static Score score(String hand, List<Meld> melds, String winningTile, boolean self, Flag... flags) {
        return score(hand, melds, winningTile, self, "", "", flags);
    }
    private static Score score(String hand, List<Meld> melds, String winningTile, boolean self, String dora, String ura, Flag... flags) {
        List<Flag> f = Arrays.asList(flags);
        return RiichiScorer.score(h(hand), melds, new RiichiScorer.Context(tile(winningTile), self, 1, 0,
                f.contains(Flag.RIICHI), f.contains(Flag.DOUBLE), f.contains(Flag.IPPATSU), false, false,
                f.contains(Flag.LAST_DRAW), f.contains(Flag.LAST_DISCARD), f.contains(Flag.RINSHAN), f.contains(Flag.CHANKAN), h(dora), h(ura)));
    }
    private static RiichiScorer.Context context(int tile, boolean self) {
        return new RiichiScorer.Context(tile, self, 1, 0, false, false, false, false, false, false, false, false, false, List.of(), List.of());
    }
    private static Meld seq(String text, boolean open) { return new Meld(Meld.Kind.SEQUENCE, h(text).get(0), open, open ? 2 : -1); }
    private static Meld quad(String text, boolean open) { return new Meld(Meld.Kind.QUAD, h(text).get(0), open, open ? 2 : -1); }
    private static int tile(String text) { List<Integer> tiles = h(text); if (tiles.size() != 1) throw new IllegalArgumentException(text); return tiles.get(0); }
    /** Conventional test notation: 1..7z = east south west north white green red. */
    private static List<Integer> h(String text) {
        List<Integer> result = new ArrayList<>(); StringBuilder digits = new StringBuilder();
        for (char c : text.toCharArray()) {
            if (Character.isDigit(c)) { digits.append(c); continue; }
            for (char d : digits.toString().toCharArray()) {
                int n = d - '1';
                result.add(switch (c) { case 'm' -> n; case 'p' -> 9 + n; case 's' -> 18 + n;
                    case 'z' -> n < 4 ? 27 + n : n == 4 ? 33 : n == 5 ? 32 : 31;
                    default -> throw new IllegalArgumentException(text); });
            }
            digits.setLength(0);
        }
        if (!digits.isEmpty()) throw new IllegalArgumentException(text);
        return List.copyOf(result);
    }
    private static boolean has(Score score, String key) { return score != null && score.breakdown().stream().anyMatch(p -> p.key().equals(key)); }
    private static void expect(Score score, String key, int value) {
        check(score != null && score.breakdown().stream().anyMatch(p -> p.key().equals(key) && p.tai() == value), "expected " + key + "=" + value + "; actual " + score);
    }
    private static void check(boolean condition, String message) { assertions++; if (!condition) throw new AssertionError(message); }
}
