package tw.minecraft.mahjong.engine;

import java.io.*;
import java.lang.reflect.Field;
import java.util.*;

/** Dependency-free executable regression suite. java ... tw.minecraft.mahjong.engine.EngineTest */
public final class EngineTest {
    private static int assertions;
    private static final MahjongRules RULES = MahjongRules.defaults();
    public static void main(String[] args) throws Exception {
        structure(); scoring(); dealing(); invalidAndSerialization(); priorities(); multiron(); kans(); flowers(); passRonAndWall(); openingAndPayments(); randomGames(); scoreBound(); practiceBots();
        System.out.println("PASS TaiwanMahjong engine: " + assertions + " assertions");
    }
    private static void structure() {
        List<Integer> normal = h(0,1,2,3,4,5,9,10,11,18,19,20,31,31,31,27,27);
        check(HandAnalyzer.canWin(normal, List.of()), "Taiwan17 standard win");
        check(!HandAnalyzer.canWin(normal.subList(0,14), List.of()), "Japanese14 rejected");
        check(!HandAnalyzer.canWin(h(0,0,1,1,2,2,9,9,10,10,18,18,27,27,28,28,31), List.of()), "eightpairs+single not automatic Taiwan win");
        check(!HandAnalyzer.canWin(h(0,0,0,0,0,9,10,11,18,19,20,31,31,31,27,27,27), List.of()), "fifthcopy rejected");
        List<Integer> ambiguous = h(0,0,0,1,1,1,2,2,2,3,3,3,4,4,4,5,5);
        check(HandAnalyzer.winningDecompositions(ambiguous,List.of()).size() > 1, "enumerates ambiguous decompositions");
        List<Meld> quads = List.of(q(0,false),q(9,false),q(18,false),q(31,false),q(32,false));
        check(HandAnalyzer.canWin(h(27,27),quads),"five declared quads + pair");
        check(!HandAnalyzer.canWin(h(0,0),quads),"quad + pair fifthcopy rejected");
        check(HandAnalyzer.waits(h(0,1,2,3,4,5,9,10,11,18,19,20,23,24,13,13),List.of()).containsAll(List.of(22,25)),"two-sided waits");
    }
    private static void scoring() {
        List<Integer> pinfu=h(0,1,2,3,4,5,9,10,11,18,19,20,23,24,25,13,13);
        Score ron=score(pinfu,25,false,List.of());
        check(ron.tai()==3 && has(ron,"all_sequences") && has(ron,"closed"),"pinfu closed ron3");
        Score tsumo=score(pinfu,25,true,List.of());
        check(tsumo.tai()==3 && has(tsumo,"closed_self_draw") && !has(tsumo,"self_draw") && !has(tsumo,"all_sequences"),"one-mo-three replaces closed+selfdraw; selfdrawnotpinfu");
        List<Integer> triplets=h(0,0,0,9,9,9,18,18,18,31,31,31,32,32,32,27,27);
        Score five=score(triplets,27,true,List.of());
        check(has(five,"five_concealed") && !has(five,"four_concealed") && has(five,"all_triplets"),"fiveconcealedexclusive plus toitoi");
        Score four=score(triplets,32,false,List.of());
        check(has(four,"four_concealed")&&!has(four,"five_concealed"),"rontriplet notconcealed");
        List<Integer> dragons=h(31,31,31,32,32,32,33,33,33,27,27,27,0,0,0,28,28);
        Score big=score(dragons,28,true,List.of());
        check(has(big,"big_dragons")&&!has(big,"dragon"),"bigdragons suppresscomponentdragons");
        List<Integer> winds=h(27,27,27,28,28,28,29,29,29,30,30,30,31,31,31,32,32);
        Score bigwinds=score(winds,32,true,List.of());
        check(has(bigwinds,"big_winds")&&has(bigwinds,"all_honors")&&!has(bigwinds,"seat_wind")&&!has(bigwinds,"round_wind"),"bigwinds honors components");
        List<Meld> open=List.of(new Meld(Meld.Kind.SEQUENCE,0,true,1),new Meld(Meld.Kind.SEQUENCE,3,true,1),new Meld(Meld.Kind.SEQUENCE,9,true,1),new Meld(Meld.Kind.SEQUENCE,18,true,1),new Meld(Meld.Kind.TRIPLET,31,true,1));
        Score exposed=HandScorer.score(h(27,27),open,List.of(),context(27,false),RULES);
        check(has(exposed,"all_exposed")&&!has(exposed,"single_wait")&&!has(exposed,"closed"),"allrequest suppressuniqueWait");
        Score flower=HandScorer.score(pinfu,List.of(),h(34,35,36,37,38),context(25,true),RULES);
        check(flower.breakdown().stream().filter(t->t.key().equals("seat_flower")).count()==2&&has(flower,"flower_set"),"seatflowers+oneflowerset");
    }
    private static void dealing() throws Exception {
        for(int seed=0;seed<500;seed++) {
            MahjongGame g=new MahjongGame(seed,seed%4,(seed/4)%4,0);
            for(int s=0;s<4;s++) {check(g.hands(s).size()==(s==g.dealer()?17:16),"deal16+17");check(g.hands(s).stream().noneMatch(Tiles::flower),"flowersreplaced");}
            conserved(g); check(g.remainingWall()>=55&&g.remainingWall()<=63,"livewallreflectsflowers");
        }
    }
    private static void invalidAndSerialization() throws Exception {
        MahjongGame g=new MahjongGame(555,0,0,0); byte[] before=bytes(g);
        fails(()->g.discard(1,g.hands(1).get(0)),"wrongturn");check(Arrays.equals(before,bytes(g)),"wrongturnstateunchanged");
        fails(()->g.claim(1,MahjongGame.Action.RON,-1),"ronwithoutdiscard");check(Arrays.equals(before,bytes(g)),"badclaimstateunchanged");
        fails(()->g.discard(0,41),"cannotdiscardflower");check(Arrays.equals(before,bytes(g)),"invaliddiscardstateunchanged");
        MahjongGame copy=copy(g); check(g.hands(0).equals(copy.hands(0)),"serializationhands");
        for(int i=0;i<400&&!g.isFinished();i++) {
            advanceAuto(g); advanceAuto(copy);
            check(snapshot(g).equals(snapshot(copy)),"deterministicrestore");
        }
        check(g.isFinished(),"autogamefinishes");
    }
    private static void priorities() throws Exception {
        List<Integer> ready=h(0,1,6,7,8,9,10,11,18,19,20,31,31,31,27,27);
        MahjongGame g=fixture(Map.of(0,h(2),1,h(0,1),2,h(2,2),3,ready),Map.of(),Map.of(),0);
        g.discard(0,2);
        check(g.validActions(1).contains(MahjongGame.Action.CHI),"nextseatcan chi");
        check(!g.validActions(2).contains(MahjongGame.Action.CHI),"otherseatcannotchi");
        check(g.validActions(2).contains(MahjongGame.Action.PON),"ponavailable");
        check(g.validActions(3).contains(MahjongGame.Action.RON),"ronavailable");
        g.claim(1,MahjongGame.Action.CHI,0); g.claim(2,MahjongGame.Action.PON,-1);g.claim(3,MahjongGame.Action.RON,-1);
        check(g.isFinished()&&g.outcome().winners().get(0).seat()==3,"ronoverponoverchi");
        check(g.melds(1).isEmpty()&&g.melds(2).isEmpty(),"losingclaim no mutation");
        MahjongGame p=fixture(Map.of(0,h(2),1,h(0,1),2,h(2,2)),Map.of(),Map.of(),0);
        p.discard(0,2);p.claim(1,MahjongGame.Action.CHI,0);p.claim(2,MahjongGame.Action.PON,-1);passOthers(p);
        check(p.currentTurn()==2&&p.phase()==MahjongGame.Phase.DISCARD&&p.melds(2).size()==1,"ponbeatschi");conserved(p);
        fails(()->p.tsumo(2),"ponnotselfdraw");
        MahjongGame c=fixture(Map.of(0,h(2),1,h(0,1)),Map.of(),Map.of(),0);
        c.discard(0,2);c.claim(1,MahjongGame.Action.CHI,0);passOthers(c);
        check(c.currentTurn()==1&&c.melds(1).get(0).kind()==Meld.Kind.SEQUENCE,"chiacceptedafterpriority");conserved(c);
    }
    private static void multiron() throws Exception {
        Map<Integer,List<Integer>> hands=new HashMap<>();hands.put(0,h(2));
        for(int s=1;s<4;s++) hands.put(s,h(0,1,3,4,5,9,10,11,18,19,20,30+s,30+s,30+s,26+s,26+s));
        MahjongGame g=fixture(hands,Map.of(),Map.of(),0);g.discard(0,2);
        for(int s=1;s<4;s++) {check(g.validActions(s).contains(MahjongGame.Action.RON),"multironeligible");g.claim(s,MahjongGame.Action.RON,-1);}
        check(g.outcome().winners().size()==3&&g.outcome().payments().size()==3,"one discardthreewinners");
        long loss=0;
        for(MahjongGame.Payment p:g.outcome().payments()) {check(p.fromSeat()==0&&p.toSeat()!=0,"discarderonlypays");check(p.diamonds()==5+2L*p.tai(),"base5tai2");loss+=p.diamonds();}
        check(loss<=RULES.maximumLossDiamonds(0),"triplestakebound");
    }
    private static void kans() throws Exception {
        MahjongGame concealed=fixture(Map.of(0,h(0,0,0,0)),Map.of(),Map.of(),0);
        int before=concealed.remainingWall();concealed.concealedKan(0,0);
        check(concealed.melds(0).get(0).kind()==Meld.Kind.QUAD&&!concealed.melds(0).get(0).open(),"concealedquad");
        check(concealed.hands(0).size()==14&&concealed.remainingWall()<before,"kanreplacement");conserved(concealed);
        List<Integer> wait=h(1,2,6,7,8,9,10,11,18,19,20,31,31,31,27,27);
        MahjongGame rob=fixture(Map.of(0,h(0),1,wait),Map.of(0,List.of(new Meld(Meld.Kind.TRIPLET,0,true,2))),Map.of(),0);
        rob.addedKan(0,0);check(rob.phase()==MahjongGame.Phase.KAN_CLAIM,"addedkanopensrobwindow");
        check(rob.validActions(1).contains(MahjongGame.Action.RON)&&!rob.validActions(1).contains(MahjongGame.Action.PON),"robwindowrononly");
        rob.claim(1,MahjongGame.Action.RON,-1);passOthers(rob);
        check(rob.isFinished()&&has(rob.outcome().winners().get(0).score(),"rob_kong"),"robkanawards");
        check(rob.melds(0).get(0).kind()==Meld.Kind.TRIPLET&&!rob.hands(0).contains(0),"robkanremovesonlyfourth");
        MahjongGame added=fixture(Map.of(0,h(0)),Map.of(0,List.of(new Meld(Meld.Kind.TRIPLET,0,true,2))),Map.of(),0);
        added.addedKan(0,0);passOthers(added);
        check(!added.isFinished()&&added.melds(0).get(0).kind()==Meld.Kind.QUAD&&added.hands(0).size()==14,"addedkanreplacement");conserved(added);
        MahjongGame open=fixture(Map.of(0,h(0),1,h(0,0,0)),Map.of(),Map.of(),0);
        open.discard(0,0);open.claim(1,MahjongGame.Action.KAN,-1);passOthers(open);
        check(open.currentTurn()==1&&open.melds(1).get(0).kind()==Meld.Kind.QUAD&&open.hands(1).size()==14,"openkanreplacement");conserved(open);
    }
    private static void flowers() throws Exception {
        MahjongGame seven=fixture(Map.of(),Map.of(),Map.of(0,h(34,35,36,37,38,39,40)),0);
        placeHead(seven,41);seven.discard(0,seven.hands(0).get(0));passOthers(seven);
        check(seven.isFinished()&&seven.outcome().winners().get(0).seat()==0&&seven.outcome().sourceSeat()==1,"sevenrobsone");
        check(seven.outcome().payments().size()==1&&seven.outcome().winners().get(0).score().tai()==8,"sevenflowerpayer");
        MahjongGame eight=fixture(Map.of(),Map.of(),Map.of(0,h(34,35,36,37,38,39,40)),3);
        placeHead(eight,41);eight.discard(3,eight.hands(3).get(0));passOthers(eight);
        check(eight.isFinished()&&eight.outcome().selfDraw()&&eight.outcome().payments().size()==3&&eight.outcome().winners().get(0).score().tai()==8,"eightflowersallpay");
    }
    private static void passRonAndWall() throws Exception {
        List<Integer> ready=h(0,1,6,7,8,9,10,11,18,19,20,31,31,31,27,27);
        MahjongGame g=fixture(Map.of(0,h(2),1,h(2),2,ready),Map.of(),Map.of(),0);
        g.discard(0,2);g.claim(2,MahjongGame.Action.PASS,-1);passOthers(g);
        check(g.currentTurn()==1&&((boolean[])field(g,"passedRon"))[2],"passronblocksuntildraw");
        g.discard(1,2);check(g.phase()==MahjongGame.Phase.CLAIM&&!g.validActions(2).contains(MahjongGame.Action.RON),"passroncannotronnextdiscard");
        check(g.validActions(2).contains(MahjongGame.Action.CHI),"passronstillallowschi");
        g.claim(2,MahjongGame.Action.PASS,-1);passOthers(g);
        check(g.currentTurn()==2&&!((boolean[])field(g,"passedRon"))[2],"owndrawclearspassron");
        MahjongGame end=fixture(Map.of(),Map.of(),Map.of(),0);
        List<Integer> wall=field(end,"wall");int regular=wall.stream().filter(t->t<34).findFirst().orElseThrow();placeHead(end,regular);
        put(end,"tail",RULES.reserveTiles());end.discard(0,end.hands(0).get(0));passOthers(end);
        check(!end.isFinished()&&end.remainingWall()==0&&end.currentTurn()==1,"lastlivetiledrawallowed");
        check(end.concealedKanOptions(1).isEmpty()&&end.addedKanOptions(1).isEmpty(),"nokanwithoutreplacement");
        end.discard(1,end.lastDraw());passOthers(end);
        check(end.isFinished()&&end.outcome().draw()&&end.outcome().payments().isEmpty(),"reservewallendsdrawnhandnopayment");
    }
    private static void openingAndPayments() throws Exception {
        List<Integer> win=h(0,1,2,3,4,5,9,10,11,18,19,20,23,24,25,13,13);
        MahjongGame heaven=fixture(Map.of(0,win),Map.of(),Map.of(),0);put(heaven,"totalDiscards",0);heaven.tsumo(0);
        Score hs=heaven.outcome().winners().get(0).score();
        check(has(hs,"heavenly")&&!has(hs,"closed")&&!has(hs,"self_draw")&&!has(hs,"closed_self_draw"),"heavenlyreplacesbasicclosurepoints");
        MahjongGame earth=fixture(Map.of(1,win),Map.of(),Map.of(),1);int[] draws=field(earth,"drawsTaken"),discards=field(earth,"discardCounts");draws[1]=1;discards[1]=0;earth.tsumo(1);
        Score es=earth.outcome().winners().get(0).score();check(has(es,"earthly")&&!has(es,"closed_self_draw"),"earthlyfirstuninterruptedselfdraw");
        for(MahjongGame.Payment p:earth.outcome().payments())check(p.tai()==es.tai()+(p.fromSeat()==0?1:0),"dealerpaysbonusonlyownshare");
        MahjongGame ordinary=fixture(Map.of(2,win),Map.of(),Map.of(),2);ordinary.tsumo(2);
        check(ordinary.outcome().payments().size()==3,"tsumothreepayers");
        Score os=ordinary.outcome().winners().get(0).score();
        for(MahjongGame.Payment p:ordinary.outcome().payments())check(p.tai()==os.tai()+(p.fromSeat()==0?1:0),"nondealerwinnerdealerpayment");
        List<Integer> ready=h(0,1,3,4,5,9,10,11,18,19,20,31,31,31,27,27);
        MahjongGame human=fixture(Map.of(0,h(2),1,ready),Map.of(),Map.of(),0);put(human,"totalDiscards",0);human.discard(0,2);human.claim(1,MahjongGame.Action.RON,-1);passOthers(human);
        Score humanScore=human.outcome().winners().get(0).score();
        check(has(humanScore,"human")&&!has(humanScore,"closed"),"humanonlydealerfirstdiscardreplacesclosure");
    }
    private static void randomGames() throws Exception {
        Random r=new Random(17825);
        for(int seed=0;seed<250;seed++) {
            MahjongGame g=new MahjongGame(seed*1729L,seed%4,0,seed%3);int step=0;
            while(!g.isFinished()&&step++<1200) {
                if(g.phase()==MahjongGame.Phase.DISCARD) {
                    int s=g.currentTurn();var options=g.validActions(s);
                    if(options.contains(MahjongGame.Action.TSUMO))g.tsumo(s);
                    else if(!g.concealedKanOptions(s).isEmpty()&&r.nextBoolean())g.concealedKan(s,g.concealedKanOptions(s).get(0));
                    else if(!g.addedKanOptions(s).isEmpty()&&r.nextBoolean())g.addedKan(s,g.addedKanOptions(s).get(0));
                    else g.discard(s,g.hands(s).get(r.nextInt(g.hands(s).size())));
                } else {
                    int s=-1;for(int t=0;t<4;t++)if(!g.validActions(t).isEmpty()){s=t;break;}
                    check(s>=0,"claimphasehaspendingactor");var choices=g.validActions(s);MahjongGame.Action a;
                    if(choices.contains(MahjongGame.Action.RON))a=MahjongGame.Action.RON;
                    else if(choices.contains(MahjongGame.Action.KAN)&&r.nextBoolean())a=MahjongGame.Action.KAN;
                    else if(choices.contains(MahjongGame.Action.PON)&&r.nextBoolean())a=MahjongGame.Action.PON;
                    else if(choices.contains(MahjongGame.Action.CHI)&&r.nextBoolean())a=MahjongGame.Action.CHI;
                    else a=MahjongGame.Action.PASS;
                    g.claim(s,a,a==MahjongGame.Action.CHI?g.chiOptions(s).get(0):-1);
                }
                if(!g.isFinished()) {
                    conserved(g);
                    for(int s=0;s<4;s++)check(g.hands(s).size()==16-3*g.melds(s).size()+((g.phase()==MahjongGame.Phase.DISCARD||g.phase()==MahjongGame.Phase.KAN_CLAIM)&&g.currentTurn()==s?1:0),"handcountphaseinvariant");
                }
                if(step==20)g=copy(g);
            }
            check(g.isFinished(),"randomgameboundedfinish");
            long[] losses=new long[4];
            for(MahjongGame.Payment p:g.outcome().payments())losses[p.fromSeat()]+=p.diamonds();
            for(long loss:losses)check(loss<=RULES.maximumLossDiamonds(g.dealerStreak()),"randomsettlementfunded");
        }
    }
    private static void scoreBound() {
        Random random=new Random(9311);int checked=0;
        while(checked<10000) {
            List<Integer> hand=new ArrayList<>();for(int n=0;n<5;n++) {
                int t=random.nextInt(34);
                if(t<27&&t%9<=6&&random.nextBoolean())hand.addAll(h(t,t+1,t+2));else hand.addAll(h(t,t,t));
            }
            int p=random.nextInt(34);hand.add(p);hand.add(p);
            if(!HandAnalyzer.canWin(hand,List.of()))continue;
            int win=hand.get(random.nextInt(hand.size()));boolean self=random.nextBoolean();
            HandScorer.Context context=new HandScorer.Context(win,self,random.nextInt(4),random.nextInt(4),false,false,false,self&&random.nextBoolean(),!self&&random.nextBoolean(),self&&random.nextBoolean(),!self&&random.nextBoolean());
            List<Integer> flowers=new ArrayList<>();for(int f=34;f<41;f++)if(random.nextBoolean())flowers.add(f);
            Score score=HandScorer.score(hand,List.of(),flowers,context,RULES);
            check(score!=null&&score.tai()+1<=RULES.maximumTai(0),"scorebound");checked++;
        }
        check(RULES.maximumTai(0)==71,"documentedbound71");
        check(RULES.maximumLossDiamonds(0)==441,"documentedescrow441");
    }
    private static void practiceBots() throws Exception {
        List<Integer> sample=h(0,1,2,3,4,5,9,10,11,18,19,20,23,24,13,13,30);
        check(PracticeBot.chooseDiscard(sample,List.of(),24)==30,"practicebotdropsisolatedhonornotnewconnectedtile");
        int wins=0;
        for(int seed=0;seed<16;seed++) {
            MahjongGame game=new MahjongGame(8800+seed,0,0,0), duplicate=copy(game);int actions=0;
            while(!game.isFinished()&&actions++<600) {
                int actor=game.currentTurn();
                if(game.phase()!=MahjongGame.Phase.DISCARD)for(int s=0;s<4;s++)if(!game.validActions(s).isEmpty()){actor=s;break;}
                PracticeBot.act(game,actor);PracticeBot.act(duplicate,actor);
                check(snapshot(game).equals(snapshot(duplicate)),"practicebotdeterministiclegalactions");
                if(!game.isFinished()&&actions%20==0)conserved(game);
            }
            check(game.isFinished(),"practicebotgameends");if(!game.outcome().draw())wins++;
        }
        check(wins>0,"practicebotscancompletewinninghands");
    }
    private static MahjongGame fixture(Map<Integer,List<Integer>> requested,Map<Integer,List<Meld>> declared,Map<Integer,List<Integer>> bloom,int turn) throws Exception {
        MahjongGame g=new MahjongGame(7,0,0,0); List<List<Integer>> hands=field(g,"hands"), flowers=field(g,"flowers"), discards=field(g,"discards");List<List<Meld>> melds=field(g,"melds");
        List<Integer> deck=new ArrayList<>();for(int t=0;t<34;t++)for(int n=0;n<4;n++)deck.add(t);for(int t=34;t<42;t++)deck.add(t);
        for(int s=0;s<4;s++) {hands.get(s).clear();flowers.get(s).clear();discards.get(s).clear();melds.get(s).clear();
            for(int t:requested.getOrDefault(s,List.of())){check(deck.remove(Integer.valueOf(t)),"fixturephysicaltiles");hands.get(s).add(t);}
            for(int t:bloom.getOrDefault(s,List.of())){check(deck.remove(Integer.valueOf(t)),"fixtureflowers");flowers.get(s).add(t);}
            for(Meld m:declared.getOrDefault(s,List.of())){for(int t:m.tiles())check(deck.remove(Integer.valueOf(t)),"fixturemeldphysical");melds.get(s).add(m);}
        }
        Collections.shuffle(deck,new Random(101));
        for(int s=0;s<4;s++) {int expected=16-3*melds.get(s).size()+(s==turn?1:0);while(hands.get(s).size()<expected){int index=0;while(Tiles.flower(deck.get(index)))index++;hands.get(s).add(deck.remove(index));}Collections.sort(hands.get(s));}
        List<Integer> wall=field(g,"wall");wall.clear();wall.addAll(deck);put(g,"head",0);put(g,"tail",deck.size()-1);put(g,"currentTurn",turn);put(g,"lastDraw",hands.get(turn).get(hands.get(turn).size()-1));put(g,"phase",MahjongGame.Phase.DISCARD);put(g,"outcome",null);put(g,"totalDiscards",10);put(g,"callsOccurred",false);put(g,"lastDiscarder",-1);put(g,"lastTile",-1);
        int[] draws=field(g,"drawsTaken"), counts=field(g,"discardCounts");Arrays.fill(draws,3);Arrays.fill(counts,2);
        Field kind=MahjongGame.class.getDeclaredField("drawKind");kind.setAccessible(true);kind.set(g,Enum.valueOf((Class)kind.getType(),"NORMAL"));
        return g;
    }
    private static void placeHead(MahjongGame g,int tile) throws Exception {List<Integer>wall=field(g,"wall");Collections.swap(wall,(Integer)field(g,"head"),wall.indexOf(tile));}
    private static void passOthers(MahjongGame g) {
        int watchdog=0;
        while((g.phase()==MahjongGame.Phase.CLAIM||g.phase()==MahjongGame.Phase.KAN_CLAIM)&&watchdog++<10)
            for(int s=0;s<4;s++)if(g.validActions(s).contains(MahjongGame.Action.PASS))g.claim(s,MahjongGame.Action.PASS,-1);
    }
    private static void advanceAuto(MahjongGame g) {
        if(g.phase()==MahjongGame.Phase.DISCARD)g.autoAction(g.currentTurn());
        else for(int s=0;s<4;s++)if(!g.validActions(s).isEmpty()){g.autoAction(s);break;}
    }
    private static void conserved(MahjongGame g) throws Exception {
        int[] counts=new int[42];for(int s=0;s<4;s++){for(int t:g.hands(s))counts[t]++;for(int t:g.flowers(s))counts[t]++;for(int t:g.discards(s))counts[t]++;for(Meld m:g.melds(s))for(int t:m.tiles())counts[t]++;}
        List<Integer>wall=field(g,"wall");int head=field(g,"head"),tail=field(g,"tail");for(int i=head;i<=tail;i++)counts[wall.get(i)]++;
        for(int t=0;t<42;t++)check(counts[t]==(t<34?4:1),"tileconservation"+t+"="+counts[t]);
    }
    private static Score score(List<Integer> hand,int tile,boolean self,List<Meld> declared){return HandScorer.score(hand,declared,List.of(),context(tile,self),RULES);}
    private static HandScorer.Context context(int tile,boolean self){return new HandScorer.Context(tile,self,0,0,false,false,false,false,false,false,false);}
    private static boolean has(Score score,String key){return score!=null&&score.breakdown().stream().anyMatch(t->t.key().equals(key));}
    private static Meld q(int tile,boolean open){return new Meld(Meld.Kind.QUAD,tile,open,1);}
    private static List<Integer> h(Integer...tiles){return List.of(tiles);}
    private static byte[] bytes(Object o)throws Exception{ByteArrayOutputStream b=new ByteArrayOutputStream();try(ObjectOutputStream stream=new ObjectOutputStream(b)){stream.writeObject(o);}return b.toByteArray();}
    private static String snapshot(MahjongGame g)throws Exception{StringBuilder b=new StringBuilder().append(g.phase()).append(g.currentTurn()).append(g.lastDraw()).append(g.lastTile()).append(g.lastDiscarder()).append(g.remainingWall()).append(g.outcome());for(int s=0;s<4;s++)b.append(g.hands(s)).append(g.flowers(s)).append(g.discards(s)).append(g.melds(s)).append(g.validActions(s));b.append((Object)field(g,"wall")).append((Object)field(g,"head")).append((Object)field(g,"tail"));return b.toString();}
    private static MahjongGame copy(MahjongGame g)throws Exception{try(ObjectInputStream stream=new ObjectInputStream(new ByteArrayInputStream(bytes(g)))){return(MahjongGame)stream.readObject();}}
    private static <T>T field(Object o,String name)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return(T)f.get(o);}
    private static void put(Object o,String name,Object value)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);f.set(o,value);}
    private static void fails(Runnable r,String message){try{r.run();throw new AssertionError("expected rejection:"+message);}catch(IllegalArgumentException expected){assertions++;}}
    private static void check(boolean condition,String message){assertions++;if(!condition)throw new AssertionError(message);}
}
