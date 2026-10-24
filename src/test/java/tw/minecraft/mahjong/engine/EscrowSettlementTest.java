package tw.minecraft.mahjong.engine;

import java.math.BigInteger;
import java.util.*;
import tw.minecraft.mahjong.engine.MahjongGame.Payment;

/** Fixed payout examples plus independent exact-quota property checks. */
public final class EscrowSettlementTest {
    private static int checks;
    public static void main(String[] args) {
        List<Payment> triple = List.of(p(0,1,133,64), p(0,2,133,64), p(0,3,133,64));
        amounts(EscrowSettlement.cap(triple, caps(32)), 11,11,10);
        amounts(EscrowSettlement.cap(triple, caps(512)), 133,133,133);
        amounts(EscrowSettlement.cap(triple, caps(399)), 133,133,133);
        amounts(EscrowSettlement.cap(triple, caps(200)), 67,67,66);
        amounts(EscrowSettlement.cap(triple, caps(0)), 0,0,0);
        amounts(EscrowSettlement.cap(List.of(p(0,1,5,0),p(0,2,10,3),p(0,3,20,8)), caps(17)), 2,5,10);
        amounts(EscrowSettlement.cap(List.of(p(0,1,5,0),p(0,2,0,0),p(0,3,5,0)), caps(1)), 1,0,0);
        amounts(EscrowSettlement.cap(List.of(p(2,3,1,0),p(0,2,1,0),p(2,1,1,0),p(0,3,1,0)), caps(1)), 1,1,0,0);
        amounts(EscrowSettlement.cap(List.of(p(0,1,100,50),p(1,0,100,50)), new long[]{32,8,0,0}), 32,8);
        amounts(EscrowSettlement.cap(List.of(p(0,1,Long.MAX_VALUE,1)), caps(32)), 32);
        amounts(EscrowSettlement.cap(List.of(p(0,1,Long.MAX_VALUE,1),p(0,2,Long.MAX_VALUE,2),p(0,3,Long.MAX_VALUE,3)), caps(Long.MAX_VALUE)),
                3_074_457_345_618_258_603L,3_074_457_345_618_258_602L,3_074_457_345_618_258_602L);
        check(EscrowSettlement.cap(List.of(),caps(32)).isEmpty(),"Empty settlement");
        expect(() -> EscrowSettlement.cap(triple,new long[]{32,32,32}));
        expect(() -> EscrowSettlement.cap(triple,new long[]{32,-1,32,32}));
        expect(() -> EscrowSettlement.cap(List.of(p(0,0,1,0)),caps(32)));
        expect(() -> EscrowSettlement.cap(List.of(p(-1,2,1,0)),caps(32)));
        expect(() -> EscrowSettlement.cap(List.of(p(0,4,1,0)),caps(32)));
        expect(() -> EscrowSettlement.cap(List.of(p(0,1,-1,0)),caps(32)));
        try{EscrowSettlement.cap(triple,caps(32)).clear();throw new AssertionError("Mutable results");}
        catch(UnsupportedOperationException expected){checks++;}

        Random random = new Random(0xD1A3200L);
        for(int sample=0;sample<5000;sample++){
            ArrayList<Payment> source=new ArrayList<>();
            int length=random.nextInt(25);
            for(int i=0;i<length;i++){
                int from=random.nextInt(4),to=(from+1+random.nextInt(3))%4;
                long amount=sample%13==0?random.nextLong()&Long.MAX_VALUE:random.nextInt(500);
                source.add(p(from,to,amount,random.nextInt(100)));
            }
            long[] limits=new long[4];
            for(int i=0;i<4;i++)limits[i]=sample%17==0?random.nextLong()&Long.MAX_VALUE:random.nextInt(1025);
            List<Payment> actual=EscrowSettlement.cap(source,limits);
            verify(source,actual,limits);
            check(actual.equals(EscrowSettlement.cap(actual,limits)),"Capping is idempotent");
            check(actual.equals(EscrowSettlement.cap(source,limits)),"Allocation is deterministic");
        }
        System.out.println("EscrowSettlementTest PASS: "+checks+" assertions; 5000 randomized settlements");
    }

    private static void verify(List<Payment> original,List<Payment> actual,long[] caps){
        check(original.size()==actual.size(),"Payment cardinality changed");
        BigInteger debits=BigInteger.ZERO,credits=BigInteger.ZERO;
        BigInteger[] outgoing={BigInteger.ZERO,BigInteger.ZERO,BigInteger.ZERO,BigInteger.ZERO};
        BigInteger[] incoming={BigInteger.ZERO,BigInteger.ZERO,BigInteger.ZERO,BigInteger.ZERO};
        for(int i=0;i<original.size();i++){
            Payment before=original.get(i),after=actual.get(i);
            check(before.fromSeat()==after.fromSeat()&&before.toSeat()==after.toSeat()&&before.tai()==after.tai(),"Payment labels/order changed");
            check(after.diamonds()>=0&&after.diamonds()<=before.diamonds(),"Cap invented a liability");
            outgoing[after.fromSeat()]=outgoing[after.fromSeat()].add(BigInteger.valueOf(after.diamonds()));
            incoming[after.toSeat()]=incoming[after.toSeat()].add(BigInteger.valueOf(after.diamonds()));
        }
        for(int payer=0;payer<4;payer++){
            BigInteger total=BigInteger.ZERO;
            for(Payment payment:original)if(payment.fromSeat()==payer)total=total.add(BigInteger.valueOf(payment.diamonds()));
            BigInteger target=total.min(BigInteger.valueOf(caps[payer]));
            check(outgoing[payer].equals(target),"Payer must pay min(original,actual escrow)");
            debits=debits.add(outgoing[payer]);credits=credits.add(incoming[payer]);
            if(total.signum()==0)continue;
            for(int i=0;i<original.size();i++){
                if(original.get(i).fromSeat()!=payer)continue;
                BigInteger numerator=BigInteger.valueOf(original.get(i).diamonds()).multiply(target);
                BigInteger paid=BigInteger.valueOf(actual.get(i).diamonds());
                // Each allocation is floor or ceil of its exact rational quota.
                check(paid.multiply(total).subtract(numerator).abs().compareTo(total)<0,"Allocation outside exact quota");
                if(paid.multiply(total).compareTo(numerator)<=0)continue;
                BigInteger remainder=numerator.mod(total);
                for(int j=0;j<original.size();j++){
                    if(original.get(j).fromSeat()!=payer)continue;
                    BigInteger otherNumerator=BigInteger.valueOf(original.get(j).diamonds()).multiply(target);
                    if(BigInteger.valueOf(actual.get(j).diamonds()).multiply(total).compareTo(otherNumerator)>=0)continue;
                    int order=remainder.compareTo(otherNumerator.mod(total));
                    check(order>0||(order==0&&i<j),"Remainder award violated rank or original-order tie break");
                }
            }
        }
        check(debits.equals(credits),"Diamond conservation violated");
    }
    private static Payment p(int from,int to,long diamonds,int tai){return new Payment(from,to,diamonds,tai);}
    private static long[] caps(long amount){return new long[]{amount,amount,amount,amount};}
    private static void amounts(List<Payment> actual,long... expected){check(actual.size()==expected.length,"Wrong result count");for(int i=0;i<expected.length;i++)check(actual.get(i).diamonds()==expected[i],"Wrong fixed apportionment at "+i);}
    private static void check(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
    private static void expect(Runnable action){checks++;try{action.run();}catch(IllegalArgumentException expected){return;}throw new AssertionError("Expected invalid input rejection");}
}
