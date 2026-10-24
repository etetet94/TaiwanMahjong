package tw.minecraft.mahjong.arena;

import static tw.minecraft.mahjong.arena.ArenaDebtStore.TARGET_DEFEATS;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import tw.minecraft.mahjong.arena.ArenaDebtStore.*;
import tw.minecraft.mahjong.bank.DiamondLedger;

public final class ArenaDebtStoreTest {
    private static final UUID PLAYER=new UUID(1,2), OTHER=new UUID(3,4), WORLD=new UUID(5,6);
    private static final ReturnPoint ORIGINAL=new ReturnPoint(WORLD,10.5,64,-22.5,90,5,"SURVIVAL",false,false,false);
    private static int checks;
    public static void main(String[] args)throws Exception {
        if(args.length==2&&args[0].equals("crash-worker")){
            Path directory=Path.of(args[1]);
            ArenaDebtStore store=new ArenaDebtStore(directory.resolve("crash-arena.dat"));
            Ticket partial=store.enroll("hard-crash",PLAYER,500);
            store.activate(partial.id(),ORIGINAL);
            store.defeat(partial.id(),new UUID(10,0));
            Ticket winner=store.enroll("reward-crash",OTHER,900);
            store.activate(winner.id(),ORIGINAL);
            for(int i=0;i<TARGET_DEFEATS;i++)store.defeat(winner.id(),new UUID(20,i));
            store.resolve(winner.id(),true);
            DiamondLedger ledger=new DiamondLedger(directory.resolve("crash-wallet.dat"));
            ledger.credit(OTHER,64,"arena-win:"+winner.id());
            Runtime.getRuntime().halt(0); // Crash after reward, before markReturned; no graceful cleanup.
        }
        Path directory=Files.createTempDirectory(args.length>0?Path.of(args[0]):Path.of("."),"arena-store-tests-");
        check(TARGET_DEFEATS==2,"Challenge requires exactly two Warden defeats");
        normal(directory.resolve("normal.dat"));
        legacyProgress(directory.resolve("legacy.dat"));
        rollback(directory.resolve("rollback.dat"));
        crash(directory);
        randomized(directory.resolve("random.dat"));
        corruption(directory.resolve("corrupt.dat"));
        System.out.println("ArenaDebtStoreTest PASS: "+checks+" assertions; "+directory.toAbsolutePath());
    }
    private static void normal(Path file)throws Exception {
        String id;
        try(ArenaDebtStore store=new ArenaDebtStore(file)){
            Ticket ticket=store.enroll("game-one",PLAYER,99);id=ticket.id();
            check(ticket.status()==Status.QUEUED&&ticket.returnPoint()==null,"New ticket queued");
            check(store.hasUnresolved(PLAYER),"Queued debt blocks completion");
            check(store.enroll("game-one",PLAYER,99).id().equals(id)&&store.tickets().size()==1,"Enrollment idempotent");
            expect(IllegalStateException.class,()->store.enroll("game-one",PLAYER,100));
            expect(IllegalArgumentException.class,()->store.enroll("zero",PLAYER,0));
            expect(IllegalArgumentException.class,()->store.enroll("negative",PLAYER,-1));
            expect(IllegalStateException.class,()->store.markReturned(id));
            expect(IllegalStateException.class,()->store.defeat(id,new UUID(99,1)));
            check(!store.queue(id),"Queue retry no-op");
            check(store.activate(id,ORIGINAL),"Activation persisted");
            check(!store.activate(id,new ReturnPoint(WORLD,100,200,300,0,0,"CREATIVE",true,true,true)),"Active retry no-op");
            check(store.find(id).orElseThrow().returnPoint().equals(ORIGINAL),"Original return point immutable");
            UUID first=new UUID(10,1);
            check(store.defeat(id,first)&&!store.defeat(id,first),"Kill identity counted once");
            expect(IllegalStateException.class,()->store.resolve(id,true));
            check(store.queue(id),"Disconnect requeues");
            check(store.find(id).orElseThrow().defeated().size()==1,"Queue preserves kills");
            check(store.activate(id,new ReturnPoint(WORLD,200,220,200,0,0,"ADVENTURE",false,false,false)),"Resume activates");
            check(store.find(id).orElseThrow().returnPoint().equals(ORIGINAL),"Resume does not overwrite original location/state");
            check(store.defeat(id,new UUID(10,2)),"Second distinct Warden completes challenge");
            check(!store.defeat(id,new UUID(10,3))&&store.find(id).orElseThrow().defeated().size()==2,"New progress capped at two");
            check(store.resolve(id,true)&&!store.resolve(id,true),"Victory resolved exactly once");
            check(!store.queue(id),"Terminal queue cannot resurrect");
            expect(IllegalStateException.class,()->store.resolve(id,false));
            expect(IllegalStateException.class,()->store.activate(id,ORIGINAL));
            check(store.hasUnresolved(PLAYER),"Won ticket still needs return/reward completion");
            expect(UnsupportedOperationException.class,()->store.tickets().clear());
            expect(UnsupportedOperationException.class,()->store.find(id).orElseThrow().defeated().clear());
            expect(IOException.class,()->{try(ArenaDebtStore another=new ArenaDebtStore(file)){another.tickets();}});
        }
        try(ArenaDebtStore store=new ArenaDebtStore(file)){
            Ticket ticket=store.find(id).orElseThrow();
            check(ticket.status()==Status.WON&&ticket.defeated().size()==2&&!ticket.returned(),"Two-Warden victory and pending return survive reload");
            check(ticket.returnPoint().equals(ORIGINAL),"Full original state survives reload");
            check(store.markReturned(id)&&!store.markReturned(id),"Returned marker idempotent");
            check(!store.hasUnresolved(PLAYER),"Completed ticket no longer blocks player");
            check(store.enroll("game-one",PLAYER,99).status()==Status.WON,"Repeated finished enrollment never reopens");
            Ticket lost=store.enroll("game-two",PLAYER,Long.MAX_VALUE);
            store.activate(lost.id(),ORIGINAL);store.defeat(lost.id(),new UUID(11,1));
            check(store.resolve(lost.id(),false),"Death clears obligation into terminal state");
            check(store.find(lost.id()).orElseThrow().debt()==Long.MAX_VALUE,"Full long debt metadata preserved");
            store.markReturned(lost.id());
            check(!store.hasUnresolved(PLAYER)&&store.tickets().size()==2,"Finished history retained without unresolved balance");
        }
        expect(IllegalArgumentException.class,()->new ReturnPoint(WORLD,Double.NaN,1,1,0,0,"SURVIVAL",false,false,false));
        expect(IllegalArgumentException.class,()->new ReturnPoint(WORLD,1,1,1,0,0,"UNKNOWN",false,false,false));
    }
    private static void legacyProgress(Path file)throws Exception {
        // Preserve the original version-one snapshot layout and all historical defeat IDs.
        List<Ticket> legacy=List.of(
                new Ticket("legacy-one","legacy-game-one",PLAYER,40,Status.QUEUED,ORIGINAL,defeats(1),false),
                new Ticket("legacy-two","legacy-game-two",PLAYER,40,Status.QUEUED,ORIGINAL,defeats(2),false),
                new Ticket("legacy-five","legacy-game-five",PLAYER,40,Status.ACTIVE,ORIGINAL,defeats(5),false),
                new Ticket("legacy-won","legacy-game-won",OTHER,40,Status.WON,ORIGINAL,defeats(10),false),
                new Ticket("legacy-lost","legacy-game-lost",OTHER,40,Status.LOST,ORIGINAL,defeats(10),true));
        LinkedHashMap<String,Ticket> snapshot=new LinkedHashMap<>();
        for(Ticket ticket:legacy)snapshot.put(ticket.id(),ticket);
        ByteArrayOutputStream buffer=new ByteArrayOutputStream();
        try(ObjectOutputStream output=new ObjectOutputStream(buffer)){output.writeObject(snapshot);}
        byte[] bytes=buffer.toByteArray();
        try(DataOutputStream output=new DataOutputStream(Files.newOutputStream(file))){
            output.writeInt(0x4D4A4152);output.writeInt(1);output.writeInt(bytes.length);
            output.write(MessageDigest.getInstance("SHA-256").digest(bytes));output.write(bytes);
        }
        try(ArenaDebtStore store=new ArenaDebtStore(file)){
            check(store.tickets().equals(legacy),"Legacy queued, active and terminal tickets read without losing progress");
            expect(IllegalStateException.class,()->store.resolve("legacy-one",true));
            store.activate("legacy-one",null);
            check(store.defeat("legacy-one",new UUID(41,1))&&store.resolve("legacy-one",true),
                    "Legacy one-defeat challenge only needs one additional Warden");
            check(store.resolve("legacy-two",true),"Queued legacy challenge at the new target can resolve without another fight");
            check(!store.defeat("legacy-five",new UUID(41,2)),"Legacy progress above the new target cannot add more defeats");
            store.queue("legacy-five");store.activate("legacy-five",null);
            check(store.resolve("legacy-five",true)&&store.find("legacy-five").orElseThrow().defeated().equals(defeats(5)),
                    "Active legacy challenge above the target wins with all original progress preserved");
            check(!store.resolve("legacy-won",true)&&store.find("legacy-won").orElseThrow().defeated().equals(defeats(10)),
                    "Historical ten-Warden winner remains valid and resolution is idempotent");
            check(store.markReturned("legacy-won"),"Historical winner can finish its pending return");
        }
        try(ArenaDebtStore store=new ArenaDebtStore(file)){
            check(store.find("legacy-two").orElseThrow().status()==Status.WON
                    &&store.find("legacy-five").orElseThrow().status()==Status.WON,"Legacy victories survive reopening");
            check(store.find("legacy-won").orElseThrow().defeated().size()==10
                    &&store.find("legacy-won").orElseThrow().returned()
                    &&store.find("legacy-lost").orElseThrow().defeated().size()==10,"Historical ten-defeat records survive later commits");
        }
        expect(IllegalArgumentException.class,()->new Ticket("too-few","validation",PLAYER,1,Status.WON,ORIGINAL,defeats(1),false));
        expect(IllegalArgumentException.class,()->new Ticket("too-many","validation",PLAYER,1,Status.ACTIVE,ORIGINAL,defeats(11),false));
    }
    private static Set<UUID> defeats(int amount){
        Set<UUID> result=new LinkedHashSet<>();for(int i=0;i<amount;i++)result.add(new UUID(40,i));return result;
    }
    private static void rollback(Path file)throws Exception {
        String id;
        try(ArenaDebtStore store=new ArenaDebtStore(file)){
            id=store.enroll("rollback",PLAYER,40).id();
            Path temp=file.resolveSibling(file.getFileName()+".tmp");Files.createDirectory(temp);
            try{expect(IOException.class,()->store.activate(id,ORIGINAL));}finally{Files.delete(temp);}
            check(store.find(id).orElseThrow().status()==Status.QUEUED&&store.find(id).orElseThrow().returnPoint()==null,"Failed commit does not change memory");
        }
        try(ArenaDebtStore store=new ArenaDebtStore(file)){
            check(store.find(id).orElseThrow().status()==Status.QUEUED,"Failed commit does not change disk");
            store.activate(id,ORIGINAL);
            check(store.find(id).orElseThrow().status()==Status.ACTIVE,"Failed transaction retry works");
        }
    }
    private static void crash(Path directory)throws Exception {
        String java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
        Process process=new ProcessBuilder(java,"-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),ArenaDebtStoreTest.class.getName(),"crash-worker",directory.toString()).inheritIO().start();
        check(process.waitFor()==0,"Crash worker committed changes");
        Files.write(directory.resolve("crash-arena.dat.tmp"),new byte[]{1,2,3});
        try(ArenaDebtStore store=new ArenaDebtStore(directory.resolve("crash-arena.dat"));DiamondLedger ledger=new DiamondLedger(directory.resolve("crash-wallet.dat"))){
            Ticket partial=store.enroll("hard-crash",PLAYER,500);
            check(partial.status()==Status.ACTIVE&&partial.defeated().size()==1,"Hard crash retains incomplete one-Warden progress");
            store.queue(partial.id());store.activate(partial.id(),null);
            check(store.find(partial.id()).orElseThrow().returnPoint().equals(ORIGINAL),"Crash resume retains original point");
            Ticket won=store.enroll("reward-crash",OTHER,900);
            check(won.status()==Status.WON&&!won.returned(),"Hard crash retains unreturned winner");
            check(!ledger.credit(OTHER,64,"arena-win:"+won.id()),"Crash after reward cannot issue reward twice");
            store.markReturned(won.id());
            check(ledger.balance(OTHER)==64&&!store.hasUnresolved(OTHER),"Winner receives exactly 64 and completes return");
        }
    }
    private static void randomized(Path file)throws Exception {
        Random random=new Random(0xA2E4A10);
        try(ArenaDebtStore store=new ArenaDebtStore(file)){
            for(int game=0;game<40;game++){
                UUID player=new UUID(100,game);Ticket ticket=store.enroll("random-"+game,player,1+random.nextInt(10000));
                store.activate(ticket.id(),ORIGINAL);int target=random.nextBoolean()?TARGET_DEFEATS:random.nextInt(TARGET_DEFEATS);
                Set<UUID> oracle=new HashSet<>();
                for(int step=0;step<target;step++){
                    UUID warden=new UUID(game,step);oracle.add(warden);store.defeat(ticket.id(),warden);
                    check(!store.defeat(ticket.id(),warden),"Repeated randomized kill no-op");
                    if(random.nextBoolean()){store.queue(ticket.id());store.activate(ticket.id(),null);}
                    check(store.find(ticket.id()).orElseThrow().defeated().equals(oracle),"Progress matches independent set through pauses");
                }
                store.resolve(ticket.id(),target==TARGET_DEFEATS);store.markReturned(ticket.id());
                check(!store.hasUnresolved(player),"Random challenge completed");
                check(store.enroll("random-"+game,player,ticket.debt()).returned(),"Completed randomized ID cannot reopen");
            }
        }
        try(ArenaDebtStore store=new ArenaDebtStore(file)){
            check(store.tickets().size()==40&&store.tickets().stream().allMatch(Ticket::returned),"Randomized completed history survives reload");
        }
    }
    private static void corruption(Path file)throws Exception {
        try(ArenaDebtStore store=new ArenaDebtStore(file)){store.enroll("corrupt",PLAYER,32);}
        byte[] bytes=Files.readAllBytes(file);bytes[bytes.length-1]^=1;Files.write(file,bytes);
        expect(IOException.class,()->{try(ArenaDebtStore broken=new ArenaDebtStore(file)){broken.tickets();}});
        check(Arrays.equals(bytes,Files.readAllBytes(file)),"Corruption fails closed without replacing data");
    }
    private static void check(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
    private static void expect(Class<? extends Throwable> expected,Checked operation)throws Exception{
        checks++;try{operation.run();}catch(Throwable actual){if(expected.isInstance(actual))return;throw new AssertionError("Expected "+expected+", got "+actual,actual);}throw new AssertionError("Expected "+expected);
    }
    private interface Checked {void run()throws Exception;}
}
