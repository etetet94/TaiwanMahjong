package tw.minecraft.mahjong.engine;

import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;

/** Manual-turn boundaries, compatibility and physical-tile conservation. */
public final class ManualDrawTest {
    private static final MahjongRules RULES=MahjongRules.defaults();
    private static int checks;
    public static void main(String[] args)throws Exception {
        turnBoundaries();claims();supplementsAndWall();passedRon();randomGames();
        if(args.length>0)legacy(Path.of(args[0]));
        System.out.println("ManualDrawTest PASS: "+checks+" assertions; 120 manual randomized games"+(args.length>0?"; actual old-version serialized game restored":""));
    }
    private static void turnBoundaries()throws Exception {
        MahjongGame game=new MahjongGame(555,0,0,0,RULES,true);
        check(game.manualDraw()&&game.phase()==MahjongGame.Phase.DISCARD&&game.hands(0).size()==17,"Dealer starts with seventeen and discards first");
        fails(()->game.draw(0));
        int remaining=game.remainingWall(),discard=game.hands(0).get(0);
        game.discard(0,discard);passAll(game);
        check(game.phase()==MahjongGame.Phase.DRAW&&game.currentTurn()==1,"Unclaimed discard waits for next player's draw");
        check(game.remainingWall()==remaining&&game.hands(1).size()==16&&game.lastDraw()==-1,"Waiting draw consumes no tile");
        check(game.validActions(1).equals(EnumSet.of(MahjongGame.Action.DRAW)),"Only legal draw action shown");
        for(int s:new int[]{0,2,3})check(game.validActions(s).isEmpty(),"Other actors have no draw action");
        byte[] before=bytes(game);
        fails(()->game.draw(2));fails(()->game.discard(1,game.hands(1).get(0)));fails(()->game.tsumo(1));
        check(Arrays.equals(before,bytes(game)),"Wrong actor and pre-draw actions do not mutate state");
        MahjongGame restored=copy(game);check(restored.manualDraw()&&restored.phase()==MahjongGame.Phase.DRAW,"Manual draw checkpoint survives serialization");
        game.draw(1);restored.autoAction(1);
        check(snapshot(game).equals(snapshot(restored)),"Timeout performs exactly the same single draw");
        check(game.phase()==MahjongGame.Phase.DISCARD&&game.hands(1).size()==17&&game.discards(1).isEmpty(),"Draw does not immediately discard");
        byte[] after=bytes(game);fails(()->game.draw(1));check(Arrays.equals(after,bytes(game)),"Double draw rejected without extra tile");
        MahjongGame bot=copyFrom(before);String botBefore=snapshot(bot);PracticeBot.act(bot,0);check(botBefore.equals(snapshot(bot)),"Bot cannot draw out of turn");
        PracticeBot.act(bot,1);check(snapshot(game).equals(snapshot(bot)),"Practice bot handles draw phase");
        check(!new MahjongGame(555,0,0,0).manualDraw()&&!new MahjongGame(555,0,0,0,RULES).manualDraw(),"Existing constructors retain automatic mode");
    }
    private static void claims()throws Exception {
        List<Integer> ready=List.of(0,1,6,7,8,9,10,11,18,19,20,31,31,31,27,27);
        MahjongGame ron=fixture(Map.of(0,List.of(2),1,List.of(0,1),2,List.of(2,2),3,ready),Map.of(),Map.of(),0);
        ron.discard(0,2);fails(()->ron.draw(1));
        ron.claim(1,MahjongGame.Action.CHI,0);ron.claim(2,MahjongGame.Action.PON,-1);ron.claim(3,MahjongGame.Action.RON,-1);
        check(ron.isFinished()&&ron.outcome().winners().getFirst().seat()==3,"Manual draw does not change ron priority");
        check(ron.melds(1).isEmpty()&&ron.melds(2).isEmpty(),"Losing claims did not consume tiles");
        MahjongGame pon=fixture(Map.of(0,List.of(2),1,List.of(0,1),2,List.of(2,2)),Map.of(),Map.of(),0);
        int wall=pon.remainingWall();pon.discard(0,2);pon.claim(1,MahjongGame.Action.CHI,0);pon.claim(2,MahjongGame.Action.PON,-1);passAll(pon);
        check(pon.currentTurn()==2&&pon.phase()==MahjongGame.Phase.DISCARD&&pon.remainingWall()==wall,"Pon beats chi and proceeds directly to discard without drawing");
        fails(()->pon.draw(2));conserved(pon);
        MahjongGame chi=fixture(Map.of(0,List.of(2),1,List.of(0,1)),Map.of(),Map.of(),0);
        chi.discard(0,2);chi.claim(1,MahjongGame.Action.CHI,0);passAll(chi);
        check(chi.phase()==MahjongGame.Phase.DISCARD&&chi.currentTurn()==1&&chi.melds(1).size()==1,"Chi proceeds directly to discard");
        conserved(chi);
    }
    private static void supplementsAndWall()throws Exception {
        MahjongGame concealed=fixture(Map.of(0,List.of(0,0,0,0)),Map.of(),Map.of(),0);
        int remaining=concealed.remainingWall();concealed.concealedKan(0,0);
        check(concealed.phase()==MahjongGame.Phase.DISCARD&&concealed.lastDraw()>=0&&concealed.hands(0).size()==14&&concealed.remainingWall()<remaining,"Concealed kan supplement stays automatic");conserved(concealed);
        MahjongGame open=fixture(Map.of(0,List.of(0),1,List.of(0,0,0)),Map.of(),Map.of(),0);
        open.discard(0,0);open.claim(1,MahjongGame.Action.KAN,-1);passAll(open);
        check(open.phase()==MahjongGame.Phase.DISCARD&&open.currentTurn()==1&&open.lastDraw()>=0&&open.hands(1).size()==14,"Open kan supplement stays automatic");conserved(open);
        MahjongGame added=fixture(Map.of(0,List.of(0)),Map.of(0,List.of(new Meld(Meld.Kind.TRIPLET,0,true,2))),Map.of(),0);
        added.addedKan(0,0);passAll(added);
        check(added.phase()==MahjongGame.Phase.DISCARD&&added.lastDraw()>=0&&added.hands(0).size()==14,"Added kan supplement stays automatic");conserved(added);
        MahjongGame seven=fixture(Map.of(),Map.of(),Map.of(0,List.of(34,35,36,37,38,39,40)),0);
        placeHead(seven,41);seven.discard(0,seven.hands(0).getFirst());passAll(seven);
        check(seven.phase()==MahjongGame.Phase.DRAW&&!seven.isFinished(),"Flower win not processed until actual draw");
        seven.draw(1);check(seven.isFinished()&&seven.outcome().sourceSeat()==1&&seven.outcome().winners().getFirst().seat()==0,"Manual draw still triggers seven-flower win");
        MahjongGame eight=fixture(Map.of(),Map.of(),Map.of(0,List.of(34,35,36,37,38,39,40)),3);
        placeHead(eight,41);eight.discard(3,eight.hands(3).getFirst());passAll(eight);eight.draw(0);
        check(eight.isFinished()&&eight.outcome().selfDraw()&&eight.outcome().payments().size()==3,"Manual draw still triggers eight-flower win");
        MahjongGame flower=fixture(Map.of(),Map.of(),Map.of(),0);
        placeHead(flower,34);put(flower,"tail",RULES.reserveTiles());flower.discard(0,flower.hands(0).getFirst());passAll(flower);
        check(flower.phase()==MahjongGame.Phase.DRAW&&flower.remainingWall()==1,"Last live flower waits for draw");
        flower.draw(1);
        check(flower.phase()==MahjongGame.Phase.DISCARD&&flower.hands(1).size()==17&&flower.flowers(1).contains(34)&&flower.remainingWall()==0,"Last live flower is replaced automatically from retained tail");
        check(flower.hands(1).stream().noneMatch(Tiles::flower),"Flower never enters discardable hand");
        MahjongGame end=fixture(Map.of(),Map.of(),Map.of(),0);
        List<Integer> wall=field(end,"wall");placeHead(end,wall.stream().filter(t->t<34).findFirst().orElseThrow());put(end,"tail",RULES.reserveTiles());
        end.discard(0,end.hands(0).getFirst());passAll(end);end.draw(1);
        check(end.phase()==MahjongGame.Phase.DISCARD&&end.remainingWall()==0,"Last regular live tile may be drawn");
        end.discard(1,end.lastDraw());passAll(end);
        check(end.isFinished()&&end.outcome().draw()&&end.outcome().payments().isEmpty(),"No pointless draw prompt at exhausted live wall");
    }
    private static void passedRon()throws Exception {
        List<Integer> ready=List.of(0,1,6,7,8,9,10,11,18,19,20,31,31,31,27,27);
        MahjongGame game=fixture(Map.of(0,List.of(2),1,List.of(2),2,ready),Map.of(),Map.of(),0);
        game.discard(0,2);game.claim(2,MahjongGame.Action.PASS,-1);passAll(game);game.draw(1);
        game.discard(1,2);check(!game.validActions(2).contains(MahjongGame.Action.RON),"Passed ron remains blocked before own draw");
        passAll(game);boolean[] passed=field(game,"passedRon");
        check(game.phase()==MahjongGame.Phase.DRAW&&game.currentTurn()==2&&passed[2],"Entering draw phase alone does not clear passed-ron restriction");
        game.draw(2);check(!passed[2],"Actual draw clears passed-ron restriction");
    }
    private static void randomGames()throws Exception {
        Random random=new Random(168320);int draws=0;
        for(int seed=0;seed<120;seed++){
            MahjongGame game=new MahjongGame(90000+seed*723L,seed%4,0,seed%3,RULES,true);int step=0;boolean checkpoint=false;
            while(!game.isFinished()&&step++<1600){
                if(game.phase()==MahjongGame.Phase.DRAW){
                    if(!checkpoint){game=copy(game);checkpoint=true;check(game.manualDraw(),"Manual mode retained in resumed live hand");}
                    int actor=game.currentTurn();
                    if(seed%3==0)game.draw(actor);else if(seed%3==1)game.autoAction(actor);else PracticeBot.act(game,actor);draws++;
                }else if(game.phase()==MahjongGame.Phase.DISCARD){
                    int actor=game.currentTurn();var actions=game.validActions(actor);
                    if(actions.contains(MahjongGame.Action.TSUMO))game.tsumo(actor);
                    else if(!game.concealedKanOptions(actor).isEmpty()&&random.nextBoolean())game.concealedKan(actor,game.concealedKanOptions(actor).getFirst());
                    else if(!game.addedKanOptions(actor).isEmpty()&&random.nextBoolean())game.addedKan(actor,game.addedKanOptions(actor).getFirst());
                    else game.discard(actor,game.hands(actor).get(random.nextInt(game.hands(actor).size())));
                }else{
                    int actor=-1;for(int s=0;s<4;s++)if(!game.validActions(s).isEmpty()){actor=s;break;}
                    check(actor>=0,"Claim window has a legal responder");var actions=game.validActions(actor);MahjongGame.Action selected;
                    if(actions.contains(MahjongGame.Action.RON))selected=MahjongGame.Action.RON;
                    else if(actions.contains(MahjongGame.Action.KAN)&&random.nextBoolean())selected=MahjongGame.Action.KAN;
                    else if(actions.contains(MahjongGame.Action.PON)&&random.nextBoolean())selected=MahjongGame.Action.PON;
                    else if(actions.contains(MahjongGame.Action.CHI)&&random.nextBoolean())selected=MahjongGame.Action.CHI;
                    else selected=MahjongGame.Action.PASS;
                    game.claim(actor,selected,selected==MahjongGame.Action.CHI?game.chiOptions(actor).getFirst():-1);
                }
                if(!game.isFinished()){
                    conserved(game);
                    for(int s=0;s<4;s++)check(game.hands(s).size()==16-3*game.melds(s).size()+((game.phase()==MahjongGame.Phase.DISCARD||game.phase()==MahjongGame.Phase.KAN_CLAIM)&&game.currentTurn()==s?1:0),"Physical hand count matches phase");
                }
            }
            check(game.isFinished(),"Manual game reaches bounded finish");
        }
        check(draws>1000,"Real manual turn draws exercised");
    }
    private static void legacy(Path path)throws Exception {
        MahjongGame game;try(ObjectInputStream in=new ObjectInputStream(Files.newInputStream(path))){game=(MahjongGame)in.readObject();}
        check(!game.manualDraw(),"Actual old serialized hand defaults to automatic mode");
        int steps=0;while(!game.isFinished()&&steps++<1000){
            check(game.phase()!=MahjongGame.Phase.DRAW,"Legacy game never enters new manual phase");
            if(game.phase()==MahjongGame.Phase.DISCARD)game.autoAction(game.currentTurn());
            else for(int s=0;s<4;s++)if(!game.validActions(s).isEmpty()){game.autoAction(s);break;}
        }
        check(game.isFinished(),"Actual legacy checkpoint completes normally");
    }
    private static MahjongGame fixture(Map<Integer,List<Integer>> requested,Map<Integer,List<Meld>> melds,Map<Integer,List<Integer>> flowers,int turn)throws Exception {
        Method method=EngineTest.class.getDeclaredMethod("fixture",Map.class,Map.class,Map.class,int.class);method.setAccessible(true);
        MahjongGame result=(MahjongGame)method.invoke(null,requested,melds,flowers,turn);put(result,"manualDraw",true);return result;
    }
    private static void passAll(MahjongGame game){int loops=0;while((game.phase()==MahjongGame.Phase.CLAIM||game.phase()==MahjongGame.Phase.KAN_CLAIM)&&loops++<10)
        for(int s=0;s<4;s++)if(game.validActions(s).contains(MahjongGame.Action.PASS))game.claim(s,MahjongGame.Action.PASS,-1);}
    private static void placeHead(MahjongGame game,int tile)throws Exception {List<Integer> wall=field(game,"wall");Collections.swap(wall,(Integer)field(game,"head"),wall.indexOf(tile));}
    private static void conserved(MahjongGame game)throws Exception {
        int[] counts=new int[42];for(int s=0;s<4;s++){for(int tile:game.hands(s))counts[tile]++;for(int tile:game.flowers(s))counts[tile]++;for(int tile:game.discards(s))counts[tile]++;for(Meld meld:game.melds(s))for(int tile:meld.tiles())counts[tile]++;}
        List<Integer> wall=field(game,"wall");int head=field(game,"head"),tail=field(game,"tail");for(int i=head;i<=tail;i++)counts[wall.get(i)]++;
        for(int tile=0;tile<42;tile++)check(counts[tile]==(tile<34?4:1),"No duplicate/missing physical tile "+tile);
    }
    private static byte[] bytes(Object source)throws Exception {ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(ObjectOutputStream out=new ObjectOutputStream(bytes)){out.writeObject(source);}return bytes.toByteArray();}
    private static MahjongGame copy(MahjongGame source)throws Exception{return copyFrom(bytes(source));}
    private static MahjongGame copyFrom(byte[] bytes)throws Exception{try(ObjectInputStream in=new ObjectInputStream(new ByteArrayInputStream(bytes))){return(MahjongGame)in.readObject();}}
    private static String snapshot(MahjongGame game)throws Exception{Method method=EngineTest.class.getDeclaredMethod("snapshot",MahjongGame.class);method.setAccessible(true);return method.invoke(null,game)+Arrays.toString((int[])field(game,"drawsTaken"))+Arrays.toString((boolean[])field(game,"passedRon"));}
    @SuppressWarnings("unchecked") private static <T>T field(Object source,String name)throws Exception{Field field=source.getClass().getDeclaredField(name);field.setAccessible(true);return(T)field.get(source);}
    private static void put(Object source,String name,Object value)throws Exception{Field field=source.getClass().getDeclaredField(name);field.setAccessible(true);field.set(source,value);}
    private static void fails(Runnable action){checks++;try{action.run();}catch(IllegalArgumentException expected){return;}throw new AssertionError("Expected invalid action rejection");}
    private static void check(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
}
