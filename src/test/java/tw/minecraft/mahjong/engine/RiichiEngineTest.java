package tw.minecraft.mahjong.engine;

import java.io.*;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.*;

/** Executable integration regressions; fixtures contain exactly four physical copies of each tile. */
public final class RiichiEngineTest {
    private static int checks;
    private static final MahjongRules RULES = MahjongRules.japanese(7, 3);
    private static final List<Integer> READY = h(0,1,2,9,10,11,18,19,20,31,31,31,27);
    public static void main(String[] args) throws Exception {
        shapes(); dealing(); noYaku(); furiten(); declarations(); kans(); calls(); specialOutcomes(); simulations();
        for (String path : args) legacy(Path.of(path));
        System.out.println("RiichiEngineTest PASS: " + checks + " assertions; 120 conserved Japanese games");
    }
    private static void shapes() {
        List<Integer> jp = plus(READY, 27), tw = plus(plus(plus(jp, 4), 5), 6);
        check(HandAnalyzer.canWin(jp,List.of(),RuleSet.JAPANESE),"Four groups and pair wins Japanese");
        check(!HandAnalyzer.canWin(jp,List.of()),"Four groups do not win Taiwan");
        check(HandAnalyzer.canWin(tw,List.of()),"Five groups win Taiwan");
        check(!HandAnalyzer.canWin(tw,List.of(),RuleSet.JAPANESE),"Five groups do not win Japanese");
        check(HandAnalyzer.isSevenPairs(h(0,0,3,3,9,9,12,12,18,18,27,27,31,31)),"Seven distinct pairs");
        check(!HandAnalyzer.isSevenPairs(h(0,0,0,0,9,9,12,12,18,18,27,27,31,31)),"Quad is not two pairs");
        check(HandAnalyzer.isThirteenOrphans(h(0,0,8,9,17,18,26,27,28,29,30,31,32,33)),"Thirteen orphans");
        check(Tiles.doraAfter(8)==0&&Tiles.doraAfter(30)==27&&Tiles.doraAfter(33)==32&&Tiles.doraAfter(32)==31&&Tiles.doraAfter(31)==33,"Dora wraps and correct dragon order");
        check(new MahjongRules(5,2,16,MahjongRules.defaults().taiValues()).ruleSet()==RuleSet.TAIWAN,"Legacy rules constructor defaults Taiwan");
        check(new MahjongRules(5,2,16,Map.of(),null).ruleSet()==RuleSet.TAIWAN,"Missing serialized ruleSet defaults Taiwan");
    }
    private static void dealing() throws Exception {
        for (int seed=0;seed<80;seed++) {
            MahjongGame g=new MahjongGame(seed,seed%4,0,0,RULES,true);
            check(g.remainingWall()==69&&g.kanCount()==0&&g.doraIndicators().size()==1,"136 tiles minus 53 deal minus 14 dead wall");
            for(int s=0;s<4;s++) check(g.hands(s).size()==(s==g.dealer()?14:13)&&g.flowers(s).isEmpty(),"13/14 deal without flowers");
            check(g.uraDoraIndicators().isEmpty(),"Ura hidden during play");conserved(g);
        }
    }
    private static void noYaku() throws Exception {
        Meld open=new Meld(Meld.Kind.SEQUENCE,0,true,0);
        List<Integer> complete=h(3,4,5,10,11,12,22,23,24,28,28);
        MahjongGame tsumo=fixture(Map.of(1,complete),Map.of(1,List.of(open)),1);
        put(tsumo,"lastDraw",24);
        check(HandAnalyzer.canWin(tsumo.hands(1),tsumo.melds(1),RuleSet.JAPANESE),"Yakuless hand has valid 14-tile shape");
        check(!tsumo.validActions(1).contains(MahjongGame.Action.TSUMO),"Open yakuless self-draw is forbidden");
        fails(()->tsumo.tsumo(1));
        List<Integer> ready=new ArrayList<>(complete);ready.remove(Integer.valueOf(24));
        MahjongGame ron=fixture(Map.of(0,h(24),1,ready),Map.of(1,List.of(open)),0);
        ron.discard(0,24);
        check(!ron.validActions(1).contains(MahjongGame.Action.RON),"Yakuless ron forbidden");
        check(ron.isFuriten(1),"Skipping yakuless complete shape still causes temporary furiten");
    }
    private static void furiten() throws Exception {
        List<Integer> two=h(0,1,2,9,10,11,31,31,31,3,4,27,27);
        MahjongGame own=fixture(Map.of(0,h(5),1,two),Map.of(),0);
        List<List<Integer>> history=field(own,"discardHistory");history.get(1).add(2);
        own.discard(0,5);
        check(!own.validActions(1).contains(MahjongGame.Action.RON)&&own.isFuriten(1),"One own-discarded wait blocks ron on every wait");
        check(own.discards(1).isEmpty(),"Called-away discard history remains effective");
        MahjongGame temporary=fixture(Map.of(0,h(27),1,h(27),2,READY),Map.of(),0);
        temporary.discard(0,27);check(temporary.validActions(2).contains(MahjongGame.Action.RON),"Valid yakuhai ron offered");
        temporary.claim(2,MahjongGame.Action.PASS,-1);passAll(temporary);
        temporary.draw(1);temporary.discard(1,27);
        check(!temporary.validActions(2).contains(MahjongGame.Action.RON),"Passed ron stays furiten until next own draw");
        passAll(temporary);place(temporary,false,0,32);temporary.draw(2);
        check(temporary.isFuriten(2),"Temporary furiten lasts through own draw until own discard");
        temporary.discard(2,32);check(!temporary.isFuriten(2),"Own next discard clears temporary furiten");
        MahjongGame permanent=fixture(Map.of(0,h(27),1,h(27),2,READY),Map.of(),0);lock(permanent,2);
        permanent.discard(0,27);permanent.claim(2,MahjongGame.Action.PASS,-1);passAll(permanent);
        permanent.draw(1);permanent.discard(1,27);passAll(permanent);place(permanent,false,0,32);permanent.draw(2);
        check(permanent.isFuriten(2),"Riichi passing ron stays furiten after own draw");
        check(copy(permanent).isFuriten(2),"Permanent furiten survives serialization");
        MahjongGame self=fixture(Map.of(1,plus(READY,27)),Map.of(),1);put(self,"lastDraw",27);lock(self,1);
        boolean[] blocked=field(self,"riichiPassedRon");blocked[1]=true;
        check(self.validActions(1).contains(MahjongGame.Action.TSUMO),"Furiten still permits self-draw");self.tsumo(1);
    }
    private static void declarations() throws Exception {
        MahjongGame g=fixture(Map.of(0,plus(READY,26)),Map.of(),0);
        check(!g.riichi(0)&&g.listeningDiscards(0).contains(26),"Riichi voluntary and available on ready discard");
        g.declareListening(0,26);passAll(g);
        check(g.riichiEstablished(0)&&g.listeningWaits(0).equals(h(27)),"Accepted declaration establishes riichi and records waits");
        check(g.riichiDiscardIndex(0)==0&&copy(g).riichiDiscardIndex(0)==0,"Declaration river position survives serialization");
        check(copy(g).riichi(0),"Riichi survives restart");
        advanceToDraw(g,0);place(g,false,0,32);g.draw(0);
        fails(()->g.discard(0,0));check(!g.canDiscard(0,0)&&g.canDiscard(0,32),"Riichi locks discards to drawn tile");
        check(g.ippatsu(0),"Ippatsu remains through first following draw");g.discard(0,32);check(!g.ippatsu(0),"Ippatsu expires on next discard");
        MahjongGame bot=fixture(Map.of(0,plus(READY,26)),Map.of(),0);PracticeBot.act(bot,0);check(!bot.riichi(0),"Practice bot never forces voluntary declaration");
        MahjongGame open=fixture(Map.of(0,h(9,10,11,18,19,20,31,31,31,27,26)),Map.of(0,List.of(new Meld(Meld.Kind.SEQUENCE,0,true,3))),0);
        check(open.listeningDiscards(0).isEmpty(),"Open hands cannot riichi");fails(()->open.declareListening(0,26));
        MahjongGame safe=fixture(Map.of(0,plus(READY,31)),Map.of(),0);put(safe,"lastDraw",31);lock(safe,0);
        check(safe.concealedKanOptions(0).equals(h(31)),"Riichi permits drawn fourth tile with unchanged waits and structure");
        safe.concealedKan(0,31);check(safe.riichi(0)&&!safe.ippatsu(0)&&safe.listeningWaits(0).equals(h(27)),"Ankan preserves riichi and cancels ippatsu");
        MahjongGame changed=fixture(Map.of(0,h(0,0,0,0,1,2,3,4,5,6,7,8,8,8)),Map.of(),0);put(changed,"lastDraw",0);
        List<Integer> before=new ArrayList<>(changed.hands(0));before.remove(Integer.valueOf(0));lock(changed,0,HandAnalyzer.waits(before,List.of(),RuleSet.JAPANESE));
        check(changed.concealedKanOptions(0).isEmpty(),"Riichi forbids kan that changes waiting structure");
        MahjongGame interrupted=fixture(Map.of(0,plus(READY,26),1,h(26,26)),Map.of(),0);
        interrupted.declareListening(0,26);interrupted.claim(1,MahjongGame.Action.PON,-1);passAll(interrupted);
        check(interrupted.riichi(0)&&!interrupted.ippatsu(0),"Call on declaration discard cancels ippatsu but preserves riichi");
        check(interrupted.riichiEstablished(0)&&interrupted.riichiDiscardIndex(0)==-1,"Called declaration waits for next sideways river tile");
        int safeDiscard=interrupted.hands(1).stream().filter(t->interrupted.canDiscard(1,t)).findFirst().orElseThrow();
        interrupted.discard(1,safeDiscard);passAll(interrupted);advanceToDraw(interrupted,0);place(interrupted,false,0,32);interrupted.draw(0);interrupted.discard(0,32);passAll(interrupted);
        check(interrupted.riichiDiscardIndex(0)==0,"Next discard takes sideways declaration marker after called-away tile");
        MahjongGame robbedDeclaration=fixture(Map.of(0,h(18,19,20,21,22,23,24,25,26,33,33,33,28,27),1,h(0,1,2,3,4,5,6,7,8,31,31,31,27)),Map.of(),0);
        robbedDeclaration.declareListening(0,27);check(robbedDeclaration.riichi(0),"Riichi pending response");
        check(!robbedDeclaration.riichiEstablished(0),"Pending declaration has no1000-point deposit yet");
        robbedDeclaration.claim(1,MahjongGame.Action.RON,-1);passAll(robbedDeclaration);
        check(!robbedDeclaration.riichi(0)&&robbedDeclaration.listeningWaits(0).isEmpty(),"Ron on declaration tile cancels riichi");
        MahjongGame points=fixture(Map.of(0,plus(READY,26)),Map.of(),0);
        int[] balances={999,25000,25000,25000};points.setJapanesePoints(balances);balances[0]=25000;
        check(points.listeningDiscards(0).isEmpty(),"Below1000 points cannot riichi; supplied points are copied");
        check(copy(points).japanesePoints()[0]==999,"Riichi point eligibility survives restart");
        points.setJapanesePoints(new int[]{1000,25000,25000,25000});check(points.listeningDiscards(0).contains(26),"Exactly1000 points permits riichi");
    }
    private static void kans() throws Exception {
        List<Integer> remainder=h(9,10,11,18,19,20,31,31,31,27);
        MahjongGame open=fixture(Map.of(0,h(0),1,concat(h(0,0,0),remainder)),Map.of(),0);place(open,true,0,27);
        int live=open.remainingWall();open.discard(0,0);open.claim(1,MahjongGame.Action.KAN,-1);passAll(open);
        check(open.kanCount()==1&&open.doraIndicators().size()==2&&open.remainingWall()==live-1,"Kan consumes rinshan, replenishes dead wall and reveals dora");
        conserved(open);check(open.validActions(1).contains(MahjongGame.Action.TSUMO),"Japanese open-kan supplement permits tsumo");
        open.tsumo(1);check(open.outcome().winners().getFirst().score().breakdown().stream().anyMatch(p->p.key().equals("rinshan")),"Rinshan yaku awarded");
        for(var p:open.outcome().payments())check(p.diamonds()==7+3L*p.tai()&&p.tai()==open.outcome().winners().getFirst().score().tai(),"Diamond base+han and no Taiwan dealer bonus");
        MahjongGame closed=fixture(Map.of(0,concat(h(0,0,0,0),remainder)),Map.of(),0);closed.concealedKan(0,0);conserved(closed);
        check(closed.melds(0).getFirst().kind()==Meld.Kind.QUAD&&!closed.melds(0).getFirst().open(),"Concealed kan remains closed");
        MahjongGame added=fixture(Map.of(0,plus(remainder,0)),Map.of(0,List.of(new Meld(Meld.Kind.TRIPLET,0,true,2))),0);
        added.addedKan(0,0);passAll(added);check(added.kanCount()==1&&added.melds(0).getFirst().kind()==Meld.Kind.QUAD,"Unrobbed added kan draws replacement");conserved(added);
        MahjongGame rob=fixture(Map.of(0,h(0),1,h(1,2,9,10,11,18,19,20,31,31,31,27,27)),Map.of(0,List.of(new Meld(Meld.Kind.TRIPLET,0,true,2))),0);
        rob.addedKan(0,0);check(rob.validActions(1).contains(MahjongGame.Action.RON),"Added kan can be robbed");rob.claim(1,MahjongGame.Action.RON,-1);passAll(rob);
        check(rob.isFinished()&&rob.kanCount()==0&&rob.doraIndicators().size()==1&&rob.melds(0).getFirst().kind()==Meld.Kind.TRIPLET,"Robbed kan neither upgrades triplet nor reveals dora");
        MahjongGame limit=fixture(Map.of(0,h(0,0,0,0)),Map.of(),0);put(limit,"kanCount",4);
        check(limit.concealedKanOptions(0).isEmpty()&&limit.doraIndicators().size()==5,"No fifth kan");
        MahjongGame last=fixture(Map.of(0,h(0,0,0,0)),Map.of(),0);put(last,"head",(Integer)field(last,"tail")+1);
        check(last.concealedKanOptions(0).isEmpty(),"No kan on exhausted wall");
        MahjongGame lastRiichi=fixture(Map.of(0,plus(READY,26)),Map.of(),0);put(lastRiichi,"head",(Integer)field(lastRiichi,"tail")+1);
        check(lastRiichi.listeningDiscards(0).contains(26),"Riichi is permitted at zero live tiles");
        lastRiichi.declareListening(0,26);passAll(lastRiichi);check(lastRiichi.riichi(0),"Final-discard riichi retained at draw");
        MahjongGame houtei=fixture(Map.of(0,h(0,0,0,0),1,h(3,4,5,10,11,12,22,23,28,28)),Map.of(1,List.of(new Meld(Meld.Kind.SEQUENCE,6,true,2))),0);
        place(houtei,true,0,24);put(houtei,"head",field(houtei,"tail"));
        houtei.concealedKan(0,0);check(houtei.remainingWall()==0,"Last kan transfers last live tile to dead wall");
        houtei.discard(0,24);check(houtei.validActions(1).contains(MahjongGame.Action.RON),"Final rinshan discard has houtei and permits otherwise yakuless ron");
        houtei.claim(1,MahjongGame.Action.RON,-1);passAll(houtei);
        check(houtei.outcome().winners().getFirst().score().breakdown().stream().anyMatch(p->p.key().equals("houtei")),"Final kan discard scores houtei");
        MahjongGame four=fixture(Map.of(0,h(0,0,0,0,4,4,4,4),1,h(9,9,9,9),2,h(18,18,18,18),3,h(31,31,31,31)),Map.of(),0);
        int[] kanTiles={0,9,18,31};
        for(int s=0;s<4;s++) {
            if(s>0)four.draw(s);
            four.concealedKan(s,kanTiles[s]);conserved(four);
            check(four.kanCount()==s+1&&four.doraIndicators().size()==s+2,"Each of four real kans reveals its indicator");
            four.discard(s,four.lastDraw());passAll(four);
        }
        four.draw(0);
        check(Collections.frequency(four.hands(0),4)==4&&four.concealedKanOptions(0).isEmpty(),"A physical fifth quad cannot be declared after four real kans");
        conserved(four);
    }
    private static void calls() throws Exception {
        MahjongGame g=fixture(Map.of(0,h(0),1,h(1,2,3),2,h(1,2)),Map.of(),0);g.discard(0,0);
        check(g.chiOptions(1).contains(0)&&g.chiOptions(2).isEmpty(),"Only next player may chi");
        g.claim(1,MahjongGame.Action.CHI,0);passAll(g);
        check(!g.canDiscard(1,3),"Suji kuikae after chi forbidden");fails(()->g.discard(1,3));PracticeBot.act(g,1);conserved(g);
        MahjongGame priority=fixture(Map.of(0,h(2),1,h(0,1),2,h(2,2)),Map.of(),0);priority.discard(0,2);
        priority.claim(1,MahjongGame.Action.CHI,0);priority.claim(2,MahjongGame.Action.PON,-1);passAll(priority);
        check(priority.currentTurn()==2&&priority.melds(1).isEmpty(),"Pon wins over chi");conserved(priority);
        MahjongGame edgeChi=fixture(Map.of(0,h(2),1,h(0,1)),Map.of(),0);edgeChi.discard(0,2);edgeChi.claim(1,MahjongGame.Action.CHI,0);passAll(edgeChi);
        check(edgeChi.melds(1).getFirst().calledTile()==2&&copy(edgeChi).melds(1).getFirst().calledTile()==2,"Chi preserves exact claimed tile independent of sequence start and serialization");
        MahjongGame headBump=fixture(Map.of(0,h(27),1,h(0,1,2,3,4,5,6,7,8,31,31,31,27),2,h(9,10,11,12,13,14,15,16,17,32,32,32,27)),Map.of(),0);
        headBump.discard(0,27);headBump.claim(2,MahjongGame.Action.RON,-1);headBump.claim(1,MahjongGame.Action.RON,-1);
        check(headBump.isFinished()&&headBump.outcome().winners().size()==1&&headBump.outcome().winners().getFirst().seat()==1,"Japanese multiple ron resolves nearest winner independent of response order");
    }
    private static void specialOutcomes() throws Exception {
        MahjongGame renhou=fixture(Map.of(0,h(27),1,READY),Map.of(),0);put(renhou,"callsOccurred",false);put(renhou,"totalDiscards",0);
        renhou.discard(0,27);renhou.claim(1,MahjongGame.Action.RON,-1);passAll(renhou);
        Score human=renhou.outcome().winners().getFirst().score();
        check(human.yakumanCount()==1&&human.breakdown().stream().anyMatch(p->p.key().equals("renhou")),"Uninterrupted ron before first own draw scores renhou yakuman");
        MahjongGame later=fixture(Map.of(0,h(27),1,READY),Map.of(),0);put(later,"callsOccurred",false);int[] draws=field(later,"drawsTaken");draws[1]=1;
        later.discard(0,27);later.claim(1,MahjongGame.Action.RON,-1);passAll(later);
        check(later.outcome().winners().getFirst().score().breakdown().stream().noneMatch(p->p.key().equals("renhou")),"Ron after own first draw is not renhou");
        List<List<Integer>> ready=List.of(READY,h(0,0,1,1,2,2,3,3,4,4,5,5,6),h(9,9,10,10,11,11,12,12,13,13,14,14,15),h(18,18,19,19,20,20,21,21,22,22,23,23,24));
        for(int amount=0;amount<=4;amount++) {
            Map<Integer,List<Integer>> requested=new HashMap<>();
            requested.put(0,amount==0?h(0,2,4,6,8,9,11,13,15,17,18,20,22,25):plus(ready.get(0),25));
            for(int s=1;s<amount;s++)requested.put(s,ready.get(s));
            MahjongGame end=fixture(requested,Map.of(),0);put(end,"head",(Integer)field(end,"tail")+1);
            end.discard(0,25);passAll(end);
            check(end.outcome().draw()&&end.outcome().tenpaiSeats().size()==amount,"Exhaustive draw records exact tenpai seats");
            for(int s=0;s<4;s++) {
                int expected=amount==0||amount==4?0:s<amount?3000/amount:-3000/(4-amount);
                check(end.outcome().pointDeltas().get(s)==expected,"Standard3000-point noten pool net distribution");
            }
            check(end.outcome().payments().isEmpty(),"Match coordinator owns standard point-to-diamond settlement");
            check(copy(end).outcome().pointDeltas().equals(end.outcome().pointDeltas()),"Draw metadata survives serialization");
        }
        MahjongGame nagashi=fixture(Map.of(1,h(0,8,27)),Map.of(),0);int terminals=0,steps=0;
        while(!nagashi.isFinished()&&steps++<50) {
            if(nagashi.phase()==MahjongGame.Phase.DRAW){nagashi.draw(nagashi.currentTurn());continue;}
            if(nagashi.phase()==MahjongGame.Phase.CLAIM||nagashi.phase()==MahjongGame.Phase.KAN_CLAIM){passAll(nagashi);continue;}
            int s=nagashi.currentTurn(),tile;
            if(s==1){tile=new int[]{0,8,27}[terminals++];if(terminals==3)put(nagashi,"head",(Integer)field(nagashi,"tail")+1);}
            else tile=nagashi.hands(s).stream().filter(t->!Tiles.terminalOrHonor(t)).findFirst().orElseThrow();
            nagashi.discard(s,tile);passAll(nagashi);
        }
        check(nagashi.isFinished()&&!nagashi.outcome().draw()&&nagashi.outcome().selfDraw(),"Terminal-only uncalled discards win nagashi before noten payments");
        check(nagashi.outcome().winners().size()==1&&nagashi.outcome().winners().getFirst().seat()==1&&nagashi.outcome().winners().getFirst().score().tai()==5,"Nagashi is exactly mangan5han");
        MahjongGame called=fixture(Map.of(0,h(0,8),1,h(0,0)),Map.of(),0);called.discard(0,0);called.claim(1,MahjongGame.Action.PON,-1);passAll(called);
        int moves=0;
        while(called.currentTurn()!=0&&moves++<15){if(called.phase()==MahjongGame.Phase.DRAW)called.draw(called.currentTurn());else if(called.phase()==MahjongGame.Phase.DISCARD){int s=called.currentTurn();int tile=called.hands(s).stream().filter(t->!Tiles.terminalOrHonor(t)&&called.canDiscard(s,t)).findFirst().orElseThrow();called.discard(s,tile);}else passAll(called);}
        if(called.phase()==MahjongGame.Phase.DRAW)called.draw(0);called.discard(0,8);passAll(called);
        check(!called.discards(0).isEmpty()&&!called.isNagashiMangan(0),"Any called-away discard invalidates nagashi even if remaining pile is terminals");
    }
    private static void simulations() throws Exception {
        for(int seed=0;seed<120;seed++) {
            MahjongGame g=new MahjongGame(seed*727L,seed%4,seed%2,seed%3,RULES,seed%2==0);int steps=0;
            while(!g.isFinished()&&steps++<700) {
                int actor=g.currentTurn();
                if(g.phase()==MahjongGame.Phase.CLAIM||g.phase()==MahjongGame.Phase.KAN_CLAIM) {
                    actor=-1;for(int s=0;s<4;s++)if(!g.validActions(s).isEmpty()){actor=s;break;}
                    check(actor>=0,"Claim responder always exists");
                }
                if(seed%2==0)PracticeBot.act(g,actor);else g.autoAction(actor);
                if(!g.isFinished()) {
                    conserved(g);
                    for(int s=0;s<4;s++)check(g.hands(s).size()==13-3*g.melds(s).size()+((g.phase()==MahjongGame.Phase.DISCARD||g.phase()==MahjongGame.Phase.KAN_CLAIM)&&g.currentTurn()==s?1:0),"Correct Japanese hand size at every phase");
                }
                if(steps==15)g=copy(g);
            }
            check(g.isFinished(),"Japanese hand terminates");
            for(var win:g.outcome().winners())check(win.score().tai()>0&&win.score().unitLabel().equals(RuleSet.JAPANESE.unit()),"Every Japanese win has Japanese scored yaku");
        }
    }
    private static void legacy(Path file) throws Exception {
        MahjongGame g;try(ObjectInputStream in=new ObjectInputStream(Files.newInputStream(file))){g=(MahjongGame)in.readObject();}
        check(!g.isJapanese()&&g.rules().ruleSet()==RuleSet.TAIWAN,"Real pre-variant checkpoint stays Taiwan");
        int steps=0;while(!g.isFinished()&&steps++<700){if(g.phase()==MahjongGame.Phase.DRAW||g.phase()==MahjongGame.Phase.DISCARD)g.autoAction(g.currentTurn());else passAll(g);}
        check(g.isFinished(),"Legacy Taiwan checkpoint completes");
        check(g.outcome().pointDeltas().equals(List.of(0,0,0,0)),"Old completed Outcome defaults new point metadata safely");
    }
    private static MahjongGame fixture(Map<Integer,List<Integer>> requested,Map<Integer,List<Meld>> declared,int turn)throws Exception {
        MahjongGame g=new MahjongGame(178,0,0,3,RULES,true);
        List<Integer> pool=new ArrayList<>();for(int t=0;t<34;t++)for(int n=0;n<4;n++)pool.add(t);
        List<List<Integer>> hands=field(g,"hands");List<List<Meld>> melds=field(g,"melds");
        for(int s=0;s<4;s++){hands.get(s).clear();melds.get(s).clear();melds.get(s).addAll(declared.getOrDefault(s,List.of()));
            for(Meld m:melds.get(s))for(int t:m.tiles())check(pool.remove(Integer.valueOf(t)),"Fixture meld tile available");
            for(int t:requested.getOrDefault(s,List.of())){check(pool.remove(Integer.valueOf(t)),"Fixture hand tile available");hands.get(s).add(t);}}
        Collections.shuffle(pool,new Random(723));
        for(int s=0;s<4;s++){int target=13-3*melds.get(s).size()+(s==turn?1:0);check(hands.get(s).size()<=target,"Fixture hand length");while(hands.get(s).size()<target)hands.get(s).add(pool.removeLast());Collections.sort(hands.get(s));}
        List<Integer> wall=field(g,"wall"),dead=field(g,"deadWall");wall.clear();dead.clear();
        for(int i=0;i<14;i++)dead.add(pool.removeLast());wall.addAll(pool);
        put(g,"head",0);put(g,"tail",wall.size()-1);put(g,"currentTurn",turn);put(g,"lastDraw",hands.get(turn).getLast());
        put(g,"totalDiscards",8);put(g,"callsOccurred",true);
        Field kind=MahjongGame.class.getDeclaredField("drawKind");kind.setAccessible(true);kind.set(g,Enum.valueOf((Class)kind.getType(),"NORMAL"));
        conserved(g);return g;
    }
    private static void place(MahjongGame g,boolean dead,int offset,int tile)throws Exception {
        List<Integer> wall=field(g,"wall"),dw=field(g,"deadWall");int head=field(g,"head");List<Integer> destination=dead?dw:wall;int index=dead?offset:head+offset;
        if(destination.get(index)==tile)return;
        for(int i=head;i<wall.size();i++)if(wall.get(i)==tile){int old=destination.set(index,tile);wall.set(i,old);return;}
        for(int i=0;i<dw.size();i++)if(dw.get(i)==tile){int old=destination.set(index,tile);dw.set(i,old);return;}
        List<List<Integer>> hands=field(g,"hands");for(int s=0;s<4;s++)if(s!=g.currentTurn()&&hands.get(s).contains(tile)){
            int at=hands.get(s).indexOf(tile);int old=destination.set(index,tile);hands.get(s).set(at,old);Collections.sort(hands.get(s));return;}
        throw new AssertionError("Fixture unavailable tile "+tile);
    }
    private static void lock(MahjongGame g,int seat)throws Exception{lock(g,seat,h(27));}
    private static void lock(MahjongGame g,int seat,List<Integer> waits)throws Exception{boolean[] d=field(g,"declaredListening");d[seat]=true;boolean[] ip=field(g,"ippatsu");ip[seat]=true;List<List<Integer>> w=field(g,"listeningWaits");w.set(seat,waits);}
    private static void advanceToDraw(MahjongGame g,int seat){int steps=0;while(!(g.phase()==MahjongGame.Phase.DRAW&&g.currentTurn()==seat)&&steps++<40){if(g.phase()==MahjongGame.Phase.DRAW)g.draw(g.currentTurn());else if(g.phase()==MahjongGame.Phase.DISCARD)g.discard(g.currentTurn(),g.lastDraw());else passAll(g);}check(g.phase()==MahjongGame.Phase.DRAW&&g.currentTurn()==seat,"Reached own draw");}
    private static void passAll(MahjongGame g){int limit=0;while((g.phase()==MahjongGame.Phase.CLAIM||g.phase()==MahjongGame.Phase.KAN_CLAIM)&&limit++<10)for(int s=0;s<4;s++)if(g.validActions(s).contains(MahjongGame.Action.PASS))g.claim(s,MahjongGame.Action.PASS,-1);}
    private static void conserved(MahjongGame g)throws Exception{int[] count=new int[34];for(int s=0;s<4;s++){for(int t:g.hands(s))count[t]++;for(int t:g.discards(s))count[t]++;for(Meld m:g.melds(s))for(int t:m.tiles())count[t]++;check(g.flowers(s).isEmpty(),"No Japanese flowers");}List<Integer> wall=field(g,"wall"),dead=field(g,"deadWall");int head=field(g,"head"),tail=field(g,"tail");for(int i=head;i<=tail;i++)count[wall.get(i)]++;for(int t:dead)count[t]++;check(dead.size()==14,"Dead wall stays fourteen");for(int n:count)check(n==4,"Exactly four physical copies");}
    private static MahjongGame copy(MahjongGame g)throws Exception{ByteArrayOutputStream b=new ByteArrayOutputStream();try(ObjectOutputStream o=new ObjectOutputStream(b)){o.writeObject(g);}try(ObjectInputStream in=new ObjectInputStream(new ByteArrayInputStream(b.toByteArray()))){return(MahjongGame)in.readObject();}}
    @SuppressWarnings("unchecked")private static<T>T field(Object o,String name)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return(T)f.get(o);}
    private static void put(Object o,String name,Object value)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);f.set(o,value);}
    private static List<Integer> h(Integer... tiles){return List.of(tiles);}
    private static List<Integer> plus(List<Integer> a,int tile){List<Integer> r=new ArrayList<>(a);r.add(tile);return r;}
    private static List<Integer> concat(List<Integer>a,List<Integer>b){List<Integer>r=new ArrayList<>(a);r.addAll(b);return r;}
    private static void fails(Runnable r){checks++;try{r.run();}catch(IllegalArgumentException expected){return;}throw new AssertionError("Expected rejection");}
    private static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
}
