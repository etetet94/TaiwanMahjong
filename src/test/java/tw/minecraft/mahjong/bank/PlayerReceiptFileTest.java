package tw.minecraft.mahjong.bank;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPOutputStream;

public final class PlayerReceiptFileTest {
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String KEY = "taiwanmahjong:physical_transfer_receipt";
    private static final String MARKER = "before:00000000-0000-0000-0000-000000000002";
    private static int assertions;
    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory(args.length>0?Path.of(args[0]):Path.of("."), "receipt-tests-");
        byte[] valid = fixture(0);
        check(MARKER.equals(read(valid)), "Exact NBT receipt read");
        check(read(fixture(1))==null, "An inventory/name substring is not a PDC receipt");
        expect(() -> read(fixture(2)), "Duplicate receipt rejected");
        expect(() -> PlayerReceiptFile.readCompressed(new ByteArrayInputStream(valid),UUID.randomUUID(),KEY), "Wrong player UUID rejected");
        expect(() -> read(fixture(3)), "Wrong receipt type rejected");
        expect(() -> read(Arrays.copyOf(valid,valid.length-7)), "Truncated compressed data rejected");
        expect(() -> read(fixture(4)), "Trailing NBT data rejected");
        expect(() -> read(fixture(5)), "Negative array size rejected");
        expect(() -> read(fixture(6)), "Excessive depth rejected");
        expect(() -> read(fixture(7)), "Missing UUID rejected");
        expect(() -> read(fixture(8)), "Duplicate PDC compound rejected");
        byte[] huge = new byte[16*1024*1024+1];
        expect(() -> read(compress(huge)), "Compressed bomb bounded");
        Path disk=directory.resolve("player.dat");Files.write(disk,valid);
        check(MARKER.equals(PlayerReceiptFile.readAndSync(disk,PLAYER,KEY)), "Actual disk fsync and verification");
        check(Arrays.equals(Files.readAllBytes(disk),valid), "Verifier does not alter player file");
        System.out.println("PlayerReceiptFileTest PASS: "+assertions+" assertions; "+directory.toAbsolutePath());
    }
    private static String read(byte[] bytes)throws IOException {
        return PlayerReceiptFile.readCompressed(new ByteArrayInputStream(bytes),PLAYER,KEY);
    }
    private static byte[] fixture(int mode)throws IOException {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        DataOutputStream out=new DataOutputStream(bytes);
        tag(out,10,"");
        if(mode!=7){
            tag(out,11,"UUID");out.writeInt(4);
            out.writeInt((int)(PLAYER.getMostSignificantBits()>>>32));out.writeInt((int)PLAYER.getMostSignificantBits());
            out.writeInt((int)(PLAYER.getLeastSignificantBits()>>>32));out.writeInt((int)PLAYER.getLeastSignificantBits());
        }
        // Exercise every skipped primitive and array type.
        for(int type=1;type<=6;type++){tag(out,type,"primitive"+type);out.write(new byte[switch(type){case 1->1;case 2->2;case 3,5->4;default->8;}]);}
        for(int type:new int[]{7,11,12}){tag(out,type,"array"+type);out.writeInt(2);out.write(new byte[2*(type==7?1:type==11?4:8)]);}
        tag(out,9,"list");out.writeByte(8);out.writeInt(2);out.writeUTF("ignored");out.writeUTF(MARKER);
        if(mode==1){tag(out,10,"Inventory");tag(out,10,"BukkitValues");tag(out,8,KEY);out.writeUTF(MARKER);out.writeByte(0);out.writeByte(0);}
        tag(out,10,"BukkitValues");
        if(mode!=1){tag(out,mode==3?3:8,KEY);if(mode==3)out.writeInt(42);else out.writeUTF(MARKER);}
        if(mode==2){tag(out,8,KEY);out.writeUTF("second");}
        out.writeByte(0);
        if(mode==5){tag(out,7,"bad");out.writeInt(-1);}
        if(mode==6){for(int i=0;i<70;i++)tag(out,10,"deep");for(int i=0;i<70;i++)out.writeByte(0);}
        if(mode==8){tag(out,10,"BukkitValues");out.writeByte(0);}
        out.writeByte(0);
        if(mode==4)out.writeByte(99);
        return compress(bytes.toByteArray());
    }
    private static void tag(DataOutputStream out,int type,String name)throws IOException{out.writeByte(type);out.writeUTF(name);}
    private static byte[] compress(byte[] raw)throws IOException{
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(GZIPOutputStream gzip=new GZIPOutputStream(bytes)){gzip.write(raw);}return bytes.toByteArray();
    }
    private static void check(boolean okay,String message){assertions++;if(!okay)throw new AssertionError(message);}
    private static void expect(Checked action,String message)throws Exception{assertions++;try{action.run();}catch(IOException expected){return;}throw new AssertionError(message);}
    private interface Checked {void run()throws Exception;}
}
