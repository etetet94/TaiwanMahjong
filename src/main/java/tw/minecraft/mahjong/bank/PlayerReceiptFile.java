package tw.minecraft.mahjong.bank;

import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.UUID;
import java.util.zip.GZIPInputStream;

/** Bounded, read-only NBT receipt verifier; no Bukkit or NMS dependencies. */
public final class PlayerReceiptFile {
    private static final int MAX_COMPRESSED = 8 * 1024 * 1024;
    private static final int MAX_EXPANDED = 16 * 1024 * 1024;
    private PlayerReceiptFile() { }
    public static String readAndSync(Path file, UUID player, String key) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            if (channel.size() > MAX_COMPRESSED) throw new IOException("Player data exceeds receipt verification limit");
            channel.force(true);
            return readCompressed(Channels.newInputStream(channel), player, key);
        }
    }
    public static String readCompressed(InputStream source, UUID player, String key) throws IOException {
        byte[] expanded;
        try (GZIPInputStream gzip = new GZIPInputStream(source)) {
            expanded = gzip.readNBytes(MAX_EXPANDED + 1);
            if (expanded.length > MAX_EXPANDED) throw new IOException("Expanded player data exceeds safety limit");
        }
        return new Parser(new DataInputStream(new ByteArrayInputStream(expanded)), player, key).read();
    }
    private static final class Parser {
        private final DataInputStream input; private final UUID player; private final String key;
        private int nodes; private boolean uuidSeen, pdcSeen, receiptSeen; private String marker;
        Parser(DataInputStream input, UUID player, String key) { this.input=input; this.player=player; this.key=key; }
        String read() throws IOException {
            if(input.readUnsignedByte()!=10)throw new IOException("Player NBT root is not a compound");
            input.readUTF();compound(0, 1);
            if(!uuidSeen || input.available()!=0)throw new IOException("Invalid player NBT identity/trailing bytes");
            return marker;
        }
        // scope 1=root, 2=the root BukkitValues compound, 0=unrelated data.
        private void compound(int depth,int scope)throws IOException {
            for(;;){
                int type=input.readUnsignedByte();if(type==0)return;
                String name=input.readUTF();count(depth);
                if(scope==1 && name.equals("UUID")){
                    if(uuidSeen || type!=11 || input.readInt()!=4)throw new IOException("Invalid player UUID tag");
                    uuidSeen=true;
                    long most=((long)input.readInt()<<32)|(input.readInt()&0xffffffffL);
                    long least=((long)input.readInt()<<32)|(input.readInt()&0xffffffffL);
                    if(!player.equals(new UUID(most,least)))throw new IOException("Player file UUID mismatch");
                }else if(scope==1 && name.equals("BukkitValues")){
                    if(pdcSeen || type!=10)throw new IOException("Invalid player persistent-data compound");
                    pdcSeen=true;compound(depth+1,2);
                }else if(scope==2 && name.equals(key)){
                    if(receiptSeen || type!=8)throw new IOException("Invalid or duplicate receipt tag");
                    receiptSeen=true;marker=input.readUTF();
                    if(marker.length()>200)throw new IOException("Invalid receipt length");
                }else payload(type,depth+1);
            }
        }
        private void payload(int type,int depth)throws IOException {
            count(depth);
            switch(type){
                case 1 -> skip(1);case 2 -> skip(2);case 3,5 -> skip(4);case 4,6 -> skip(8);
                case 7 -> skip(length(1));case 8 -> input.readUTF();
                case 9 -> {
                    int element=input.readUnsignedByte(),length=input.readInt();
                    if(length<0 || length>100000 || element>12 || (element==0&&length>0))throw new IOException("Invalid NBT list");
                    for(int i=0;i<length;i++)payload(element,depth+1);
                }
                case 10 -> compound(depth+1,0);
                case 11 -> skip(length(4));case 12 -> skip(length(8));
                default -> throw new IOException("Unknown NBT tag: "+type);
            }
        }
        private int length(int width)throws IOException {
            int count=input.readInt();
            if(count<0 || count>MAX_EXPANDED/width)throw new IOException("Invalid NBT array length");
            return count*width;
        }
        private void skip(int bytes)throws IOException {
            if(bytes>input.available())throw new EOFException("Truncated player NBT");
            input.skipNBytes(bytes);
        }
        private void count(int depth)throws IOException {
            if(depth>64 || ++nodes>100000)throw new IOException("Player NBT nesting/node limit exceeded");
        }
    }
}
