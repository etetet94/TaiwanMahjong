package tw.minecraft.mahjong.engine;

import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import tw.minecraft.mahjong.view.TableGeometry;

/** Exercises actual turns, physical tile fixtures, scoring placements, and old saved games. */
public final class HouseRulesTest {
    private static int checks;
    private static final List<Integer> READY = List.of(0,1,2,3,4,5,9,10,11,18,19,20,31,31,31,27);
    private static final List<Integer> OPEN_READY = List.of(9,10,11,18,19,20,31,31,31,27,27,27,28);
    public static void main(String[] args) throws Exception {
        geometry(); listening(); listeningKongs(); replacementWins(); scoring();
        for (String path : args) {
            if(path.endsWith("-kong.bin"))legacyKong(Files.newInputStream(Path.of(path)),path.endsWith("-open-kong.bin"));
            else legacy(Files.newInputStream(Path.of(path)));
        }
        System.out.println("HouseRulesTest PASS: " + checks + " assertions");
    }
    private static void geometry() {
        for (int seat=0;seat<4;seat++) {
            var own=TableGeometry.rotate(seat,0,1); var next=TableGeometry.rotate(seat+1,0,1);
            var previous=TableGeometry.rotate(seat-1,0,1); var across=TableGeometry.rotate(seat+2,0,1);
            var right=TableGeometry.rotate(seat,1,0);
            check(close(next.x(),right.x())&&close(next.z(),right.z()),"Downstream is on each player's right");
            check(close(previous.x(),-right.x())&&close(previous.z(),-right.z()),"Upstream is on each player's left");
            check(close(across.x(),-own.x())&&close(across.z(),-own.z()),"Opposite seat faces own seat");
            double yaw=Math.toRadians(TableGeometry.yaw(seat));
            check(close(-Math.sin(yaw),own.x())&&close(Math.cos(yaw),own.z()),"Tile front and labels face their owner");
            var first=TableGeometry.hand(seat,0,17);var last=TableGeometry.hand(seat,16,17);
            check((last.x()-first.x())*right.x()+(last.z()-first.z())*right.z()>0,"Hand slots and click positions run left to right");
        }
    }
    private static void listening() throws Exception {
        MahjongGame bot=fixture(Map.of(0,plus(READY,26)),Map.of(),0);PracticeBot.act(bot,0);
        check(!bot.declaredListening(0),"Practice bot never declares listening automatically");
        MahjongGame timeout=fixture(Map.of(0,plus(READY,26)),Map.of(),0);timeout.autoAction(0);
        check(!timeout.declaredListening(0),"Timeout never declares listening automatically");
        MahjongGame game=fixture(Map.of(0,plus(READY,26),1,List.of(31)),Map.of(),0);
        check(game.validActions(0).contains(MahjongGame.Action.LISTEN)&&game.listeningDiscards(0).contains(26),"Ready discard enables declaration");
        byte[] before=bytes(game);fails(()->game.declareListening(1,31));fails(()->game.declareListening(0,40));
        check(Arrays.equals(before,bytes(game)),"Rejected declaration leaves checkpoint unchanged");
        List<Integer> cached=game.listeningDiscards(0);check(cached==game.listeningDiscards(0),"Same hand reuses ready-discard calculation");
        game.declareListening(0,26);passAll(game);
        check(game.declaredListening(0)&&game.listeningWaits(0).equals(List.of(27)),"Declaration records one legal wait after discarding");
        check(game.phase()==MahjongGame.Phase.DRAW&&game.currentTurn()==1,"Declaration advances to downstream draw");
        game.draw(1);game.discard(1,31);
        check(!game.validActions(0).contains(MahjongGame.Action.PON)&&!game.validActions(0).contains(MahjongGame.Action.KAN),"Declared player cannot pong or exposed kong");
        passAll(game);advanceToDraw(game,0);placeHead(game,32);game.draw(0);
        before=bytes(game);fails(()->game.concealedKan(0,31));fails(()->game.discard(0,0));fails(()->game.declareListening(0,32));
        check(Arrays.equals(before,bytes(game)),"Locked hand rejects shape changes without mutation");
        MahjongGame restored=copy(game);check(restored.declaredListening(0)&&restored.listeningWaits(0).equals(List.of(27)),"Hand lock and waits survive restart");
        PracticeBot.act(restored,0);game.autoAction(0);check(game.hands(0).equals(READY.stream().sorted().toList())&&restored.hands(0).equals(game.hands(0)),"Timeout and replacement bot both discard drawn tile");
        MahjongGame closed=fixture(Map.of(0,plus(READY,26)),Map.of(),0);closed.declareListening(0,26);passAll(closed);advanceToDraw(closed,0);placeHead(closed,31);closed.draw(0);
        check(Collections.frequency(closed.hands(0),31)==4&&closed.concealedKanOptions(0).isEmpty(),"Declared hand cannot closed kong even with four identical tiles");fails(()->closed.concealedKan(0,31));
        MahjongGame win=fixture(Map.of(0,plus(READY,26)),Map.of(),0);win.declareListening(0,26);passAll(win);advanceToDraw(win,0);placeHead(win,27);win.draw(0);
        check(win.validActions(0).contains(MahjongGame.Action.TSUMO),"Declared listening still permits own winning draw");win.tsumo(0);
        check(win.outcome().selfDraw(),"Declared ready hand can finish by self draw");
        MahjongGame upstream=fixture(Map.of(3,plus(READY,26)),Map.of(),3);upstream.declareListening(3,26);passAll(upstream);
        advanceToDraw(upstream,2);upstream.draw(2);
        if (!upstream.hands(2).contains(2)) { // Move a real wall tile into the drawn slot for this explicit upstream discard.
            List<Integer> hand=field(upstream,"hands",2);List<Integer> wall=field(upstream,"wall");int old=upstream.lastDraw();int index=wall.indexOf(2);
            check(index>=0,"Fixture has physical upstream tile");hand.remove(Integer.valueOf(old));hand.add(2);Collections.sort(hand);wall.set(index,old);put(upstream,"lastDraw",2);
        }
        upstream.discard(2,2);check(upstream.chiOptions(3).isEmpty(),"Declared hand cannot chi upstream discard");
    }
    private static void listeningKongs() throws Exception {
        Meld pon=new Meld(Meld.Kind.TRIPLET,0,true,2);
        MahjongGame game=fixture(Map.of(0,plus(OPEN_READY,26)),Map.of(0,List.of(pon)),0);
        game.declareListening(0,26);passAll(game);advanceToDraw(game,0);placeHead(game,0);game.draw(0);
        check(game.addedKanOptions(0).equals(List.of(0)),"Declared hand may add only drawn fourth tile while keeping waits");
        placeTail(game,28,0);game.addedKan(0,0);passAll(game);
        check(game.melds(0).getFirst().kind()==Meld.Kind.QUAD&&game.listeningWaits(0).equals(List.of(28)),"Added kong preserves declared ready shape");
        check(game.validActions(0).contains(MahjongGame.Action.TSUMO),"Added kong supplement can self draw");game.tsumo(0);
        check(has(game.outcome().winners().getFirst().score(),"replacement_draw"),"Added kong self draw awards replacement tai");
    }
    private static void replacementWins() throws Exception {
        for(boolean flower:List.of(false,true)) {
            MahjongGame open=fixture(Map.of(0,List.of(0),1,concat(List.of(0,0,0),OPEN_READY)),Map.of(),0);
            placeTail(open,28,flower?1:0);if(flower)placeTail(open,34,0);
            open.discard(0,0);open.claim(1,MahjongGame.Action.KAN,-1);passAll(open);
            check(HandAnalyzer.canWin(open.hands(1),open.melds(1)),"Exposed kong supplement fixture has winning shape");
            check(!open.validActions(1).contains(MahjongGame.Action.TSUMO),"Exposed kong supplement cannot self draw, including flower chain");
            byte[] before=bytes(open);fails(()->open.tsumo(1));check(Arrays.equals(before,bytes(open)),"Forbidden self draw does not finish game");
            MahjongGame restored=copy(open);check(!restored.validActions(1).contains(MahjongGame.Action.TSUMO),"Exposed kong restriction survives restart");
            if(flower)check(open.flowers(1).contains(34),"Exposed kong chained flower was replaced");
        }
        MahjongGame closed=fixture(Map.of(0,concat(List.of(0,0,0,0),OPEN_READY)),Map.of(),0);placeTail(closed,28,0);closed.concealedKan(0,0);closed.tsumo(0);
        Score closedScore=closed.outcome().winners().getFirst().score();
        check(has(closedScore,"replacement_draw")&&has(closedScore,"closed_self_draw"),"Closed kong allows replacement self draw and remains menqing");
        for(boolean flower:List.of(false,true)) {
            MahjongGame sea=fixture(Map.of(0,List.of(26),1,READY),Map.of(),0);
            put(sea,"tail",MahjongRules.defaults().reserveTiles());
            if(flower){placeHead(sea,34);placeTail(sea,27,0);}else placeHead(sea,27);
            sea.discard(0,26);passAll(sea);sea.draw(1);sea.tsumo(1);
            Score score=sea.outcome().winners().getFirst().score();
            check(has(score,"last_draw")&&has(score,"replacement_draw"),"Last live normal or flower replacement draw stacks sea-bottom and kong-flower tai");
        }
    }
    private static void scoring() {
        for(int wind=0;wind<4;wind++) {
            var ctx=context(27,false,wind);Score score=HandScorer.score(plus(READY,27),List.of(),List.of(34,35,36,37,38,39,40,41),ctx,MahjongRules.defaults());
            List<Score.Tai> flowers=score.breakdown().stream().filter(p->p.key().equals("seat_flower")).toList();
            String names=flowers.toString();check(flowers.size()==2,"Each seat has exactly two matching flowers");
            if(wind==2)check(names.contains("秋")&&names.contains("菊")&&!names.contains("竹"),"West matches autumn and chrysanthemum");
            if(wind==3)check(names.contains("冬")&&names.contains("竹")&&!names.contains("菊"),"North matches winter and bamboo");
        }
        List<Integer> tripletOnly=List.of(3,4,5,3,4,5,9,10,11,18,19,20,27,27,27,4,4);
        List<Integer> before=new ArrayList<>(tripletOnly);before.remove(Integer.valueOf(27));
        check(HandAnalyzer.waits(before,List.of()).equals(List.of(27)),"Pair-pair fixture has one physically legal winning type");
        check(!has(score(tripletOnly,27),"single_wait"),"One surviving triplet wait is not a middle, edge or pair wait");
        check(has(score(plus(READY,27),27),"single_wait"),"Single pair wait earns one tai");
        List<Integer> edge=List.of(0,1,2,6,7,8,9,10,11,18,19,20,31,31,31,27,27);
        check(has(score(edge,2),"single_wait"),"Only winning type on edge wait earns one tai");
        check(has(score(edge,1),"single_wait"),"Only winning type on middle wait earns one tai");
        Score honor=HandScorer.score(plus(OPEN_READY,28),List.of(new Meld(Meld.Kind.QUAD,0,false,0)),List.of(),context(28,false,0),MahjongRules.defaults());
        check(has(honor,"dragon")&&has(honor,"seat_wind")&&has(honor,"round_wind")&&has(honor,"closed"),"Dragon, seat wind, round wind and concealed kong menqing score");
    }
    public static void legacy(InputStream source) throws Exception {
        MahjongGame game;try(ObjectInputStream in=new ObjectInputStream(source)){game=(MahjongGame)in.readObject();}
        check(game.manualDraw()&&game.dealerStreak()==2,"Actual version 1.1.3 checkpoint fields restored");
        for(int seat=0;seat<4;seat++)check(!game.declaredListening(seat)&&game.listeningWaits(seat).isEmpty(),"Old hand migrates without a listening lock");
        check(game.phase()==MahjongGame.Phase.DRAW||game.phase()==MahjongGame.Phase.CLAIM,"Old saved turn boundary restored");
        int steps=0;while(!game.isFinished()&&steps++<1000){if(game.phase()==MahjongGame.Phase.DRAW||game.phase()==MahjongGame.Phase.DISCARD)game.autoAction(game.currentTurn());else passAll(game);}
        check(game.isFinished(),"Old saved game continues to a complete outcome");
    }
    public static void legacyKong(InputStream source,boolean exposed) throws Exception {
        MahjongGame game;try(ObjectInputStream in=new ObjectInputStream(source)){game=(MahjongGame)in.readObject();}
        check(game.phase()==MahjongGame.Phase.DISCARD&&game.lastDraw()>=0,"Actual old kong checkpoint restores supplement turn");
        check(field(game,"drawKind").toString().equals(exposed?"OPEN_KAN":"KAN"),"Only old exposed kong checkpoint migrates to no-self-draw kind");
        if(exposed)check(!game.validActions(game.currentTurn()).contains(MahjongGame.Action.TSUMO),"Old exposed kong cannot bypass house restriction on restart");
        for(int seat=0;seat<4;seat++)check(!game.declaredListening(seat),"Old kong checkpoint has no automatic declaration");
        int steps=0;while(!game.isFinished()&&steps++<1000){if(game.phase()==MahjongGame.Phase.DRAW||game.phase()==MahjongGame.Phase.DISCARD)game.autoAction(game.currentTurn());else passAll(game);}
        check(game.isFinished(),"Actual old kong checkpoint continues to completion");
    }
    private static void advanceToDraw(MahjongGame game,int seat){int steps=0;while(!(game.phase()==MahjongGame.Phase.DRAW&&game.currentTurn()==seat)&&steps++<40){
        if(game.phase()==MahjongGame.Phase.DRAW)game.draw(game.currentTurn());else if(game.phase()==MahjongGame.Phase.DISCARD)game.discard(game.currentTurn(),game.lastDraw());else passAll(game);}
        check(game.phase()==MahjongGame.Phase.DRAW&&game.currentTurn()==seat,"Advanced through full counterclockwise turn sequence");}
    private static MahjongGame fixture(Map<Integer,List<Integer>> hands,Map<Integer,List<Meld>> melds,int turn)throws Exception{Method method=EngineTest.class.getDeclaredMethod("fixture",Map.class,Map.class,Map.class,int.class);method.setAccessible(true);MahjongGame game=(MahjongGame)method.invoke(null,hands,melds,Map.of(),turn);put(game,"manualDraw",true);return game;}
    private static void passAll(MahjongGame game){int loops=0;while((game.phase()==MahjongGame.Phase.CLAIM||game.phase()==MahjongGame.Phase.KAN_CLAIM)&&loops++<10)for(int seat=0;seat<4;seat++)if(game.validActions(seat).contains(MahjongGame.Action.PASS))game.claim(seat,MahjongGame.Action.PASS,-1);}
    private static void placeHead(MahjongGame game,int tile)throws Exception{List<Integer> wall=field(game,"wall");int head=field(game,"head");Collections.swap(wall,head,wallIndex(game,tile));}
    private static void placeTail(MahjongGame game,int tile,int offset)throws Exception{List<Integer> wall=field(game,"wall");int tail=field(game,"tail");Collections.swap(wall,tail-offset,wallIndex(game,tile));}
    private static int wallIndex(MahjongGame game,int tile)throws Exception{
        List<Integer> wall=field(game,"wall");int head=field(game,"head");int index=wall.subList(head,wall.size()).indexOf(tile);
        if(index>=0)return head+index;
        for(int seat=0;seat<4;seat++)if(seat!=game.currentTurn()){
            List<Integer> hand=field(game,"hands",seat);if(!hand.contains(tile))continue;
            int replacement=head;while(wall.get(replacement)>=34)replacement++;
            hand.remove(Integer.valueOf(tile));hand.add(wall.get(replacement));Collections.sort(hand);wall.set(replacement,tile);return replacement;
        }
        throw new AssertionError("Requested physical tile unavailable: "+tile);
    }
    private static HandScorer.Context context(int tile,boolean self,int seat){return new HandScorer.Context(tile,self,seat,0,false,false,false,false,false,false,false);}
    private static Score score(List<Integer> hand,int tile){return HandScorer.score(hand,List.of(),List.of(),context(tile,false,0),MahjongRules.defaults());}
    private static boolean has(Score score,String key){return score!=null&&score.breakdown().stream().anyMatch(p->p.key().equals(key));}
    private static List<Integer> plus(List<Integer> values,int tile){List<Integer> result=new ArrayList<>(values);result.add(tile);return result;}
    private static List<Integer> concat(List<Integer> a,List<Integer> b){List<Integer> result=new ArrayList<>(a);result.addAll(b);return result;}
    private static byte[] bytes(Object game)throws Exception{ByteArrayOutputStream result=new ByteArrayOutputStream();try(ObjectOutputStream out=new ObjectOutputStream(result)){out.writeObject(game);}return result.toByteArray();}
    private static MahjongGame copy(MahjongGame game)throws Exception{try(ObjectInputStream in=new ObjectInputStream(new ByteArrayInputStream(bytes(game)))){return(MahjongGame)in.readObject();}}
    @SuppressWarnings("unchecked")private static <T>T field(Object object,String name)throws Exception{Field field=object.getClass().getDeclaredField(name);field.setAccessible(true);return(T)field.get(object);}
    private static List<Integer> field(Object object,String name,int seat)throws Exception{List<List<Integer>> values=field(object,name);return values.get(seat);}
    private static void put(Object object,String name,Object value)throws Exception{Field field=object.getClass().getDeclaredField(name);field.setAccessible(true);field.set(object,value);}
    private static boolean close(double a,double b){return Math.abs(a-b)<1e-8;}
    private static void fails(Runnable action){checks++;try{action.run();}catch(IllegalArgumentException expected){return;}throw new AssertionError("Expected rejection");}
    private static void check(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
}
