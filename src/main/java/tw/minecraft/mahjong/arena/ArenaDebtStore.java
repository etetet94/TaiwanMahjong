package tw.minecraft.mahjong.arena;

import java.io.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Durable, append-retained arena obligations; independent from physical inventories. */
public final class ArenaDebtStore implements AutoCloseable {
    public static final int TARGET_DEFEATS=2;
    // Version-one snapshots can retain progress from the original ten-Warden challenge.
    private static final int MAX_RECORDED_DEFEATS=10;
    private static final int MAGIC=0x4D4A4152, VERSION=1, MAX_SNAPSHOT=64*1024*1024;
    private final Path file;
    private final FileChannel lockChannel;
    private final FileLock fileLock;
    private Map<String,Ticket> state;
    private boolean closed;

    public enum Status { QUEUED, ACTIVE, WON, LOST }
    public record ReturnPoint(UUID world,double x,double y,double z,float yaw,float pitch,
                              String gameMode,boolean allowFlight,boolean flying,boolean invulnerable) implements Serializable {
        private static final long serialVersionUID=1L;
        public ReturnPoint {
            Objects.requireNonNull(world,"world");Objects.requireNonNull(gameMode,"gameMode");
            if(!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z)||!Float.isFinite(yaw)||!Float.isFinite(pitch))
                throw new IllegalArgumentException("Return coordinates must be finite");
            if(!Set.of("SURVIVAL","ADVENTURE","CREATIVE","SPECTATOR").contains(gameMode))
                throw new IllegalArgumentException("Unknown return game mode");
        }
    }
    public record Ticket(String id,String gameId,UUID player,long debt,Status status,ReturnPoint returnPoint,
                         Set<UUID> defeated,boolean returned) implements Serializable {
        private static final long serialVersionUID=1L;
        public Ticket {
            validId(id);validId(gameId);Objects.requireNonNull(player);Objects.requireNonNull(status);
            if(debt<=0)throw new IllegalArgumentException("Arena debt must be positive");
            defeated=Set.copyOf(defeated);
            if(defeated.size()>MAX_RECORDED_DEFEATS)throw new IllegalArgumentException("Too many historical warden defeats recorded");
            if(status==Status.ACTIVE && returnPoint==null)throw new IllegalArgumentException("Active ticket has no return point");
            if(status==Status.WON && defeated.size()<TARGET_DEFEATS)throw new IllegalArgumentException("Winning requires "+TARGET_DEFEATS+" wardens");
            if(returned && !terminal(status))throw new IllegalArgumentException("Unfinished ticket cannot be returned");
        }
    }

    public ArenaDebtStore(Path file)throws IOException {
        this.file=Objects.requireNonNull(file).toAbsolutePath().normalize();
        Files.createDirectories(this.file.getParent());
        lockChannel=FileChannel.open(this.file.resolveSibling(this.file.getFileName()+".lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
        FileLock acquired;
        try{
            acquired=lockChannel.tryLock();
            if(acquired==null)throw new IOException("Arena debt store is already open");
        }catch(IOException|RuntimeException ex){lockChannel.close();throw new IOException("Cannot lock arena debt store",ex);}
        fileLock=acquired;
        try{state=Files.exists(this.file)?read(this.file):new LinkedHashMap<>();}
        catch(IOException|RuntimeException ex){fileLock.release();lockChannel.close();throw ex;}
    }

    /** Deterministic per-game/player identity prevents retry from creating a new obligation. */
    public synchronized Ticket enroll(String gameId,UUID player,long debt)throws IOException {
        checkOpen();validId(gameId);Objects.requireNonNull(player);
        if(debt<=0)throw new IllegalArgumentException("Arena debt must be positive");
        String id="arena-"+HexFormat.of().formatHex(sha256(("arena-debt-v1\u0000"+gameId+"\u0000"+player).getBytes(StandardCharsets.UTF_8)));
        Ticket old=state.get(id);
        if(old!=null){
            if(!old.gameId.equals(gameId)||!old.player.equals(player)||old.debt!=debt)
                throw new IllegalStateException("Arena enrollment reused with different debt");
            return old;
        }
        Ticket ticket=new Ticket(id,gameId,player,debt,Status.QUEUED,null,Set.of(),false);
        replace(ticket);return ticket;
    }
    public synchronized List<Ticket> tickets(){checkOpen();return List.copyOf(state.values());}
    public synchronized Optional<Ticket> find(String id){checkOpen();return Optional.ofNullable(state.get(id));}
    public synchronized boolean hasUnresolved(UUID player){
        checkOpen();Objects.requireNonNull(player);
        return state.values().stream().anyMatch(t->t.player.equals(player)&&(!terminal(t.status)||!t.returned));
    }

    /** First original return point is immutable, including after disconnect/requeue. */
    public synchronized boolean activate(String id,ReturnPoint returnPoint)throws IOException {
        checkOpen();Ticket old=require(id);
        if(terminal(old.status))throw new IllegalStateException("Terminal arena ticket cannot reactivate");
        if(old.status==Status.ACTIVE)return false;
        ReturnPoint point=old.returnPoint==null?Objects.requireNonNull(returnPoint,"returnPoint"):old.returnPoint;
        replace(copy(old,Status.ACTIVE,point,old.defeated,false));return true;
    }
    public synchronized boolean defeat(String id,UUID warden)throws IOException {
        checkOpen();Objects.requireNonNull(warden);Ticket old=require(id);
        if(old.defeated.contains(warden))return false;
        if(old.status!=Status.ACTIVE)throw new IllegalStateException("Only an active arena can record a defeat");
        if(old.defeated.size()>=TARGET_DEFEATS)return false;
        Set<UUID> defeated=new LinkedHashSet<>(old.defeated);defeated.add(warden);
        replace(copy(old,old.status,old.returnPoint,defeated,false));return true;
    }
    /** Pauses an active challenge without resetting kills or its original return point. */
    public synchronized boolean queue(String id)throws IOException {
        checkOpen();Ticket old=require(id);
        if(old.status!=Status.ACTIVE)return false;
        replace(copy(old,Status.QUEUED,old.returnPoint,old.defeated,false));return true;
    }
    public synchronized boolean resolve(String id,boolean won)throws IOException {
        checkOpen();Ticket old=require(id);Status result=won?Status.WON:Status.LOST;
        if(terminal(old.status)){
            if(old.status!=result)throw new IllegalStateException("Arena ticket already resolved with another outcome");
            return false;
        }
        if(won&&old.defeated.size()<TARGET_DEFEATS)throw new IllegalStateException("Winning requires "+TARGET_DEFEATS+" distinct warden defeats");
        replace(copy(old,result,old.returnPoint,old.defeated,false));return true;
    }
    /** Call after any idempotent winner reward and successful restoration/return. */
    public synchronized boolean markReturned(String id)throws IOException {
        checkOpen();Ticket old=require(id);
        if(!terminal(old.status))throw new IllegalStateException("Arena is not resolved");
        if(old.returned)return false;
        replace(copy(old,old.status,old.returnPoint,old.defeated,true));return true;
    }

    private static Ticket copy(Ticket old,Status status,ReturnPoint point,Set<UUID> defeated,boolean returned){
        return new Ticket(old.id,old.gameId,old.player,old.debt,status,point,defeated,returned);
    }
    private Ticket require(String id){validId(id);Ticket ticket=state.get(id);if(ticket==null)throw new IllegalArgumentException("Unknown arena ticket");return ticket;}
    private static boolean terminal(Status status){return status==Status.WON||status==Status.LOST;}
    private static void validId(String id){if(id==null||id.isBlank()||id.length()>200)throw new IllegalArgumentException("Invalid arena/game id");}
    private void checkOpen(){if(closed)throw new IllegalStateException("Arena debt store is closed");}

    private void replace(Ticket ticket)throws IOException {
        LinkedHashMap<String,Ticket> next=new LinkedHashMap<>(state);next.put(ticket.id,ticket);
        ByteArrayOutputStream buffer=new ByteArrayOutputStream();
        try(ObjectOutputStream out=new ObjectOutputStream(buffer)){out.writeObject(next);}
        byte[] bytes=buffer.toByteArray();
        if(bytes.length>MAX_SNAPSHOT)throw new IOException("Arena debt snapshot exceeds safety limit");
        Path temporary=file.resolveSibling(file.getFileName()+".tmp");
        try(FileOutputStream stream=new FileOutputStream(temporary.toFile());DataOutputStream out=new DataOutputStream(stream)){
            out.writeInt(MAGIC);out.writeInt(VERSION);out.writeInt(bytes.length);out.write(sha256(bytes));out.write(bytes);out.flush();stream.getFD().sync();
        }
        atomicReplace(temporary,file);
        state=next;
        try(FileChannel directory=FileChannel.open(file.getParent(),StandardOpenOption.READ)){directory.force(true);}
        catch(IOException|UnsupportedOperationException ignored){}
    }
    private static void atomicReplace(Path from,Path to)throws IOException {
        for(int attempt=0;;attempt++){
            try{Files.move(from,to,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);return;}
            catch(AccessDeniedException ex){
                if(attempt==4)throw ex;
                try{Thread.sleep(10L<<attempt);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IOException("Interrupted arena commit",ex);}
            }
        }
    }
    private static Map<String,Ticket> read(Path file)throws IOException {
        try(DataInputStream input=new DataInputStream(Files.newInputStream(file))){
            if(input.readInt()!=MAGIC||input.readInt()!=VERSION)throw new IOException("Unsupported arena debt format");
            int length=input.readInt();if(length<0||length>MAX_SNAPSHOT)throw new IOException("Invalid arena debt length");
            byte[] expected=input.readNBytes(32),bytes=input.readNBytes(length);
            if(expected.length!=32||bytes.length!=length||input.read()!=-1||!MessageDigest.isEqual(expected,sha256(bytes)))
                throw new IOException("Arena debt checksum mismatch");
            try(ObjectInputStream object=new ObjectInputStream(new ByteArrayInputStream(bytes))){
                object.setObjectInputFilter(info->{
                    if(info.depth()>40||info.references()>2000000||info.streamBytes()>MAX_SNAPSHOT)return ObjectInputFilter.Status.REJECTED;
                    Class<?> type=info.serialClass();if(type==null)return ObjectInputFilter.Status.UNDECIDED;
                    while(type.isArray())type=type.componentType();String name=type.getName();
                    return type.isPrimitive()||name.startsWith("tw.minecraft.mahjong.arena.ArenaDebtStore$")||name.startsWith("java.util.")||name.startsWith("java.lang.")
                        ?ObjectInputFilter.Status.ALLOWED:ObjectInputFilter.Status.REJECTED;
                });
                Object loaded=object.readObject();if(!(loaded instanceof Map<?,?> map))throw new IOException("Invalid arena debt root");
                LinkedHashMap<String,Ticket> result=new LinkedHashMap<>();
                for(var entry:map.entrySet()){
                    if(!(entry.getKey() instanceof String id)||!(entry.getValue() instanceof Ticket ticket)||!id.equals(ticket.id))
                        throw new IOException("Invalid arena debt entry");
                    result.put(id,ticket);
                }
                return result;
            }catch(ClassNotFoundException|RuntimeException ex){throw new IOException("Unreadable arena debt store",ex);}
        }
    }
    private static byte[] sha256(byte[] bytes){try{return MessageDigest.getInstance("SHA-256").digest(bytes);}catch(NoSuchAlgorithmException ex){throw new AssertionError(ex);}}
    @Override public synchronized void close()throws IOException {
        if(closed)return;closed=true;try{fileLock.release();}finally{lockChannel.close();}
    }
}
