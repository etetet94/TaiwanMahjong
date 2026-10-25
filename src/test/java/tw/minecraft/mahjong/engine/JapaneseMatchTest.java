package tw.minecraft.mahjong.engine;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/** Executable point-table, persistent-match and balanced-currency regression fixtures. */
public final class JapaneseMatchTest {
    private static int checks;
    public static void main(String[] args) throws Exception {
        pointTable(); continuation(); rounds(); riichi(); ending(); conversion(); serialization();
        System.out.println("JapaneseMatchTest PASS: " + checks + " assertions");
    }
    private static void pointTable() {
        int[][] cases = {
                {1,30,1000,1500,300,500}, {1,40,1300,2000,400,700}, {1,50,1600,2400,400,800},
                {1,60,2000,2900,500,1000}, {1,70,2300,3400,600,1200}, {1,80,2600,3900,700,1300},
                {1,90,2900,4400,800,1500}, {1,100,3200,4800,800,1600}, {1,110,3600,5300,900,1800},
                {2,20,1300,2000,400,700}, {2,25,1600,2400,400,800}, {2,30,2000,2900,500,1000},
                {2,40,2600,3900,700,1300}, {2,50,3200,4800,800,1600}, {2,60,3900,5800,1000,2000},
                {2,70,4500,6800,1200,2300}, {2,80,5200,7700,1300,2600}, {2,90,5800,8700,1500,2900},
                {2,100,6400,9600,1600,3200}, {2,110,7100,10600,1800,3600},
                {3,20,2600,3900,700,1300}, {3,25,3200,4800,800,1600}, {3,30,3900,5800,1000,2000},
                {3,40,5200,7700,1300,2600}, {3,50,6400,9600,1600,3200}, {3,60,7700,11600,2000,3900},
                {3,70,8000,12000,2000,4000}, {4,20,5200,7700,1300,2600}, {4,25,6400,9600,1600,3200},
                {4,30,7700,11600,2000,3900}, {4,40,8000,12000,2000,4000}, {5,0,8000,12000,2000,4000},
                {6,30,12000,18000,3000,6000}, {7,30,12000,18000,3000,6000}, {8,30,16000,24000,4000,8000},
                {10,30,16000,24000,4000,8000}, {11,30,24000,36000,6000,12000}, {12,30,24000,36000,6000,12000},
                {13,30,32000,48000,8000,16000}, {30,30,32000,48000,8000,16000}
        };
        for (int[] c : cases) {
            Score score = score(c[0], c[1]);
            check(JapaneseMatch.pointPayments(score,1,0,false,2,0).getFirst().points()==c[2], "Child ron " + Arrays.toString(c));
            check(JapaneseMatch.pointPayments(score,0,0,false,2,0).getFirst().points()==c[3], "Dealer ron " + Arrays.toString(c));
            var child = JapaneseMatch.pointPayments(score,1,0,true,-1,0);
            check(child.size()==3 && child.get(0).fromSeat()==0 && child.get(0).points()==c[5]
                    && child.get(1).points()==c[4] && child.get(2).points()==c[4], "Child tsumo " + Arrays.toString(c));
            var dealer = JapaneseMatch.pointPayments(score,0,0,true,-1,0);
            check(dealer.stream().allMatch(p->p.points()==c[5]), "Dealer tsumo " + Arrays.toString(c));
        }
        Score doubleYakuman = new Score(26,List.of(new Score.Tai("kokushi_13","國士無雙十三面待",26)),"飜",0,2);
        check(JapaneseMatch.basePoints(doubleYakuman)==16000, "Actual double yakuman base");
        check(JapaneseMatch.pointPayments(doubleYakuman,1,0,false,2,2).getFirst().points()==64600, "Double yakuman ron plus two honba");
        check(JapaneseMatch.pointPayments(score(1,30),1,0,true,-1,2).equals(List.of(
                new JapaneseMatch.PointPayment(0,1,700),new JapaneseMatch.PointPayment(2,1,500),new JapaneseMatch.PointPayment(3,1,500))), "Honba adds100 per payer after rounding");
        fails(()->JapaneseMatch.pointPayments(score(1,30),1,0,false,1,0));
        fails(()->JapaneseMatch.basePoints(score(0,30)));
        fails(()->JapaneseMatch.basePoints(score(1,22)));
    }
    private static void continuation() throws Exception {
        JapaneseMatch m = new JapaneseMatch(4,3);
        check(Arrays.equals(m.points(),new int[]{25000,25000,25000,25000}), "Four25k starts");
        int[] outside=m.points();outside[0]=0;check(m.points(0)==25000,"Points snapshot is defensive");
        MahjongGame first=win(m,1,0,false,score(1,30));
        check(m.applyHand(0,first),"First completed hand applied");
        check(Arrays.equals(m.points(),new int[]{24000,26000,25000,25000}),"Child ron standard points");
        check(m.dealer()==1&&m.prevailingWind()==0&&m.honba()==0&&m.handId()==1,"Nondealer win advances dealer");
        check(!m.applyHand(0,first)&&m.points(0)==24000,"Applying same hand twice never pays twice");
        fails(()->m.applyHand(3,first));
        int[] before=m.points(); MahjongGame unfinished=new MahjongGame(1,m.dealer(),m.prevailingWind(),m.honba(),MahjongRules.japanese());
        fails(()->m.applyHand(m.handId(),unfinished));check(Arrays.equals(before,m.points()),"Invalid hand leaves match unchanged");
        m.applyHand(m.handId(),win(m,1,-1,true,score(2,30)));
        check(Arrays.equals(m.points(),new int[]{23000,29000,24000,24000})&&m.dealer()==1&&m.honba()==1,"Dealer tsumo repeats withhonba");
        m.applyHand(m.handId(),win(m,2,3,false,score(1,30)));
        check(m.points(2)==25300&&m.points(3)==22700&&m.dealer()==2&&m.honba()==0,"Child ron receives300honba then reset");
        m.applyHand(m.handId(),draw(m,List.of(0,2),List.of(1500,-1500,1500,-1500)));
        check(m.dealer()==2&&m.honba()==1,"Tenpai dealer repeats on draw");
        m.applyHand(m.handId(),draw(m,List.of(),List.of(0,0,0,0)));
        check(m.dealer()==3&&m.honba()==2,"Noten dealer advances while honba increases");
        m.applyHand(m.handId(),win(m,0,3,false,score(1,30)));
        check(m.finished()&&m.roundIndex()==4&&m.endReason().equals("東風戰結束"),"East round ends after final dealer advances");
        check(Arrays.stream(m.points()).sum()==100000&&Arrays.stream(m.netPoints()).sum()==0,"Final point conservation");
        check(m.finalPayments().equals(m.finalPayments()),"Currency plan stable on retry");
        fails(()->m.declareRiichi(m.handId(),0));
    }
    private static void rounds() throws Exception {
        JapaneseMatch east=new JapaneseMatch(4,1);
        for(int n=0;n<3;n++)east.applyHand(east.handId(),draw(east,List.of(),List.of(0,0,0,0)));
        east.applyHand(east.handId(),win(east,3,0,false,score(1,30)));
        check(!east.finished()&&east.dealer()==3&&east.honba()==4,"Final dealer winning continues East round");
        east.applyHand(east.handId(),draw(east,List.of(3),List.of(-1000,-1000,-1000,3000)));
        check(!east.finished()&&east.dealer()==3&&east.honba()==5,"Final dealer tenpai continues");
        east.applyHand(east.handId(),win(east,1,2,false,score(1,30)));
        check(east.finished(),"Final dealer ends only when rotated");
        JapaneseMatch half=new JapaneseMatch(8,2);
        for(int n=0;n<8;n++) {
            check(half.dealer()==n%4&&half.prevailingWind()==n/4&&!half.finished(),"East/South dealer order");
            half.applyHand(half.handId(),draw(half,List.of(),List.of(0,0,0,0)));
        }
        check(half.finished()&&half.finalPayments().isEmpty()&&half.endReason().equals("半莊結束"),"No West extension or uma for tied hanchan");
        JapaneseMatch rotated=new JapaneseMatch(4,1,2);
        check(rotated.dealer()==2&&rotated.startingDealer()==2,"Optional initial dealer honored");
        rotated.applyHand(rotated.handId(),win(rotated,3,0,false,score(1,30)));
        check(rotated.dealer()==3,"Initial dealer offset preserved");
    }
    private static void riichi() throws Exception {
        JapaneseMatch m=new JapaneseMatch(4,1);
        check(m.canDeclareRiichi(1)&&m.declareRiichi(0,1),"Riichi debits immediately");
        check(m.points(1)==24000&&m.riichiPot()==1000&&m.riichiSticks()==1,"1000 point stick separated");
        check(!m.declareRiichi(0,1)&&m.points(1)==24000,"Duplicate checkpoint does not debit again");
        MahjongGame drawn=draw(m,List.of(1),List.of(-1000,3000,-1000,-1000));markRiichi(drawn,1);
        m.applyHand(0,drawn);
        check(m.riichiPot()==1000&&m.points(1)==27000&&m.honba()==1,"Draw carries sticks and applies3000notenpool");
        check(m.lastPointDeltas()[1]==2000,"Hand summary includes riichi deposit");
        m.declareRiichi(m.handId(),0);MahjongGame next=win(m,2,1,false,score(1,30));markRiichi(next,0);
        m.applyHand(m.handId(),next);
        check(m.riichiPot()==0&&m.points(2)==27300&&m.points(1)==25700,"Next winner receives both sticks andhonba");
        check(Arrays.stream(m.points()).sum()==100000,"Pot payout returns total100k");
        JapaneseMatch canceled=new JapaneseMatch(4,1);canceled.declareRiichi(0,0);
        canceled.applyHand(0,win(canceled,1,0,false,score(1,30)));
        check(canceled.points(0)==24000&&canceled.points(1)==26000&&canceled.riichiPot()==0,"Ron on declaration discard refunds provisional stick");
        JapaneseMatch missing=new JapaneseMatch(4,1);MahjongGame unrecorded=win(missing,1,0,false,score(1,30));markRiichi(unrecorded,2);
        fails(()->missing.applyHand(0,unrecorded));check(missing.points(0)==25000&&missing.handId()==0,"Missing stick checkpoint rejected atomically");
        JapaneseMatch threshold=new JapaneseMatch(4,1);put(threshold,"points",new long[]{1000,1000,0,98000});
        check(threshold.canDeclareRiichi(0)&&!threshold.canDeclareRiichi(2),"At least1000 points required");
        threshold.declareRiichi(0,0);check(threshold.points(0)==0&&!threshold.finished(),"Riichi can leave exactlyzero");
        fails(()->threshold.declareRiichi(0,2));
    }
    private static void ending() throws Exception {
        JapaneseMatch negative=new JapaneseMatch(8,2);
        negative.applyHand(0,win(negative,1,0,false,score(13,30)));
        check(negative.finished()&&negative.points(0)==-7000&&negative.endReason().equals("負點終局"),"Negative points end immediately");
        check(negative.finalPayments().equals(List.of(new MahjongGame.Payment(0,1,64,0))),"Only net matchpoint changes convert todiamonds");
        JapaneseMatch zero=new JapaneseMatch(8,1);put(zero,"honba",80);
        zero.applyHand(0,win(zero,1,0,false,score(1,30)));
        check(!zero.finished()&&zero.points(0)==0,"Exactly zero points do not end match");
        JapaneseMatch sticks=new JapaneseMatch(4,1);
        for(int n=0;n<3;n++)sticks.applyHand(sticks.handId(),draw(sticks,List.of(),List.of(0,0,0,0)));
        sticks.declareRiichi(sticks.handId(),3);
        MahjongGame allReady=draw(sticks,List.of(0,1,2,3),List.of(0,0,0,0));markRiichi(allReady,3);
        sticks.applyHand(sticks.handId(),allReady);
        sticks.applyHand(sticks.handId(),draw(sticks,List.of(),List.of(0,0,0,0)));
        check(sticks.finished()&&sticks.riichiPot()==0&&Arrays.equals(sticks.points(),new int[]{26000,25000,25000,24000}),"Unclaimed final sticks go totied leader byinitial seat order");
        check(sticks.finalPayments().equals(List.of(new MahjongGame.Payment(3,0,1,0))),"Final pot still preserves zero-sum settlement");
        JapaneseMatch nagashi=new JapaneseMatch(4,1);
        nagashi.applyHand(0,win(nagashi,1,-1,true,score(5,0)));
        check(Arrays.equals(nagashi.points(),new int[]{21000,33000,23000,23000}),"Nagashi mangan treated asstandard mangan tsumo");
    }
    private static void conversion() {
        var tied=JapaneseMatch.convertNetPoints(new long[]{-1500,-1500,1500,1500},1);
        check(tied.equals(List.of(new MahjongGame.Payment(0,2,2,0),new MahjongGame.Payment(1,3,1,0))),"Remainder ties deterministic onboth sides");
        check(JapaneseMatch.convertNetPoints(new long[]{-100,-100,-100,300},1).equals(List.of(new MahjongGame.Payment(0,3,1,0))),"Single aggregate rounding preservesdiamonds forsmallamounts");
        check(JapaneseMatch.convertNetPoints(new long[]{-1200,-1300,1250,1250},2).equals(List.of(
                new MahjongGame.Payment(0,2,2,0),new MahjongGame.Payment(1,2,1,0),new MahjongGame.Payment(1,3,2,0))),"Largestremainder independently apportions credit anddebt");
        check(JapaneseMatch.convertNetPoints(new long[]{-5000,5000,0,0},0).isEmpty(),"Zero rate has no transfers");
        fails(()->JapaneseMatch.convertNetPoints(new long[]{-1,1,0,1},1));
        Random random=new Random(45019);
        for(int n=0;n<2000;n++) {
            long[] net=new long[4];for(int s=0;s<3;s++)net[s]=random.nextInt(20001)-10000;net[3]=-net[0]-net[1]-net[2];
            int rate=random.nextInt(101);var payments=JapaneseMatch.convertNetPoints(net,rate);
            long[] actual=new long[4];long total=0;
            for(var p:payments) {
                check(p.diamonds()>0&&net[p.fromSeat()]<0&&net[p.toSeat()]>0&&p.fromSeat()!=p.toSeat(),"Onlydebtorspaycreditors");
                actual[p.fromSeat()]-=p.diamonds();actual[p.toSeat()]+=p.diamonds();total+=p.diamonds();
            }
            long positive=0;for(long value:net)if(value>0)positive+=value;
            check(total==(positive*rate+999)/1000&&Arrays.stream(actual).sum()==0,"Aggregateceil andexactconservation");
            for(int s=0;s<4;s++) {
                long numerator=Math.abs(net[s])*rate, amount=Math.abs(actual[s]);
                check(amount==numerator/1000||amount==(numerator+999)/1000,"Everyseat getsfloor orceil rationalamount");
            }
            check(payments.equals(JapaneseMatch.convertNetPoints(net,rate)),"Currencyconversiondeterministic");
        }
    }
    private static void serialization() throws Exception {
        JapaneseMatch original=new JapaneseMatch(8,7);original.declareRiichi(0,1);
        JapaneseMatch restored=copy(original);
        check(Arrays.equals(original.points(),restored.points())&&restored.riichiPot()==1000&&!restored.declareRiichi(0,1),"Checkpoint preserves points,pot,depositidempotency");
        MahjongGame hand=win(original,1,2,false,score(2,40));markRiichi(hand,1);
        original.applyHand(0,hand);restored.applyHand(0,hand);
        check(Arrays.equals(original.points(),restored.points())&&original.handId()==restored.handId()&&original.honba()==restored.honba(),"Restoredmatchcontinuesdeterministically");
        JapaneseMatch again=copy(restored);check(!again.applyHand(0,hand),"AppliedhandID persistsacrossrestart");
        long[] net=again.netPoints();net[0]=Long.MAX_VALUE;check(again.netPoints()[0]!=Long.MAX_VALUE,"Netpointssnapshotdefensive");
    }
    private static Score score(int han,int fu){return new Score(han,List.of(new Score.Tai("test","測試役",han)),"飜",fu,0);}
    private static MahjongGame win(JapaneseMatch m,int winner,int source,boolean self,Score score)throws Exception {
        return complete(m,new MahjongGame.Outcome(List.of(new MahjongGame.Win(winner,score)),List.of(),source,self,false,"和牌",List.of(),List.of(0,0,0,0)));
    }
    private static MahjongGame draw(JapaneseMatch m,List<Integer> ready,List<Integer> points)throws Exception {
        return complete(m,new MahjongGame.Outcome(List.of(),List.of(),-1,false,true,"流局",ready,points));
    }
    private static MahjongGame complete(JapaneseMatch m,MahjongGame.Outcome outcome)throws Exception {
        MahjongGame game=new MahjongGame(301,m.dealer(),m.prevailingWind(),m.honba(),MahjongRules.japanese(),true);
        put(game,"outcome",outcome);put(game,"phase",MahjongGame.Phase.FINISHED);return game;
    }
    private static void markRiichi(MahjongGame game,int seat)throws Exception {
        Field field=MahjongGame.class.getDeclaredField("declaredListening");field.setAccessible(true);((boolean[])field.get(game))[seat]=true;
    }
    private static void put(Object object,String name,Object value)throws Exception {Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);f.set(object,value);}
    private static JapaneseMatch copy(JapaneseMatch value)throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(ObjectOutputStream out=new ObjectOutputStream(bytes)){out.writeObject(value);}
        try(ObjectInputStream in=new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))){return(JapaneseMatch)in.readObject();}
    }
    private static void fails(Runnable action){checks++;try{action.run();}catch(IllegalArgumentException|IllegalStateException expected){return;}throw new AssertionError("Expected rejection");}
    private static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
}
