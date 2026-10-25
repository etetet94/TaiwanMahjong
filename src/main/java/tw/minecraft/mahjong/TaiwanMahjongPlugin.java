package tw.minecraft.mahjong;

import java.io.*;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.*;
import org.bukkit.plugin.java.JavaPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import io.papermc.paper.event.player.AsyncChatEvent;
import tw.minecraft.mahjong.bank.DiamondLedger;
import tw.minecraft.mahjong.arena.ArenaManager;
import tw.minecraft.mahjong.engine.*;
import tw.minecraft.mahjong.view.TableView;
import tw.minecraft.mahjong.view.TileArt;

public final class TaiwanMahjongPlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();
    private static final long MAX_ESCROW_DIAMONDS = 2304;
    private final Map<String, Table> tables = new LinkedHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private DiamondLedger ledger;
    private DiamondInventory diamonds;
    private ArenaManager arena;
    private MahjongCelebrations celebrations;
    private MahjongActionEffects actionEffects;
    private MahjongRules rules;
    private boolean operational;
    private final Map<UUID,Long> walletInput=new ConcurrentHashMap<>();

    static final class Table {
        final String id; final Location location;
        final UUID[] seats = new UUID[4]; final String[] names = new String[4];
        final boolean[] ready = new boolean[4];
        TableView view; Session session; long revision;
        UUID instance=UUID.randomUUID();long lastSettlementAt;
        int dealer, streak, roundWind; boolean faulted;
        LobbySettings lobby = new LobbySettings(RuleSet.TAIWAN,5,2);
        Table(String id, Location location) { this.id = id; this.location = location; }
    }
    static final class Session implements Serializable {
        private static final long serialVersionUID = 1L;
        String id, tableId; UUID world; double x,y,z;
        UUID[] seats; String[] names; boolean[] bots;
        UUID[] lobbyOrder;
        boolean practice; MahjongGame game; long deadline;
        UUID tableInstance;long endedAt;int nextDealer,nextStreak,nextWind;
        List<MahjongGame.Payment> actualPayments;
        boolean arenaDebtEnabled;
        JapaneseMatch japaneseMatch;
        long japaneseHand;
        boolean japaneseHandCommitted;
        List<MahjongGame.Payment> theoreticalPayments;
    }
    private static final class Menu implements InventoryHolder {
        Inventory inventory; final String table; final long revision;
        boolean wallet;
        int walletAmount=1;
        final Map<Integer, Consumer<Player>> actions = new HashMap<>();
        Menu(String table,long revision) { this.table=table; this.revision=revision; }
        @Override public Inventory getInventory() { return inventory; }
    }

    @Override public void onEnable() {
        try {
            saveDefaultConfig();
            celebrations=new MahjongCelebrations(this);
            actionEffects=new MahjongActionEffects(this);
            MahjongRules standard=MahjongRules.defaults();
            rules=new MahjongRules(getConfig().getInt("base-diamonds",5),getConfig().getInt("diamonds-per-tai",2),standard.reserveTiles(),standard.taiValues());
            if (rules.baseDiamonds()<1 || rules.diamondsPerTai()<1) throw new IOException("底與每台鑽石必須大於零");
            required(null);
            ledger=new DiamondLedger(getDataFolder().toPath().resolve("diamond-ledger.dat"));
            diamonds=new DiamondInventory(this,ledger);
            TableView.cleanupOwnedEntities(this);
            boolean firstLaunch=!Files.exists(getDataFolder().toPath().resolve("tables.yml"));
            loadTables();
            restoreGames();
            if(firstLaunch && tables.isEmpty() && getConfig().getBoolean("create-first-table",true)) createFirstTable();
            for(Table table:tables.values()) createView(table);
            arena=new ArenaManager(this,ledger,this::releaseForArena);
            arena.enable();
            for(var saved:ledger.closedGames().values())if(saved.status()==DiamondLedger.GameStatus.SETTLED && saved.attachment().length>0)
                registerArenaDebts(decode(saved.attachment()));
            getServer().getPluginManager().registerEvents(this,this);
            Objects.requireNonNull(getCommand("mahjong")).setExecutor(this);
            getCommand("mahjong").setTabCompleter(this);
            operational=true;
            for(Player p:Bukkit.getOnlinePlayers()) guard(p,()->diamonds.recover(p));
            getServer().getScheduler().runTaskTimer(this,this::tick,20L,20L);
            getLogger().info("繁中台麻／日麻已啟用；牌桌 "+tables.size()+"，各桌由房主選規則與金額。");
        } catch(Exception ex) {
            getLogger().log(java.util.logging.Level.SEVERE,"麻將初始化失敗；帳本保留原檔，停止啟用以避免錯誤結算。",ex);
            getServer().getPluginManager().disablePlugin(this);
        }
    }
    @Override public void onDisable() {
        operational=false;
        walletInput.clear();
        if(actionEffects!=null)actionEffects.close();
        if(arena!=null)arena.close();
        for(Table t:tables.values()) if(t.view!=null)t.view.close();
        for(Player p:Bukkit.getOnlinePlayers())if(p.getOpenInventory().getTopInventory().getHolder() instanceof Menu)p.closeInventory();
        if(ledger!=null)try{ledger.close();}catch(IOException ex){getLogger().severe(ex.toString());}
        // Paid hands are checkpointed on every mutation and resume next startup, not refunded on shutdown.
    }
    private void loadTables() throws IOException {
        File file=new File(getDataFolder(),"tables.yml");
        if(!file.exists())return;
        YamlConfiguration cfg=new YamlConfiguration();
        try{cfg.load(file);}catch(Exception e){throw new IOException("無法讀取牌桌資料",e);}
        ConfigurationSection root=cfg.getConfigurationSection("tables");
        if(root==null)return;
        for(String id:root.getKeys(false)){
            ConfigurationSection c=root.getConfigurationSection(id);
            World world=Bukkit.getWorld(UUID.fromString(c.getString("world")));
            if(world==null)throw new IOException("牌桌世界尚未載入："+id);
            Table t=new Table(id,new Location(world,c.getDouble("x"),c.getDouble("y"),c.getDouble("z")));
            t.lobby=new LobbySettings(RuleSet.valueOf(c.getString("rule-set","TAIWAN")),c.getInt("base-diamonds",rules.baseDiamonds()),c.getInt("diamonds-per-unit",rules.diamondsPerTai()));
            t.lobby.restoreRounds(c.getInt("japanese-rounds",4));
            if(c.contains("instance"))t.instance=UUID.fromString(c.getString("instance"));t.lastSettlementAt=c.getLong("last-settlement-at",0);
            t.dealer=Math.floorMod(c.getInt("dealer",0),4);t.roundWind=Math.floorMod(c.getInt("round-wind",0),4);t.streak=Math.max(0,c.getInt("streak",0));tables.put(id,t);
        }
    }
    private void saveTables() throws IOException {
        YamlConfiguration cfg=new YamlConfiguration();
        for(Table t:tables.values()){
            String key="tables."+t.id+".";
            cfg.set(key+"world",t.location.getWorld().getUID().toString());cfg.set(key+"x",t.location.getX());
            cfg.set(key+"y",t.location.getY());cfg.set(key+"z",t.location.getZ());
            cfg.set(key+"dealer",t.dealer);cfg.set(key+"streak",t.streak);cfg.set(key+"round-wind",t.roundWind);
            cfg.set(key+"instance",t.instance.toString());cfg.set(key+"last-settlement-at",t.lastSettlementAt);
            cfg.set(key+"rule-set",t.lobby.mode().name());cfg.set(key+"base-diamonds",t.lobby.base());cfg.set(key+"diamonds-per-unit",t.lobby.perUnit());
            cfg.set(key+"japanese-rounds",t.lobby.rounds());
        }
        Path dest=getDataFolder().toPath().resolve("tables.yml"),temp=dest.resolveSibling("tables.yml.tmp");
        Files.writeString(temp,cfg.saveToString());
        // Windows readers can briefly hold the previous index after close.
        // Retry the same atomic replacement; never delete or copy over the index.
        for(int attempt=0;;attempt++){
            try{
                Files.move(temp,dest,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
                break;
            }catch(AccessDeniedException blocked){
                if(attempt==4)throw blocked;
                try{Thread.sleep(10L<<attempt);}
                catch(InterruptedException interrupted){
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while committing table index",blocked);
                }
            }
        }
    }
    private void createFirstTable() throws IOException {
        World world=Bukkit.getWorlds().getFirst();Location spawn=world.getSpawnLocation();
        // Display entities never replace world blocks. Search a dry, flat, open patch near spawn.
        for(int r=6;r<=22;r+=4)for(int dx=-r;dx<=r;dx+=4)for(int dz:new int[]{-r,r}){
            int x=spawn.getBlockX()+dx,z=spawn.getBlockZ()+dz;
            int y=world.getHighestBlockYAt(x,z,HeightMap.MOTION_BLOCKING_NO_LEAVES)+1;
            Location location=new Location(world,x+0.5,y,z+0.5);
            if(clearSite(location)){tables.put("牌桌一",newTable("牌桌一",location));saveTables();return;}
        }
        getLogger().warning("出生點附近未找到平坦空地；管理員可站在空地使用 /mj create 牌桌一。");
    }
    private boolean clearSite(Location p){
        World w=p.getWorld();
        for(int x=-2;x<=2;x++)for(int z=-2;z<=2;z++){
            if(!w.getBlockAt(p.getBlockX()+x,p.getBlockY()-1,p.getBlockZ()+z).getType().isSolid())return false;
            for(int y=0;y<3;y++)if(!w.getBlockAt(p.getBlockX()+x,p.getBlockY()+y,p.getBlockZ()+z).isPassable()
                ||w.getBlockAt(p.getBlockX()+x,p.getBlockY()+y,p.getBlockZ()+z).isLiquid())return false;
        }return true;
    }
    private Table newTable(String id,Location location){
        Table table=new Table(id,location);table.lobby=new LobbySettings(RuleSet.TAIWAN,rules.baseDiamonds(),rules.diamondsPerTai());return table;
    }
    private void restoreGames() throws IOException {
        // Settlement and next dealer state share the ledger commit. tables.yml is only its recoverable index.
        for(var saved:ledger.closedGames().values())if(saved.status()==DiamondLedger.GameStatus.SETTLED && saved.attachment().length>0){
            Session s=decode(saved.attachment());Table t=tables.get(s.tableId);
            if(t!=null && t.instance.equals(s.tableInstance) && s.endedAt>t.lastSettlementAt){
                t.dealer=s.nextDealer;t.streak=s.nextStreak;t.roundWind=s.nextWind;t.lastSettlementAt=s.endedAt;
            }
        }
        for(var saved:ledger.openGames().values()){
            Session s=decode(saved.attachment());
            if(s.practice || !saved.id().equals(s.id) || !saved.players().equals(Arrays.asList(s.seats)))throw new IOException("對局與押金座位不一致");
            Table t=tables.get(s.tableId);
            if(t==null){World w=Bukkit.getWorld(s.world);if(w==null)throw new IOException("恢復對局所需世界不存在");
                t=new Table(s.tableId,new Location(w,s.x,s.y,s.z));t.instance=s.tableInstance;tables.put(t.id,t);}
            if(!t.instance.equals(s.tableInstance))throw new IOException("未結算對局與牌桌識別碼不一致");
            if(t.session!=null)throw new IOException("同一牌桌有多局未結算");
            t.session=s;s.deadline=System.currentTimeMillis()+60000;
            System.arraycopy(s.seats,0,t.seats,0,4);System.arraycopy(s.names,0,t.names,0,4);
            if(s.lobbyOrder!=null)for(UUID id:s.lobbyOrder)if(Arrays.asList(s.seats).contains(id))t.lobby.join(id);
            for(UUID id:s.seats)t.lobby.join(id);
            t.lobby.restoreActive(s.game.rules().ruleSet(),s.game.rules().baseDiamonds(),s.game.rules().diamondsPerTai());
            if(s.japaneseMatch!=null)t.lobby.restoreRounds(s.japaneseMatch.rounds());
            t.dealer=s.game.dealer();t.streak=s.game.dealerStreak();t.roundWind=s.game.prevailingWind();
        }
        saveTables();
    }
    private void createView(Table t){
        t.view=new TableView(this,t.id,t.location,(p,action,value)->guard(p,()->{
            if(action.equals("join"))join(p,t,value);
            else if(action.equals("hand")){
                int seat=seat(t,p);ensureNear(p,t);ensureActive(t);
                List<Integer> hand=t.session.game.hands(seat);
                if(value<0||value>=hand.size())return;
                int tile=hand.get(value);mutate(t,g->g.discard(seat,tile));
            }else show(p,t);
        }));render(t);
    }
    private void render(Table t){
        if(t.view==null)return;
        List<TableView.SeatState> seats=new ArrayList<>();MahjongGame g=t.session==null?null:t.session.game;
        for(int i=0;i<4;i++){
            List<List<String>> melds=new ArrayList<>();
            if(g!=null)for(Meld m:g.melds(i))melds.add(m.open()?labels(m.tiles()):g.isJapanese()?List.of("?",Tiles.name(m.tile()),Tiles.name(m.tile()),"?"):Collections.nCopies(m.tiles().size(),"?"));
            seats.add(new TableView.SeatState(t.seats[i],t.names[i]==null?"空位":t.names[i],g==null?List.of():labels(g.hands(i)),melds,
                g==null?List.of():labels(g.discards(i)),g==null?List.of():labels(g.flowers(i)),g==null?List.of():g.melds(i),g==null?-1:g.riichiDiscardIndex(i)));
        }
        String status=t.faulted?"資料寫入異常，牌局已暫停":g==null?(t.lobby.selected()?terms(t):"右鍵入座｜房主先選台麻／日麻"):
            (t.session.practice?"免費練習｜":"鑽石對局｜")+turnText(t.session)+"｜"+Math.max(0,(t.session.deadline-System.currentTimeMillis()+999)/1000)+" 秒";
        t.view.render(new TableView.ViewState(seats,g==null?-1:g.currentTurn(),t.dealer,g==null?0:g.remainingWall(),status));
    }
    private static List<String> labels(Collection<Integer> tiles){return tiles.stream().map(Tiles::name).toList();}
    private String turnText(Session s){
        MahjongGame g=s.game;
        return switch(g.phase()){
            case DRAW->"輪到 "+s.names[g.currentTurn()]+" 摸牌";
            case DISCARD->"輪到 "+s.names[g.currentTurn()]+" 出牌";
            case CLAIM->(g.lastDiscarder()<0?"上家":s.names[g.lastDiscarder()])+" 打出 "+Tiles.name(g.lastTile())+"，等待吃／碰／槓／胡";
            case KAN_CLAIM->"加槓 "+Tiles.name(g.lastTile())+"，等待搶槓胡";
            case FINISHED->"本局結束";
        };
    }
    private String actionText(MahjongGame g,int seat){
        List<String> actions=new ArrayList<>();
        for(var action:g.validActions(seat))actions.add(switch(action){
            case DRAW->"摸牌";case CHI->"吃";case PON->"碰";case KAN->"槓";case RON->"胡牌";case TSUMO->"自摸";case PASS->"過";case LISTEN->g.isJapanese()?"立直":"宣告聽牌";
        });
        if(g.phase()==MahjongGame.Phase.DISCARD && g.currentTurn()==seat)actions.add("點手牌出牌");
        return actions.isEmpty()?"等待其他玩家":"可選："+String.join("／",actions);
    }
    private void notifyTurn(Table t,boolean chat){
        Session s=t.session;if(s==null)return;String turn=turnText(s);
        for(int i=0;i<4;i++)if(!s.bots[i]){
            Player p=Bukkit.getPlayer(s.seats[i]);if(p==null)continue;
            String actions=actionText(s.game,i);
            long seconds=Math.max(0,(s.deadline-System.currentTimeMillis()+999)/1000);
            p.sendActionBar(Component.text(turn+"｜"+actions+"｜"+seconds+"秒"));
            Inventory top=p.getOpenInventory().getTopInventory();
            if(top.getHolder() instanceof Menu m && t.id.equals(m.table) && top.getItem(49)!=null && top.getItem(49).getType()==Material.CLOCK)
                top.setItem(49,item(Material.CLOCK,"§e"+turn,"§7剩餘可摸牌："+s.game.remainingWall(),"§a"+actions,"§7剩餘 "+seconds+" 秒"));
            if(chat)tell(p,turn+"。"+actions+"；/mj 開啟操作選單。");
        }
    }
    private Table seated(Player p){for(Table t:tables.values())for(UUID id:t.seats)if(p.getUniqueId().equals(id))return t;return null;}
    private int seat(Table t,Player p){for(int i=0;i<4;i++)if(p.getUniqueId().equals(t.seats[i]))return i;throw new IllegalArgumentException("請先入座。");}
    private void ensureNear(Player p,Table t){double d=getConfig().getDouble("interaction-distance",8);if(p.getWorld()!=t.location.getWorld()||p.getLocation().distanceSquared(t.location)>d*d)throw new IllegalArgumentException("請靠近牌桌再操作。");}
    private void ensureActive(Table t){if(t.faulted)throw new IllegalArgumentException("此桌資料異常已暫停，請通知管理員。");if(t.session==null)throw new IllegalArgumentException("牌局尚未開始。");}
    private void join(Player p,Table t,int index){
        if(tables.get(t.id)!=t)throw new IllegalArgumentException("這張牌桌已經移除，請重新開啟選單。");
        if(t.faulted)throw new IllegalArgumentException("此桌已暫停，請通知管理員。");
        ensureNear(p,t);if(!p.hasPermission("taiwanmahjong.play"))throw new IllegalArgumentException("沒有使用牌桌權限。");
        Table prior=seated(p);if(prior!=null){if(prior==t){show(p,t);return;}throw new IllegalArgumentException("請先離開原本的牌桌。");}
        if(t.session!=null)throw new IllegalArgumentException("牌局進行中，請等待本局結束。");
        if(index<0){for(int i=0;i<4;i++)if(t.seats[i]==null){index=i;break;}}
        if(index<0||index>3||t.seats[index]!=null)throw new IllegalArgumentException("這個座位已有人。");
        t.seats[index]=p.getUniqueId();t.names[index]=p.getName();t.lobby.join(p.getUniqueId());Arrays.fill(t.ready,false);t.revision++;render(t);refreshMenus(t);
        if(p.getUniqueId().equals(t.lobby.host())&&!t.lobby.selected())modeMenu(p,t);else show(p,t);
        tell(p,"房主："+hostName(t)+"。"+(t.lobby.selected()?terms(t):"等待房主選擇台麻／日麻，再設定金額。"));
        tell(p,"已入座。/mj bots 補電腦免費玩。四位真人都準備才開鑽石局，每人押金 "+required(t)+" 顆；結算先扣押金、不足再扣麻將帳戶，仍付不起才記欠債並挑戰 2 隻伏守者，勝利清債＋64鑽石、死亡清債且保留物品。");
    }
    private MahjongRules tableRules(Table t){
        return t.lobby.mode()==RuleSet.JAPANESE?MahjongRules.japanese(0,t.lobby.perUnit()):rules.withStakes(t.lobby.base(),t.lobby.perUnit());
    }
    private String terms(Table t){return t.lobby.mode()==RuleSet.JAPANESE?"日本立直麻將｜"+(t.lobby.rounds()==4?"東風局":"半莊")+"｜每 1000 點＝"+t.lobby.perUnit()+" 顆鑽石":t.lobby.mode().displayName()+"｜底 "+t.lobby.base()+"＋每台 "+t.lobby.perUnit()+" 顆鑽石";}
    private String hostName(Table t){
        UUID host=t.lobby.host();for(int i=0;i<4;i++)if(host!=null&&host.equals(t.seats[i]))return t.names[i];return "無";
    }
    private void removeWaitingSeat(Table t,int index){
        UUID prior=t.lobby.host();t.lobby.remove(t.seats[index]);t.seats[index]=null;t.names[index]=null;
        Arrays.fill(t.ready,false);t.revision++;hostChanged(t,prior);
    }
    private void hostChanged(Table t,UUID prior){
        if(!Objects.equals(prior,t.lobby.host())&&t.lobby.host()!=null)
            broadcast(t,"房主改由 "+hostName(t)+" 擔任；下局開始前請重新選規則並確認金額。/mj settings");
    }
    private void chooseMode(Player p,Table t,RuleSet mode)throws IOException{
        ensureNear(p,t);if(t.session!=null)throw new IllegalArgumentException("請等本局結束再改規則。");
        LobbySettings before=t.lobby.copy();int oldDealer=t.dealer,oldWind=t.roundWind,oldStreak=t.streak;
        t.lobby.choose(p.getUniqueId(),mode);
        if(before.mode()!=mode){t.dealer=0;t.roundWind=0;t.streak=0;}
        try{saveTables();}catch(IOException e){t.lobby=before;t.dealer=oldDealer;t.roundWind=oldWind;t.streak=oldStreak;throw e;}
        Arrays.fill(t.ready,false);t.revision++;
        for(Player viewer:Bukkit.getOnlinePlayers())tell(viewer,"【"+t.id+"】房主 "+p.getName()+" 選擇「"+mode.displayName()+"」，接著設定"+(mode==RuleSet.JAPANESE?"每 1000 點的鑽石比率":"底與每台價格")+"；本桌玩家需重新準備。");
        render(t);refreshMenus(t);stakesMenu(p,t);
    }
    private void configureStakes(Player p,Table t,int base,int perUnit,boolean confirm)throws IOException{
        ensureNear(p,t);if(t.session!=null)throw new IllegalArgumentException("請等本局結束再改金額。");
        LobbySettings before=t.lobby.copy();t.lobby.stakes(p.getUniqueId(),base,perUnit,confirm);
        try{saveTables();}catch(IOException e){t.lobby=before;throw e;}
        Arrays.fill(t.ready,false);t.revision++;
        if(confirm)broadcast(t,"房主已確認："+terms(t)+"。本局押金仍是每人 "+required(t)+" 顆；同意後請各自點「準備」。");
        render(t);refreshMenus(t);if(!confirm)stakesMenu(p,t);
    }
    private void practice(Player p,Table t)throws IOException{
        ensureNear(p,t);t.lobby.requireHost(p.getUniqueId());start(t,true);
    }
    private void configureRounds(Player p,Table t,int rounds)throws IOException{
        ensureNear(p,t);if(t.session!=null)throw new IllegalArgumentException("請等整場結束再改規則。");
        LobbySettings before=t.lobby.copy();t.lobby.rounds(p.getUniqueId(),rounds);
        try{saveTables();}catch(IOException e){t.lobby=before;throw e;}
        Arrays.fill(t.ready,false);t.revision++;broadcast(t,"房主改為"+(rounds==4?"東風局（東場四局）":"半莊（東、南場八局）")+"，連莊另計；請重新確認金額。 ");render(t);refreshMenus(t);stakesMenu(p,t);
    }
    private List<MahjongGame.Payment> payments(Session s){return s.theoreticalPayments==null?s.game.outcome().payments():s.theoreticalPayments;}
    private long required(Table t){
        Object configured=getConfig().get("escrow-diamonds-per-player",5);
        if(!(configured instanceof Number number) || number.doubleValue()!=number.longValue() || number.longValue()<1 || number.longValue()>MAX_ESCROW_DIAMONDS)
            throw new IllegalArgumentException("每人押金 escrow-diamonds-per-player 必須為 1～"+MAX_ESCROW_DIAMONDS+" 的整數。");
        return number.longValue();
    }
    private void releaseForArena(UUID player){
        for(Table t:tables.values())for(int i=0;i<4;i++)if(player.equals(t.seats[i])){
            if(t.session!=null){
                if(!t.session.practice)throw new IllegalStateException("玩家仍有未結算鑽石牌局，稍後再進競技場。");
                departPractice(t,i);
            }else{removeWaitingSeat(t,i);render(t);refreshMenus(t);}
            return;
        }
    }
    private void registerArenaDebts(Session s)throws IOException{
        if(arena==null || s.practice || !s.arenaDebtEnabled || !s.game.isFinished())return;
        if(s.actualPayments==null)throw new IOException("欠債挑戰缺少已結算付款資料。");
        long[] owed=new long[4],paid=new long[4];
        for(var p:payments(s))owed[p.fromSeat()]=Math.addExact(owed[p.fromSeat()],p.diamonds());
        for(var p:s.actualPayments)paid[p.fromSeat()]=Math.addExact(paid[p.fromSeat()],p.diamonds());
        for(int i=0;i<4;i++)if(owed[i]>paid[i])arena.enroll(s.id,s.seats[i],owed[i]-paid[i]);
    }
    private void ready(Player p,Table t) throws IOException {
        if(t.faulted || tables.get(t.id)!=t)throw new IllegalArgumentException("這張牌桌目前無法開局。");
        ensureNear(p,t);int seat=seat(t,p);if(t.session!=null)throw new IllegalArgumentException("這局已開始。");
        t.lobby.requireConfirmed();
        if(arena!=null && arena.hasDebt(p.getUniqueId()))throw new IllegalArgumentException("請先完成欠債競技場挑戰，再開新的鑽石牌局。/mj arena status 查看狀態。");
        long required=required(t);if(ledger.balance(p.getUniqueId())<required)throw new IllegalArgumentException("帳戶需先存入 "+required+" 顆鑽石作為本局押金；結算不足會再扣麻將帳戶餘額，兩者仍不足才算欠債。未用押金全退回。人數不足四人也能補電腦免費玩。");
        t.ready[seat]=!t.ready[seat];t.revision++;
        boolean all=true;for(int i=0;i<4;i++)if(t.seats[i]==null||!t.ready[i])all=false;
        if(all)start(t,false);else{render(t);refreshMenus(t);}
    }
    private void start(Table t,boolean practice) throws IOException {
        if(tables.get(t.id)!=t || t.faulted)throw new IllegalArgumentException("這張牌桌目前無法開局。");
        if(t.session!=null)throw new IllegalArgumentException("已有進行中的牌局。");
        t.lobby.requireConfirmed();
        Session s=new Session();s.id=UUID.randomUUID().toString();s.tableId=t.id;s.world=t.location.getWorld().getUID();
        s.tableInstance=t.instance;
        s.x=t.location.getX();s.y=t.location.getY();s.z=t.location.getZ();s.seats=t.seats.clone();s.names=t.names.clone();
        s.lobbyOrder=t.lobby.members();
        s.practice=practice;s.bots=new boolean[4];
        s.arenaDebtEnabled=!practice;
        int humans=0;
        for(UUID id:s.seats){
            if(id==null){if(!practice)throw new IllegalArgumentException("鑽石對局需要四位真人都入座、準備；人數不足可補電腦免費玩。");continue;}
            Player player=Bukkit.getPlayer(id);
            if(!practice && arena!=null && arena.hasDebt(id))throw new IllegalArgumentException("有玩家尚未完成欠債挑戰，不能開始新的鑽石牌局。");
            if(player==null || !player.isOnline())throw new IllegalArgumentException("所有已入座的真人都必須在線才能開局。");
            ensureNear(player,t);humans++;
        }
        if(humans==0)throw new IllegalArgumentException("至少一位真人入座才能開始免費對局。");
        if(practice)for(int i=0;i<4;i++)if(s.seats[i]==null){s.seats[i]=UUID.randomUUID();s.names[i]="練習對手 "+(i+1);s.bots[i]=true;}
        MahjongRules handRules=tableRules(t);
        if(handRules.isJapanese()){
            s.japaneseMatch=new JapaneseMatch(t.lobby.rounds(),t.lobby.perUnit());s.japaneseHand=s.japaneseMatch.handId();
            s.game=new MahjongGame(random.nextLong(),s.japaneseMatch.dealer(),s.japaneseMatch.prevailingWind(),s.japaneseMatch.honba(),handRules,true);
            s.game.setJapanesePoints(s.japaneseMatch.points());
        }else s.game=new MahjongGame(random.nextLong(),t.dealer,t.roundWind,t.streak,handRules,true);
        s.deadline=deadline(s.game);
        if(!practice)ledger.reserve(s.id,Arrays.asList(s.seats),required(t),encode(s));
        t.session=s;t.lobby.active(true);System.arraycopy(s.seats,0,t.seats,0,4);System.arraycopy(s.names,0,t.names,0,4);Arrays.fill(t.ready,false);t.revision++;
        t.dealer=s.game.dealer();t.streak=s.game.dealerStreak();t.roundWind=s.game.prevailingWind();
        broadcast(t,practice?"免費對局開始："+humans+" 位真人＋"+(4-humans)+" 位電腦，不收押金、不扣鑽石。有人離開會由電腦接手。":
            "鑽石對局開始，每人押金 "+required(t)+" 顆已保管。"+terms(t)+"；先用押金，再用麻將帳戶餘額，不扣背包。兩者仍不足才記差額欠債，多響按可付款額比例分配。欠債者結算後挑戰 2 隻伏守者；勝利清債＋64鑽石，死亡清債、不噴裝。斷線仍結算。");
        render(t);refreshMenus(t);notifyTurn(t,true);
        if(s.japaneseMatch!=null)broadcast(t,"日麻每人 25,000 點；整場結束依淨輸贏換鑽石，立直供託 1,000 點、流局不聽罰符 3,000 點。"+(practice?"本場免費練習。":"本場押金持續保管；中途離線仍自動代打與結算。"));
        if(s.game.isFinished())finish(t,true);
    }
    private long deadline(MahjongGame game){boolean own=game.phase()==MahjongGame.Phase.DISCARD||game.phase()==MahjongGame.Phase.DRAW;return System.currentTimeMillis()+1000L*Math.max(5,getConfig().getInt(own?"turn-seconds":"claim-seconds",own?45:12));}
    private void mutate(Table t,Consumer<MahjongGame> action) throws IOException {
        ensureActive(t);Session s=t.session;byte[] before=encode(s);var phase=s.game.phase();int turn=s.game.currentTurn();int wall=s.game.remainingWall();
        int discardedBefore=0;for(int i=0;i<4;i++)discardedBefore+=s.game.discards(i).size();
        boolean[] listeningBefore=new boolean[4];for(int i=0;i<4;i++)listeningBefore[i]=s.game.declaredListening(i);
        List<List<Meld>> meldsBefore=new ArrayList<>();for(int i=0;i<4;i++)meldsBefore.add(List.copyOf(s.game.melds(i)));
        try{
            action.accept(s.game);
            if(s.japaneseMatch!=null){
                for(int i=0;i<4;i++)if(s.game.riichiEstablished(i))s.japaneseMatch.declareRiichi(s.japaneseHand,i);
                s.game.setJapanesePoints(s.japaneseMatch.points());
            }
            if(phase!=s.game.phase()||turn!=s.game.currentTurn()||wall!=s.game.remainingWall())s.deadline=deadline(s.game);
            if(!s.practice)ledger.saveGame(s.id,encode(s));
        }catch(RuntimeException ex){t.session=decode(before);if(!(ex instanceof IllegalArgumentException))t.faulted=true;throw ex;}
        catch(IOException ex){t.session=decode(before);t.faulted=true;render(t);throw ex;}
        t.revision++;
        for(int i=0;i<4;i++)if(!listeningBefore[i]&&s.game.declaredListening(i))celebrations.announceListening(t.location,s.names[i],s.practice,s.game.isJapanese());
        for(int i=0;i<4;i++){
            List<Meld> previous=meldsBefore.get(i),now=s.game.melds(i);
            for(int j=0;j<now.size();j++)if(j>=previous.size()||!now.get(j).equals(previous.get(j))){
                MahjongActionEffects.Action effect=switch(now.get(j).kind()){case SEQUENCE->MahjongActionEffects.Action.CHI;case TRIPLET->MahjongActionEffects.Action.PON;case QUAD->MahjongActionEffects.Action.KAN;};
                actionEffects.announceAction(t.location,s.names,s.names[i],effect);
            }
        }
        int discardedAfter=0;for(int i=0;i<4;i++)discardedAfter+=s.game.discards(i).size();
        if(discardedAfter>discardedBefore && s.game.phase()!=MahjongGame.Phase.CLAIM)
            broadcast(t,s.names[s.game.lastDiscarder()]+" 打出「"+Tiles.name(s.game.lastTile())+"」。");
        if(s.game.isFinished())finish(t,true);else{
            render(t);refreshMenus(t);
            boolean changed=phase!=s.game.phase()||turn!=s.game.currentTurn()||wall!=s.game.remainingWall();
            notifyTurn(t,changed);
            if(phase==MahjongGame.Phase.DRAW && s.game.phase()==MahjongGame.Phase.DISCARD && s.game.lastDraw()>=0){
                Player owner=Bukkit.getPlayer(s.seats[s.game.currentTurn()]);if(owner!=null)tell(owner,"你摸到了「"+Tiles.name(s.game.lastDraw())+"」，請選擇手牌出牌。");
            }
        }
    }
    private void finish(Table t) throws IOException {
        finish(t,false);
    }
    private void finish(Table t,boolean celebrate) throws IOException {
        Session s=t.session;var outcome=s.game.outcome();
        if(s.japaneseMatch!=null){
            if(!finishJapaneseHand(t,celebrate))return;
            celebrate=false;
        }
        boolean announce=celebrate&&s.endedAt==0;
        boolean dealerWon=outcome.winners().stream().anyMatch(w->w.seat()==s.game.dealer());
        if(s.endedAt==0){
            s.endedAt=Math.max(System.currentTimeMillis(),t.lastSettlementAt+1);s.nextDealer=t.dealer;s.nextStreak=t.streak;s.nextWind=t.roundWind;
            if(outcome.draw()||dealerWon)s.nextStreak++;else{s.nextStreak=0;s.nextDealer=(t.dealer+1)%4;if(s.nextDealer==0)s.nextWind=(t.roundWind+1)%4;}
            if(s.japaneseMatch!=null){s.nextDealer=0;s.nextStreak=0;s.nextWind=0;}
        }
        if(!s.practice){
            // Quote and commit against the same wallet snapshot. Recalculate
            // OPEN games on retry; never charge wallets again for settled history.
            synchronized(ledger){
                var saved=ledger.game(s.id).orElseThrow(()->new IOException("找不到本局押金帳本，已停止結算。"));
                if(!saved.players().equals(Arrays.asList(s.seats)))throw new IOException("本局結算座位與押金不一致。");
                if(saved.status()==DiamondLedger.GameStatus.SETTLED){
                    Session committed=decode(saved.attachment());
                    s.endedAt=committed.endedAt;s.nextDealer=committed.nextDealer;
                    s.nextStreak=committed.nextStreak;s.nextWind=committed.nextWind;
                    // Pre-cap historical attachments may not carry actual payments.
                    s.actualPayments=committed.actualPayments!=null?committed.actualPayments:
                        EscrowSettlement.cap(payments(s),new long[]{saved.amountEach(),saved.amountEach(),saved.amountEach(),saved.amountEach()});
                }else{
                    if(saved.status()!=DiamondLedger.GameStatus.OPEN)throw new IOException("本局已取消，不能結算。");
                    long[] seatCaps=new long[4];
                    for(int i=0;i<4;i++){
                        Long amount=saved.escrow().get(s.seats[i]);
                        if(amount==null || amount<0)throw new IOException("本局缺少座位押金，已停止結算。");
                        seatCaps[i]=Math.addExact(amount,ledger.balance(s.seats[i]));
                    }
                    s.actualPayments=EscrowSettlement.cap(payments(s),seatCaps);
                }
                List<DiamondLedger.Transfer> transfers=new ArrayList<>();
                for(var payment:s.actualPayments)if(payment.diamonds()>0)
                    transfers.add(new DiamondLedger.Transfer(s.seats[payment.fromSeat()],s.seats[payment.toSeat()],payment.diamonds()));
                ledger.settleWithWallet(s.id,transfers,encode(s));
            }
            registerArenaDebts(s);
        }
        if(s.japaneseMatch!=null)broadcast(t,"日麻整場結束："+s.japaneseMatch.endReason()+"。"+pointSummary(s));
        if(s.japaneseMatch==null&&outcome.draw())broadcast(t,"本局流局："+outcome.reason()+(s.practice?"。免費對局不扣鑽石。":"。押金全數退回帳戶。"));
        if(s.japaneseMatch==null)for(var win:outcome.winners()){
            String detail=win.score().breakdown().stream().map(x->x.name()+" "+x.tai()+s.game.rules().unitLabel()).collect(Collectors.joining("、"));
            if(s.game.isJapanese())detail+="（"+win.score().tai()+"飜／"+win.score().fu()+"符）";
            broadcast(t,s.names[win.seat()]+(outcome.selfDraw()?" 自摸！":" 胡牌！")+detail);
            if(outcome.reason().contains("七搶一") || outcome.reason().contains("八仙"))broadcast(t,"胡牌花牌："+String.join(" ",labels(s.game.flowers(win.seat()))));
            else{List<Integer> shown=new ArrayList<>(s.game.hands(win.seat()));
                if(!outcome.selfDraw() && s.game.lastTile()>=0)shown.add(s.game.lastTile());
                broadcast(t,"胡牌手牌："+String.join(" ",labels(shown)));}
        }
        for(int i=0;i<payments(s).size();i++){
            var theoretical=payments(s).get(i);var payment=s.practice?theoretical:s.actualPayments.get(i);
            String capped=!s.practice && payment.diamonds()!=theoretical.diamonds()?"（原應付 "+theoretical.diamonds()+" 顆；押金＋麻將帳戶不足，按可付款額比例分配，未付差額記欠債）":"";
            broadcast(t,s.names[payment.fromSeat()]+" → "+s.names[payment.toSeat()]+"："+(s.japaneseMatch==null?payment.tai()+" "+s.game.rules().unitLabel()+"，":"點數淨輸贏換算，")+
                (s.practice?"參考 ":"實付 ")+payment.diamonds()+" 顆鑽石"+(s.practice?"（免費對局，不扣款）":capped));
        }
        t.dealer=s.nextDealer;t.streak=s.nextStreak;t.roundWind=s.nextWind;t.lastSettlementAt=s.endedAt;
        UUID priorHost=t.lobby.host();
        for(int i=0;i<4;i++)if(s.bots[i]||Bukkit.getPlayer(t.seats[i])==null){t.lobby.remove(t.seats[i]);t.seats[i]=null;t.names[i]=null;}
        t.session=null;t.lobby.active(false);Arrays.fill(t.ready,false);t.revision++;saveTables();hostChanged(t,priorHost);
        if(announce){
            celebrations.announceFinished(t.location,s.names,outcome,s.practice,s.game.rules().unitLabel());
            for(var win:outcome.winners())actionEffects.announceWin(t.location,s.names,s.names[win.seat()],win.score().tai(),s.game.rules().unitLabel(),outcome.selfDraw(),outcome.sourceSeat()<0?null:s.names[outcome.sourceSeat()]);
        }
        render(t);refreshMenus(t);
    }
    private String pointSummary(Session s){
        List<String> parts=new ArrayList<>();for(int i=0;i<4;i++)parts.add(s.names[i]+" "+s.japaneseMatch.points(i)+" 點");
        return String.join("｜",parts)+"｜供託 "+s.japaneseMatch.riichiPot()+" 點";
    }
    /** One durable hand result, then a fresh hand; escrow remains held for the whole Japanese match. */
    private boolean finishJapaneseHand(Table t,boolean celebrate)throws IOException{
        Session s=t.session;
        if(!s.japaneseHandCommitted){
            byte[] before=encode(s);
            try{
                s.japaneseMatch.applyHand(s.japaneseHand,s.game);
                s.japaneseHandCommitted=true;s.deadline=System.currentTimeMillis()+8000;
                if(s.japaneseMatch.finished())s.theoreticalPayments=s.japaneseMatch.finalPayments();
                if(!s.practice)ledger.saveGame(s.id,encode(s));
            }catch(IOException|RuntimeException failure){t.session=decode(before);t.faulted=true;throw failure;}
            t.revision++;
            var outcome=s.game.outcome();
            if(outcome.draw())broadcast(t,"本局流局；聽牌："+outcome.tenpaiSeats().stream().map(i->s.names[i]).collect(Collectors.joining("、"))+"。不聽罰符已按 3,000 點分配。 ");
            for(var win:outcome.winners()){
                String detail=win.score().breakdown().stream().map(x->x.name()+" "+x.tai()+"飜").collect(Collectors.joining("、"));
                broadcast(t,s.names[win.seat()]+(outcome.selfDraw()?" 自摸！":" 榮和！")+win.score().tai()+"飜 "+win.score().fu()+"符｜"+detail);
                List<Integer> tiles=new ArrayList<>(s.game.hands(win.seat()));if(!outcome.selfDraw()&&s.game.lastTile()>=0)tiles.add(s.game.lastTile());
                broadcast(t,"胡牌手牌："+String.join(" ",labels(tiles)));
            }
            if(!s.game.uraDoraIndicators().isEmpty())broadcast(t,"裏寶牌指示牌："+String.join("、",labels(s.game.uraDoraIndicators())));
            long[] deltas=s.japaneseMatch.lastPointDeltas();for(int i=0;i<4;i++)if(deltas[i]!=0)broadcast(t,s.names[i]+" 本局結算 "+(deltas[i]>0?"+":"")+deltas[i]+" 點");
            broadcast(t,pointSummary(s));
            if(celebrate){
                celebrations.announceFinished(t.location,s.names,outcome,s.practice,"飜");
                for(var win:outcome.winners())actionEffects.announceWin(t.location,s.names,s.names[win.seat()],win.score().tai(),"飜",outcome.selfDraw(),outcome.sourceSeat()<0?null:s.names[outcome.sourceSeat()]);
            }
            if(!s.japaneseMatch.finished())broadcast(t,"8 秒後自動開始下一局；押金保留，整場結束才換算鑽石。");
            render(t);refreshMenus(t);
        }
        if(s.japaneseMatch.finished())return true;
        if(System.currentTimeMillis()<s.deadline)return false;
        byte[] before=encode(s);
        try{
            s.game=new MahjongGame(random.nextLong(),s.japaneseMatch.dealer(),s.japaneseMatch.prevailingWind(),s.japaneseMatch.honba(),s.game.rules(),true);
            s.japaneseHand=s.japaneseMatch.handId();s.japaneseHandCommitted=false;
            s.game.setJapanesePoints(s.japaneseMatch.points());s.deadline=deadline(s.game);
            if(!s.practice)ledger.saveGame(s.id,encode(s));
        }catch(IOException|RuntimeException failure){t.session=decode(before);t.faulted=true;throw failure;}
        t.dealer=s.game.dealer();t.streak=s.game.dealerStreak();t.roundWind=s.game.prevailingWind();t.revision++;
        broadcast(t,"開始 "+Tiles.name(27+t.roundWind)+(t.dealer+1)+"局、"+s.japaneseMatch.honba()+"本場。"+pointSummary(s));
        render(t);refreshMenus(t);notifyTurn(t,true);return false;
    }
    private void tick(){
        if(!operational)return;
        for(Table t:new ArrayList<>(tables.values())){
            if(t.session==null||t.faulted)continue;
            try{
                Session s=t.session;
                if(s.game.isFinished()){finish(t);continue;}
                if(s.game.phase()==MahjongGame.Phase.DISCARD || s.game.phase()==MahjongGame.Phase.DRAW){
                    int turn=s.game.currentTurn();if(s.bots[turn]||System.currentTimeMillis()>=s.deadline)mutate(t,g->{if(s.bots[turn])PracticeBot.act(g,turn);else g.autoAction(turn);});
                }else{
                    for(int i=0;i<4 && t.session==s && !s.game.isFinished() && s.game.phase()!=MahjongGame.Phase.DISCARD && s.game.phase()!=MahjongGame.Phase.DRAW;i++){
                        int seat=i;if(!s.game.validActions(i).isEmpty()&&(s.bots[i]||System.currentTimeMillis()>=s.deadline))mutate(t,g->{if(s.bots[seat])PracticeBot.act(g,seat);else g.autoAction(seat);});
                    }
                }
                if(t.session!=null){render(t);notifyTurn(t,false);}
            }catch(Exception e){t.faulted=true;getLogger().log(java.util.logging.Level.SEVERE,"牌桌 "+t.id+" 已暫停，原始帳本保留",e);broadcast(t,"牌局已暫停，請通知管理員檢查；鑽石仍在帳本內。");}
        }
    }
    private void leave(Player p,Table t){
        int seat=seat(t,p);
        if(t.session!=null){
            if(!t.session.practice)throw new IllegalArgumentException("鑽石對局開始後不能中途退押金；離線會自動打牌，結束後可領回餘額。");
            departPractice(t,seat);
        }else{
            removeWaitingSeat(t,seat);render(t);refreshMenus(t);
        }
        p.closeInventory();tell(p,"已離開牌桌。免費對局若還有其他真人，會由電腦接手你的座位；鑽石帳戶可用 /mj withdraw 數量 領回。");
    }
    private void departPractice(Table t,int index){
        Session s=t.session;
        if(s==null || !s.practice || s.bots[index])throw new IllegalArgumentException("這個座位不是免費對局的真人。");
        String former=s.names[index];UUID previousHost=t.lobby.host();t.lobby.remove(s.seats[index]);UUID bot=UUID.randomUUID();String name="練習對手 "+(index+1);
        s.seats[index]=bot;s.names[index]=name;s.bots[index]=true;t.seats[index]=bot;t.names[index]=name;
        Arrays.fill(t.ready,false);t.revision++;
        boolean humanRemains=false;for(boolean isBot:s.bots)if(!isBot){humanRemains=true;break;}
        if(humanRemains)broadcast(t,former+" 已離開免費對局，由電腦接手；其他真人可繼續玩，不扣鑽石。");
        else{t.session=null;t.lobby.active(false);Arrays.fill(t.seats,null);Arrays.fill(t.names,null);}
        hostChanged(t,previousHost);render(t);refreshMenus(t);
    }

    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args){
        if(!(sender instanceof Player p)){sender.sendMessage("麻將狀態：牌桌="+tables.size()+"，進行中的鑽石牌局="+(ledger==null?0:ledger.openGames().size()));return true;}
        guard(p,()->{
            if(!operational)throw new IllegalArgumentException("麻將目前尚未就緒。");
            String sub=args.length==0?"menu":args[0].toLowerCase(Locale.ROOT);Table t=seated(p);
            switch(sub){
                case "arena"->arena.command(p,Arrays.copyOfRange(args,1,args.length));
                case "help","rules"->rulesBook(p);
                case "balance"->tell(p,"可領取餘額："+ledger.balance(p.getUniqueId())+" 顆鑽石；進行中對局的押金另行保管。");
                case "wallet","bank"->walletMenu(p,1);
                case "deposit","withdraw"->{if(args.length<2)throw new IllegalArgumentException("用法：/mj "+sub+" 數量");diamonds.transfer(p,Integer.parseInt(args[1]),sub.equals("deposit"));}
                case "list"->{int page=args.length>1?Integer.parseInt(args[1]):1;if(page<1)throw new IllegalArgumentException("頁碼必須大於零。");listMenu(p,page-1);}
                case "join"->{if(args.length<2){listMenu(p);break;}Table target=table(args[1]);join(p,target,-1);}
                case "tp"->{if(args.length<2)throw new IllegalArgumentException("用法：/mj tp 牌桌名稱");Table target=table(args[1]);
                    Location dest=target.location.clone().add(0,0,2.3);dest.setYaw(180);if(!dest.getBlock().isPassable()||!dest.clone().add(0,1,0).getBlock().isPassable()||!dest.clone().add(0,-1,0).getBlock().getType().isSolid())throw new IllegalArgumentException("桌邊目前不安全，請步行靠近。");p.teleport(dest);}
                case "create"->{admin(p);if(args.length<2||!args[1].matches("[\\p{L}\\p{N}_-]{1,24}"))throw new IllegalArgumentException("用法：/mj create 牌桌名稱（文字、數字、底線）");
                    if(tables.containsKey(args[1]))throw new IllegalArgumentException("牌桌名稱已存在。");
                    Location loc=p.getLocation().getBlock().getLocation().add(.5,0,.5);if(!clearSite(loc))throw new IllegalArgumentException("請選擇至少 5×5 平坦空地，上方留 3 格空間。");
                    for(Table existing:tables.values())if(existing.location.getWorld()==loc.getWorld()&&existing.location.distanceSquared(loc)<49)throw new IllegalArgumentException("兩張牌桌至少相距 7 格。");
                    Table created=newTable(args[1],loc);tables.put(created.id,created);saveTables();createView(created);tell(p,"已建立 3×3 立體牌桌 "+created.id+"。右鍵桌邊座位即可入座。");}
                case "remove"->{admin(p);if(args.length<2)throw new IllegalArgumentException("用法：/mj remove 牌桌名稱");Table target=table(args[1]);if(target.session!=null)throw new IllegalArgumentException("請等本局結算後再移除牌桌。");target.view.close();tables.remove(target.id);saveTables();tell(p,"牌桌已移除，個人鑽石帳戶不受影響。");}
                case "ready"->{if(t==null)throw new IllegalArgumentException("請先入座。");ready(p,t);}
                case "settings"->{if(t==null)throw new IllegalArgumentException("請先入座。");t.lobby.requireHost(p.getUniqueId());if(!t.lobby.selected())modeMenu(p,t);else stakesMenu(p,t);}
                case "mode"->{if(t==null)throw new IllegalArgumentException("請先入座。");if(args.length<2){modeMenu(p,t);break;}
                    RuleSet mode=switch(args[1].toLowerCase(Locale.ROOT)){case "tw","taiwan","台麻"->RuleSet.TAIWAN;case "jp","japanese","日麻"->RuleSet.JAPANESE;default->throw new IllegalArgumentException("用法：/mj mode tw 或 /mj mode jp");};chooseMode(p,t,mode);}
                case "stakes"->{if(t==null)throw new IllegalArgumentException("請先入座。");
                    if(t.lobby.mode()==RuleSet.JAPANESE){if(args.length!=2)throw new IllegalArgumentException("日麻用法：/mj stakes 每1000點幾顆鑽石；例 /mj stakes 2");configureStakes(p,t,t.lobby.base(),Integer.parseInt(args[1]),true);}
                    else{if(args.length!=3)throw new IllegalArgumentException("台麻用法：/mj stakes 底 每台鑽石數；例 /mj stakes 5 2");configureStakes(p,t,Integer.parseInt(args[1]),Integer.parseInt(args[2]),true);}show(p,t);}
                case "length"->{if(t==null)throw new IllegalArgumentException("請先入座。");if(args.length!=2)throw new IllegalArgumentException("用法：/mj length east（東風）或 south（半莊）");int rounds=switch(args[1].toLowerCase(Locale.ROOT)){case "east","4","東風"->4;case "south","8","半莊"->8;default->throw new IllegalArgumentException("請選 east 或 south。");};configureRounds(p,t,rounds);}
                case "listen","riichi"->{if(t==null||t.session==null)throw new IllegalArgumentException("請先入座並開始牌局。");ensureNear(p,t);listeningMenu(p,t);}
                case "practice","bots"->{if(t==null)throw new IllegalArgumentException("請先右鍵空桌入座，再使用 /mj practice 或 /mj bots 免費補電腦。");practice(p,t);}
                case "leave"->{if(t==null)throw new IllegalArgumentException("你目前沒有入座。");leave(p,t);}
                case "menu"->{if(t==null)listMenu(p);else show(p,t);}
                default->throw new IllegalArgumentException("使用 /mj 開啟選單，/mj help 查看中文玩法。");
            }
        });return true;
    }
    private Table table(String id){Table t=tables.get(id);if(t==null)throw new IllegalArgumentException("找不到這張牌桌。");return t;}
    private void admin(Player p){if(!p.hasPermission("taiwanmahjong.admin"))throw new IllegalArgumentException("只有管理員可以建立或移除牌桌。");}
    @Override public List<String> onTabComplete(CommandSender sender,Command c,String label,String[] a){
        List<String> choices=a.length==1?List.of("help","list","join","tp","settings","mode","stakes","length","ready","listen","riichi","practice","bots","arena","leave","deposit","withdraw","balance","wallet","bank","create","remove"):
            a.length==2&&a[0].equalsIgnoreCase("mode")?List.of("tw","jp"):
            a.length==2&&a[0].equalsIgnoreCase("length")?List.of("east","south"):
            a.length==2&&a[0].equalsIgnoreCase("arena")?List.of("watch","leave","status"):
            a.length==2&&List.of("join","tp","remove").contains(a[0])?new ArrayList<>(tables.keySet()):List.of();
        String prefix=a[a.length-1].toLowerCase(Locale.ROOT);return choices.stream().filter(x->x.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
    }
    void tell(Player p,String message){p.sendMessage(LEGACY.deserialize("§2[麻將] §f"+message));}
    private void broadcast(Table t,String message){for(int i=0;i<4;i++){Player p=t.seats[i]==null?null:Bukkit.getPlayer(t.seats[i]);if(p!=null)tell(p,message);}}
    @FunctionalInterface private interface CheckedAction{void run()throws Exception;}
    private void guard(Player p,CheckedAction action){try{action.run();}catch(NumberFormatException ex){tell(p,"請輸入有效的整數。");}
        catch(IllegalArgumentException ex){tell(p,ex.getMessage());}
        catch(Exception ex){getLogger().log(java.util.logging.Level.SEVERE,"麻將操作失敗："+p.getUniqueId(),ex);tell(p,"這次操作未完成，請通知管理員檢查；不要重複存領鑽石。");}}

    private Menu menu(String title,Table t){Menu holder=new Menu(t==null?null:t.id,t==null?0:t.revision);holder.inventory=Bukkit.createInventory(holder,54,Component.text(title));return holder;}
    private static ItemStack item(Material material,String name,String... lore){ItemStack i=new ItemStack(material);ItemMeta meta=i.getItemMeta();meta.displayName(LEGACY.deserialize(name));meta.lore(Arrays.stream(lore).map(LEGACY::deserialize).toList());i.setItemMeta(meta);return i;}
    private void button(Menu m,int slot,Material material,String name,Consumer<Player> action,String... lore){m.inventory.setItem(slot,item(material,name,lore));if(action!=null)m.actions.put(slot,action);}
    private void tileButton(Menu m,int slot,int tile,String name,Consumer<Player> action,String... lore){m.inventory.setItem(slot,TileArt.decorate(item(Material.PAPER,name,lore),tile));if(action!=null)m.actions.put(slot,action);}
    private void walletMenu(Player p,int selected){
        if(!p.hasPermission("taiwanmahjong.play"))throw new IllegalArgumentException("沒有使用麻將帳戶權限。");
        walletInput.remove(p.getUniqueId());
        int amount=Math.max(1,Math.min(2304,selected));Menu m=menu("麻將鑽石帳戶｜自訂存領數量",null);m.wallet=true;m.walletAmount=amount;
        button(m,4,Material.CHEST,"§b帳戶餘額："+ledger.balance(p.getUniqueId())+" 顆",null,"§7背包原版鑽石："+DiamondInventory.count(p),"§eShift＋點下方背包的鑽石：整組存入","§7只收原版鑽石，特殊寶石不會被扣除");
        int[] slots={10,12,14},presets={1,16,64};
        for(int i=0;i<slots.length;i++){int value=presets[i];button(m,slots[i],Material.DIAMOND,"§e選擇 "+value+" 顆",q->walletMenu(q,value));}
        button(m,16,Material.NAME_TAG,"§a自行輸入數量",this::promptWalletAmount,"§7點擊後在聊天欄輸入 1～2304","§7輸入只選數量，再點存入或領回才交易","§7輸入 cancel 可取消，60秒內有效");
        int[] changeSlots={19,20,24,25},changes={-64,-1,1,64};
        for(int i=0;i<changes.length;i++){int delta=changes[i];button(m,changeSlots[i],delta>0?Material.LIME_DYE:Material.RED_DYE,"§e數量 "+(delta>0?"+":"")+delta,q->walletMenu(q,amount+delta));}
        button(m,22,Material.PAPER,"§e目前數量："+amount+" 顆",null,"§7上方可選數量、加減或直接輸入");
        button(m,29,Material.DIAMOND,"§b存入 "+amount+" 顆",q->guard(q,()->walletTransfer(q,amount,true,amount)),"§7由背包存入自己的麻將帳戶");
        button(m,33,Material.HOPPER,"§b領回 "+amount+" 顆",q->guard(q,()->walletTransfer(q,amount,false,amount)),"§7由自己的帳戶領回背包");
        button(m,39,Material.DIAMOND_BLOCK,"§a全部存入",q->guard(q,()->walletTransfer(q,DiamondInventory.count(q),true,amount)),"§7存入背包全部原版鑽石；目前 "+DiamondInventory.count(p)+" 顆");
        int max=(int)Math.min(ledger.balance(p.getUniqueId()),DiamondInventory.capacity(p));
        button(m,41,Material.CHEST_MINECART,"§a領回最多（"+max+" 顆）",q->guard(q,()->walletTransfer(q,(int)Math.min(ledger.balance(q.getUniqueId()),DiamondInventory.capacity(q)),false,amount)),"§7領取背包放得下的數量","§7剩餘鑽石留在帳戶，不會掉到地上");
        button(m,49,Material.BOOK,"§eShift＋點背包鑽石即可整組存入",null,"§7需要精確數量就點「自行輸入數量」","§7可隨時 /mj wallet 開啟，不必入座");
        button(m,53,Material.ARROW,"§7返回",q->{Table t=seated(q);if(t!=null&&q.getWorld()==t.location.getWorld()&&q.getLocation().distanceSquared(t.location)<=64)show(q,t);else listMenu(q);});
        p.openInventory(m.inventory);
    }
    private void walletTransfer(Player p,int amount,boolean deposit,int selected)throws IOException{
        walletTransfer(p,amount,deposit,selected,-1);
    }
    private void walletTransfer(Player p,int amount,boolean deposit,int selected,int preferredSlot)throws IOException{
        if(!p.getItemOnCursor().getType().isAir())throw new IllegalArgumentException("請先關閉選單，把滑鼠拿著的物品放回背包，再存領。");
        if(amount==0)throw new IllegalArgumentException(deposit?"背包沒有可存入的原版鑽石。":"沒有可領餘額，或背包已滿。");
        diamonds.transfer(p,amount,deposit,preferredSlot);walletMenu(p,selected);
    }
    private void promptWalletAmount(Player p){
        p.closeInventory();walletInput.put(p.getUniqueId(),System.currentTimeMillis()+60000);
        tell(p,"請在聊天欄輸入存領數量（1～2304），只選數量、不會立刻交易。輸入 cancel 取消。");
    }
    @EventHandler(priority=EventPriority.LOWEST)public void walletChat(AsyncChatEvent event){
        UUID id=event.getPlayer().getUniqueId();Long expires=walletInput.get(id);
        if(expires==null)return;
        if(expires<System.currentTimeMillis()){walletInput.remove(id,expires);return;}
        event.setCancelled(true);String input=PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        Bukkit.getScheduler().runTask(this,()->{
            Player p=event.getPlayer();if(!operational||!p.isOnline()||!Objects.equals(walletInput.get(id),expires))return;
            if(input.equalsIgnoreCase("cancel")||input.equals("取消")){walletMenu(p,1);return;}
            try{int amount=Integer.parseInt(input);if(amount<1||amount>2304)throw new NumberFormatException();walletMenu(p,amount);}
            catch(NumberFormatException invalid){tell(p,"請輸入 1～2304 的整數，或 cancel 取消。");}
        });
    }
    private void modeMenu(Player p,Table t){
        ensureNear(p,t);t.lobby.requireHost(p.getUniqueId());if(t.session!=null)throw new IllegalArgumentException("本局已開始，結算後才能設定。");
        Menu m=menu("房主設定 1／2｜先選規則",t);
        button(m,20,Material.SUNFLOWER,"§a台灣十六張",q->guard(q,()->chooseMode(q,t,RuleSet.TAIWAN)),"§7十六張＋花牌，五組面子＋一對將眼","§7沿用本服台型，宣告聽牌可自由選擇","§e選擇後全服公告，再設定底與每台價格");
        button(m,24,Material.RED_DYE,"§c日本十三張立直",q->guard(q,()->chooseMode(q,t,RuleSet.JAPANESE)),"§7十三張、無花無赤，至少一役才能胡","§7立直、振聽、寶牌、符數、七對子、國士無雙","§e每人25000點，整場淨輸贏再換算鑽石","§e選擇後全服公告，再選東風／半莊與換算率");
        button(m,49,Material.BOOK,"§e第一位入座的玩家擔任房主",null,"§7開局前可改規則，修改會取消全桌準備","§7房主離開，由最早仍在桌上的玩家接任");
        button(m,53,Material.ARROW,"返回牌桌",q->show(q,t));p.openInventory(m.inventory);
    }
    private void stakesMenu(Player p,Table t){
        ensureNear(p,t);t.lobby.requireHost(p.getUniqueId());if(t.session!=null)throw new IllegalArgumentException("本局已開始，結算後才能設定。");
        if(!t.lobby.selected()){modeMenu(p,t);return;}
        if(t.lobby.mode()==RuleSet.JAPANESE){japaneseSettingsMenu(p,t);return;}
        Menu m=menu("房主設定 2／2｜底與每"+t.lobby.mode().unit(),t);
        button(m,4,Material.BOOK,"§e"+t.lobby.mode().displayName(),null,"§7規則已公告；金額確認後大家重新準備","§7可輸入任意非負整數（上限 2147483647）","§f/mj stakes 底 每"+t.lobby.mode().unit()+"價格","§7例如 /mj stakes 5 2");
        button(m,20,Material.DIAMOND,"§b底："+t.lobby.base()+" 顆",null,"§7每筆胡牌基本付款，不是入桌押金");
        button(m,24,Material.EMERALD,"§b每"+t.lobby.mode().unit()+"："+t.lobby.perUnit()+" 顆",null,"§7每筆付款＝底＋台／飜數×價格");
        int[] changes={-10,-1,1,10};int[] baseSlots={10,19,28,37},unitSlots={14,23,32,41};
        for(int i=0;i<changes.length;i++){int delta=changes[i];String label=(delta>0?"+":"")+delta;
            button(m,baseSlots[i],delta>0?Material.LIME_DYE:Material.RED_DYE,"§e底 "+label,q->guard(q,()->configureStakes(q,t,adjust(t.lobby.base(),delta),t.lobby.perUnit(),false)));
            button(m,unitSlots[i],delta>0?Material.LIME_DYE:Material.RED_DYE,"§e每"+t.lobby.mode().unit()+" "+label,q->guard(q,()->configureStakes(q,t,t.lobby.base(),adjust(t.lobby.perUnit(),delta),false)));
        }
        button(m,45,Material.ARROW,"§e返回選擇規則",q->modeMenu(q,t));
        button(m,49,Material.LIME_CONCRETE,"§a確認本桌金額",q->guard(q,()->{configureStakes(q,t,t.lobby.base(),t.lobby.perUnit(),true);show(q,t);}),"§f"+terms(t),"§7確認會通知全桌；各玩家同意後點準備","§7每人押金仍為 "+required(t)+" 顆");
        button(m,53,Material.OAK_DOOR,"返回牌桌",q->show(q,t));p.openInventory(m.inventory);
    }
    private static int adjust(int value,int delta){return (int)Math.max(0,Math.min(Integer.MAX_VALUE,(long)value+delta));}
    private void japaneseSettingsMenu(Player p,Table t){
        Menu m=menu("房主設定 2／2｜日麻點數換鑽石",t);
        button(m,4,Material.BOOK,"§e每人 25,000 點，整場結束換算",null,"§7符＋飜算點，含滿貫／跳滿／倍滿／三倍滿／役滿","§7立直1000供託、流局3000不聽罰符、本場加點","§f/mj stakes 每1000點鑽石數（例 /mj stakes 2）");
        button(m,20,Material.SUNFLOWER,"§e東風局（東場4局）",q->guard(q,()->configureRounds(q,t,4)),"§7連莊另計，未選則預設東風");
        button(m,24,Material.WITHER_ROSE,"§b半莊（東、南場8局）",q->guard(q,()->configureRounds(q,t,8)),"§7連莊另計；有人點數變負時提前結束");
        button(m,22,Material.DIAMOND,"§b每 1000 點＝"+t.lobby.perUnit()+" 顆鑽石",null,"§7以相對25000的最終淨輸贏換算","§7鑽石總額向上取整；依餘數分配，不抽水","§7本場押金只收一次：每人 "+required(t)+" 顆");
        int[] slots={28,30,32,34},changes={-10,-1,1,10};
        for(int i=0;i<slots.length;i++){int delta=changes[i];button(m,slots[i],delta>0?Material.LIME_DYE:Material.RED_DYE,"§e換算率 "+(delta>0?"+":"")+delta,q->guard(q,()->configureStakes(q,t,t.lobby.base(),adjust(t.lobby.perUnit(),delta),false)));}
        button(m,45,Material.ARROW,"§e重新選規則",q->modeMenu(q,t));
        button(m,49,Material.LIME_CONCRETE,"§a確認本場設定",q->guard(q,()->{configureStakes(q,t,t.lobby.base(),t.lobby.perUnit(),true);show(q,t);}),"§f"+terms(t),"§7確認後所有玩家重新準備；開局後鎖定設定","§7免費練習也採點數制，但完全不扣鑽石");
        button(m,53,Material.OAK_DOOR,"返回牌桌",q->show(q,t));p.openInventory(m.inventory);
    }
    private void listMenu(Player p){listMenu(p,0);}
    private void listMenu(Player p,int requestedPage){
        List<Table> listed=new ArrayList<>(tables.values());int pages=Math.max(1,(listed.size()+44)/45);
        int page=Math.max(0,Math.min(requestedPage,pages-1));
        Menu m=menu("麻將牌桌｜"+(page+1)+"／"+pages+"頁",null);int slot=0;
        for(Table t:listed.subList(page*45,Math.min(listed.size(),(page+1)*45))){
        int count=(int)Arrays.stream(t.seats).filter(Objects::nonNull).count();button(m,slot++,Material.GREEN_CONCRETE,t.id+"（"+count+"／4）",q->guard(q,()->{Table target=table(t.id);ensureNear(q,target);join(q,target,-1);}),
            "§7世界："+t.location.getWorld().getName(),"§7座標："+t.location.getBlockX()+" "+t.location.getBlockY()+" "+t.location.getBlockZ(),
            "§a人數不足四人可補電腦免費玩","§7四位真人都準備才開鑽石局","§e靠近後點選入座；/mj tp "+t.id);}
        button(m,45,Material.BOOK,"§e中文玩法與台數",this::rulesBook);
        button(m,46,Material.SPYGLASS,"§b天空競技場觀眾席",q->guard(q,()->arena.command(q,new String[]{"watch"})),"§7觀看欠債者挑戰兩隻伏守者","§7離開：/mj arena leave");
        if(page>0)button(m,47,Material.ARROW,"§e上一頁",q->listMenu(q,page-1));
        button(m,50,Material.PAPER,"§f第 "+(page+1)+"／"+pages+" 頁",null,"§7共 "+listed.size()+" 張牌桌；每頁 45 張","§7/mj list 頁碼");
        if(page+1<pages)button(m,51,Material.ARROW,"§e下一頁",q->listMenu(q,page+1));
        button(m,49,Material.DIAMOND,"§b帳戶餘額："+ledger.balance(p.getUniqueId()),q->walletMenu(q,1),"§e點擊開啟自訂存領選單","§7/mj wallet");p.openInventory(m.inventory);
    }
    private void show(Player p,Table t){
        if(!p.hasPermission("taiwanmahjong.play"))throw new IllegalArgumentException("沒有使用牌桌權限。");
        ensureNear(p,t);Menu m=menu("麻將｜"+t.id,t);int own=-1;for(int i=0;i<4;i++)if(p.getUniqueId().equals(t.seats[i]))own=i;final int seat=own;
        Session s=t.session;MahjongGame g=s==null?null:s.game;
        if(s!=null&&s.japaneseMatch!=null){
            List<String> lines=new ArrayList<>();for(int i=0;i<4;i++)lines.add("§f"+s.names[i]+"："+s.japaneseMatch.points(i)+" 點");
            lines.add("§e供託："+s.japaneseMatch.riichiPot()+" 點；本場："+s.japaneseMatch.honba());
            lines.add("§7整場淨輸贏換算，非每飜價格");
            button(m,40,Material.PAPER,"§e日麻點數｜"+(s.japaneseMatch.rounds()==4?"東風局":"半莊"),null,lines.toArray(String[]::new));
        }
        if(seat>=0 && g!=null){
            Map<Integer,String> hints=new HashMap<>();
            boolean canDiscard=g.phase()==MahjongGame.Phase.DISCARD && g.currentTurn()==seat;
            int index=0;for(int tile:g.hands(seat)){final int value=tile;
                String hint=canDiscard?hints.computeIfAbsent(tile,v->{List<Integer> after=new ArrayList<>(g.hands(seat));after.remove(Integer.valueOf(v));List<Integer> waits=HandAnalyzer.waits(after,g.melds(seat),g.rules().ruleSet());return waits.isEmpty()?"§7出此牌後尚未聽牌":"§a出此牌後聽："+String.join("、",labels(waits));}):
                    g.phase()==MahjongGame.Phase.DRAW&&g.currentTurn()==seat?"§e請先按下方的「摸牌」":"§7請等待自己的出牌回合";
                boolean legalDiscard=g.canDiscard(seat,tile);
                tileButton(m,index++,tile,"§f"+Tiles.name(tile),legalDiscard?q->guard(q,()->mutate(t,game->game.discard(seat,value))):null,
                    legalDiscard?"§a點擊／右鍵出牌":g.declaredListening(seat)?"§e已宣告聽牌：只能打出剛摸到的牌":"§7你的手牌（僅自己可見）",hint);}
            int slot=18;for(Meld meld:g.melds(seat))tileButton(m,slot++,meld.tiles().getFirst(),"§e"+String.join(" ",labels(meld.tiles())),null, meld.open()?"§7已公開副露":"§7暗槓（僅自己看得到）");
            button(m,23,g.isJapanese()?Material.GLOWSTONE_DUST:Material.SUNFLOWER,g.isJapanese()?"§e寶牌指示牌："+String.join(" ",labels(g.doraIndicators())):"§e花牌："+String.join(" ",labels(g.flowers(seat))),null,"§7"+(g.isJapanese()?"顯示的是指示牌，下一張才是寶牌；寶牌不能單獨構成役":"配合門風可計正花"));
            if(!g.flowers(seat).isEmpty())m.inventory.setItem(23,TileArt.decorate(m.inventory.getItem(23),g.flowers(seat).getFirst()));
            if(g.lastTile()>=0)tileButton(m,24,g.lastTile(),"§e最新棄牌："+Tiles.name(g.lastTile()),null);
            List<Integer> waiting=g.declaredListening(seat)?g.listeningWaits(seat):HandAnalyzer.waits(g.hands(seat),g.melds(seat),g.rules().ruleSet());
            button(m,26,Material.SPYGLASS,g.declaredListening(seat)?"§6已"+(g.isJapanese()?"立直":"宣告聽牌")+"："+String.join("、",labels(waiting)):waiting.isEmpty()?"§7尚未聽牌":"§a聽牌："+String.join("、",labels(waiting)),null,"§7聽牌種類僅自己可見",g.isJapanese()?"§7至少一役才能胡；振聽只能自摸":"§7宣告後鎖定牌形：可胡／保持聽口加槓，不可暗槓",g.isJapanese()&&g.isFuriten(seat)?"§c目前振聽，不能榮和":"");
            var actions=g.validActions(seat);
            if(actions.contains(MahjongGame.Action.LISTEN))button(m,25,Material.BELL,g.isJapanese()?"§6立直（選出牌）":"§6宣告聽牌（選出牌）",q->guard(q,()->listeningMenu(q,t)),"§e出牌後鎖定牌形，只能摸切、胡牌或合法槓牌",g.isJapanese()?"§7須門清；只許不改聽口及面子構成的暗槓；/mj riichi":"§7不可吃、碰、明槓、暗槓；/mj listen");
            if(actions.contains(MahjongGame.Action.DRAW))button(m,27,Material.PAPER,"§a輪到你了！點擊摸牌",q->guard(q,()->mutate(t,game->game.draw(seat))),"§e先摸一張，再選手牌出牌","§7只有你能按，其他人的手牌不公開");
            if(actions.contains(MahjongGame.Action.TSUMO))button(m,28,Material.EMERALD,"§a自摸",q->guard(q,()->mutate(t,game->game.tsumo(seat))));
            if(actions.contains(MahjongGame.Action.RON))button(m,29,Material.EMERALD_BLOCK,"§a胡牌／榮和",q->guard(q,()->mutate(t,game->game.claim(seat,MahjongGame.Action.RON,-1))));
            if(actions.contains(MahjongGame.Action.PON))button(m,30,Material.IRON_BLOCK,"§e碰",q->guard(q,()->mutate(t,game->game.claim(seat,MahjongGame.Action.PON,-1))));
            if(g.phase()==MahjongGame.Phase.CLAIM && actions.contains(MahjongGame.Action.KAN))button(m,31,Material.GOLD_BLOCK,"§6明槓",q->guard(q,()->mutate(t,game->game.claim(seat,MahjongGame.Action.KAN,-1))));
            if(actions.contains(MahjongGame.Action.CHI))button(m,32,Material.LIME_CONCRETE,"§a吃（選擇組合）",q->choice(q,t,"吃",g.chiOptions(seat),v->mutate(t,game->game.claim(seat,MahjongGame.Action.CHI,v))));
            if(actions.contains(MahjongGame.Action.PASS))button(m,33,Material.GRAY_DYE,"§7過／不吃碰胡",q->guard(q,()->mutate(t,game->game.claim(seat,MahjongGame.Action.PASS,-1))));
            if(g.currentTurn()==seat && g.phase()==MahjongGame.Phase.DISCARD){
                if(!g.concealedKanOptions(seat).isEmpty())button(m,34,Material.QUARTZ_BLOCK,"§e暗槓",q->choice(q,t,"暗槓",g.concealedKanOptions(seat),v->mutate(t,game->game.concealedKan(seat,v))));
                if(!g.addedKanOptions(seat).isEmpty())button(m,35,Material.GOLD_INGOT,"§6加槓",q->choice(q,t,"加槓",g.addedKanOptions(seat),v->mutate(t,game->game.addedKan(seat,v))));
            }
        }else if(seat>=0){
            boolean host=p.getUniqueId().equals(t.lobby.host());
            button(m,20,Material.COMPASS,"§e規則："+(t.lobby.selected()?t.lobby.mode().displayName():"等待房主選擇"),host?q->modeMenu(q,t):null,"§7房主："+hostName(t),"§7先選規則並全服公告，再設定金額");
            button(m,24,Material.DIAMOND,"§b本桌金額"+(t.lobby.confirmed()?"（已確認）":"（待確認）"),host?q->stakesMenu(q,t):null,"§f"+terms(t),"§7修改設定會取消所有玩家的準備",t.lobby.mode()==RuleSet.JAPANESE?"§7房主：/mj stakes 每1000點鑽石數":"§7房主：/mj stakes 底 每台價格");
            button(m,27,t.ready[seat]?Material.LIME_DYE:Material.YELLOW_DYE,t.ready[seat]?"§a已準備（點擊取消）":"§e準備鑽石對局",q->guard(q,()->ready(q,t)),
                "§7僅限四位真人都準備才開局","§7"+terms(t),
                "§e本局每人押金："+required(t)+" 顆","§7先扣押金、不足再扣麻將帳戶餘額","§7未輸掉的全退回帳戶，不抽水",
                "§c押金＋帳戶仍不足才欠債並挑戰 2 隻伏守者","§e勝利清債＋64鑽石；死亡清債、不噴裝","§c開局後離線仍會自動打牌、正常結算");
            int humans=(int)Arrays.stream(t.seats).filter(Objects::nonNull).count();
            button(m,28,Material.WOODEN_SWORD,humans<4?"§a房主：補電腦免費玩":"§a房主：四人免費玩",q->guard(q,()->practice(q,t)),
                "§7現在 "+humans+" 位真人，補 "+(4-humans)+" 位電腦","§a不收押金、不扣鑽石；/mj bots",
                "§7所有真人須在線並靠近牌桌","§7有人離開由電腦接手，其他人繼續");
        }
        for(int i=0;i<4;i++){final int pos=i;String name=t.names[i]==null?"空位":t.names[i];String wind=Tiles.name(27+Math.floorMod(i-t.dealer,4));
            String relative=seat<0?"":switch(Math.floorMod(i-seat,4)){case 0->"（自己）";case 1->"（下家）";case 2->"（對家）";default->"（上家）";};
            button(m,36+i,Material.OAK_STAIRS,wind+"位"+relative+"："+name+(g!=null&&g.declaredListening(i)?"【聽】":""),q->guard(q,()->join(q,t,pos)),"§7"+(g==null?(t.ready[i]?"已準備":"等待準備"):"棄牌："+String.join(" ",labels(g.discards(i)))));}
        button(m,41,Material.BOOK,"§e玩法／胡牌台型",this::rulesBook);
        button(m,46,Material.SPYGLASS,"§b天空競技場觀眾席",q->guard(q,()->arena.command(q,new String[]{"watch"})),"§7觀看欠債者挑戰兩隻伏守者","§7離開：/mj arena leave");
        button(m,42,Material.DIAMOND,"§b存入鑽石（自訂數量）",q->walletMenu(q,1),"§e點擊選數量／全部存入","§7帳戶選單可 Shift＋點背包鑽石整組存入");
        button(m,43,Material.HOPPER,"§b領回鑽石（自訂數量）",q->walletMenu(q,1),"§e點擊選數量／領回最多");
        button(m,44,Material.CHEST,"§b可領餘額："+ledger.balance(p.getUniqueId()),q->walletMenu(q,1));
        if(seat>=0)button(m,45,Material.OAK_DOOR,"§c離開牌桌",q->guard(q,()->leave(q,t)));
        button(m,49,Material.CLOCK,g==null?"房主選規則 → 金額 → 各自準備":"§e"+turnText(s),null,g==null?"§7房主："+hostName(t):"§7剩餘可摸牌："+g.remainingWall(),
            g==null||seat<0?"§7右鍵立體牌也能直接出牌":"§a"+actionText(g,seat),g==null?"":"§7剩餘 "+Math.max(0,(s.deadline-System.currentTimeMillis()+999)/1000)+" 秒");
        button(m,53,Material.BARRIER,"§7關閉選單",Player::closeInventory);p.openInventory(m.inventory);
    }
    @FunctionalInterface private interface TileChoice{void choose(int tile)throws Exception;}
    private void listeningMenu(Player p,Table t){
        int own=seat(t,p);MahjongGame game=t.session==null?null:t.session.game;
        if(game==null||!game.validActions(own).contains(MahjongGame.Action.LISTEN))throw new IllegalArgumentException("現在無法宣告聽牌；須在自己出牌回合，且出牌後確實聽牌。");
        choice(p,t,game.isJapanese()?"出牌並立直":"出牌並宣告聽牌",game.listeningDiscards(own),tile->mutate(t,g->g.declareListening(own,tile)));
    }
    private void choice(Player p,Table t,String label,List<Integer> tiles,TileChoice action){Menu m=menu("選擇"+label,t);int i=0;for(int tile:tiles)tileButton(m,i++,tile,label+" "+Tiles.name(tile),q->guard(q,()->action.choose(tile)));button(m,53,Material.ARROW,"返回",q->show(q,t));p.openInventory(m.inventory);}
    private void refreshMenus(Table t){for(Player p:Bukkit.getOnlinePlayers())if(p.getOpenInventory().getTopInventory().getHolder() instanceof Menu m && t.id.equals(m.table)){
        if(p.getWorld()==t.location.getWorld()&&p.getLocation().distanceSquared(t.location)<=64)show(p,t);else p.closeInventory();}}
    @EventHandler(priority=EventPriority.HIGHEST)public void click(InventoryClickEvent e){
        if(!(e.getView().getTopInventory().getHolder() instanceof Menu m))return;e.setCancelled(true);
        if(!(e.getWhoClicked() instanceof Player p)||e.getRawSlot()<0)return;
        if(m.wallet&&e.getRawSlot()>=54&&e.getClickedInventory()==p.getInventory()&&e.getSlot()>=0&&e.getSlot()<36&&e.isShiftClick()){
            ItemStack clicked=e.getCurrentItem();if(!DiamondInventory.plain(clicked))return;
            int storageSlot=e.getSlot();ItemStack expected=clicked.clone();
            Bukkit.getScheduler().runTask(this,()->guard(p,()->{
                if(!p.isOnline()||p.getOpenInventory().getTopInventory()!=m.inventory)return;
                if(!expected.equals(p.getInventory().getItem(storageSlot)))return;
                walletTransfer(p,expected.getAmount(),true,m.walletAmount,storageSlot);
            }));return;
        }
        if(e.getRawSlot()>=54)return;
        if(e.getClick()!=ClickType.LEFT&&e.getClick()!=ClickType.RIGHT)return;
        Consumer<Player> action=m.actions.get(e.getRawSlot());if(action==null)return;
        Bukkit.getScheduler().runTask(this,()->guard(p,()->{
            if(!p.isOnline()||p.getOpenInventory().getTopInventory()!=m.inventory)return;
            if(m.table!=null){Table t=table(m.table);ensureNear(p,t);if(t.revision!=m.revision){show(p,t);return;}}
            action.accept(p);
        }));
    }
    @EventHandler(priority=EventPriority.HIGHEST)public void drag(InventoryDragEvent e){if(e.getView().getTopInventory().getHolder() instanceof Menu)e.setCancelled(true);}
    @EventHandler public void joined(PlayerJoinEvent e){Bukkit.getScheduler().runTaskLater(this,()->guard(e.getPlayer(),()->{diamonds.recover(e.getPlayer());Table t=seated(e.getPlayer());if(t!=null){render(t);tell(e.getPlayer(),"你有進行中的牌局，使用 /mj 回到操作選單。");}}),20L);}
    @EventHandler public void quit(PlayerQuitEvent e){
        walletInput.remove(e.getPlayer().getUniqueId());
        Table t=seated(e.getPlayer());if(t==null)return;int i=seat(t,e.getPlayer());
        if(t.session==null){removeWaitingSeat(t,i);render(t);refreshMenus(t);}
        else if(t.session.practice)departPractice(t,i);
        else broadcast(t,e.getPlayer().getName()+" 暫時離線，逾時會自動打牌，押金仍正常結算。");
    }

    private void rulesBook(Player p){
        Table current=seated(p);MahjongRules bookRules=current!=null?(current.session!=null?current.session.game.rules():tableRules(current)):rules;
        long bookEscrow=current!=null && current.session!=null && !current.session.practice?
            ledger.game(current.session.id).map(DiamondLedger.GameView::amountEach).orElse(required(current)):required(current);
        if(bookRules.isJapanese()){japaneseBook(p,bookRules,bookEscrow);return;}
        ItemStack book=new ItemStack(Material.WRITTEN_BOOK);BookMeta meta=(BookMeta)book.getItemMeta();meta.setTitle("台灣十六張麻將");meta.setAuthor("伺服器牌桌");
        meta.setPages(
            "§2台灣十六張麻將\n\n四人各持十六張，莊家十七張先出牌。\n五組順子／刻子／槓，加一對將眼即可胡牌，零台也能胡。\n\n使用 /mj 開選單，或右鍵立體手牌出牌。自己的牌面只有自己看得到。",
            "§2摸牌與提示\n\n輪到你時，選單點「摸牌」，再點自己的手牌出牌。\n開局莊家已拿十七張，先出牌；補花、槓後補牌自動進行。\n\n畫面下方、牌桌與選單會提示輪到誰。聊天欄會提示棄牌與你的合法吃碰槓胡選項；剛摸到的牌只告訴本人。",
            "§2順序與莊家\n\n發牌、摸打、輪莊皆逆時針。面向桌心：左邊上家、右邊下家、對面對家。\n\n莊家胡牌或流局：連莊，連莊次數加一。其餘結果由下家接莊；四家輪莊完才進下一圈。免費局也照此順序。",
            "§2吃碰槓與將眼\n\n吃：只可拿上家棄牌，配手上兩張同花色連號。\n碰：任何一家棄牌＋手上相同兩張。\n將眼：兩張相同牌。\n明槓：手上三張＋棄牌。\n暗槓：手上四張相同牌。\n加槓：已碰的刻子＋自己第四張。\n胡優先於碰槓，碰槓優先於吃；可一炮多響。",
            "§2宣告聽牌（本桌規則）\n\n使用 /mj listen 或選單「宣告聽牌」，選一張打出後確實聽牌的牌。\n宣告後鎖定牌形，只能打剛摸到的牌；可胡牌、可將剛摸的第四張加槓，但必須保持原聽口。\n不能吃、碰、明槓或暗槓。\n全服只公告姓名，不公布你的聽牌種類。",
            "§2鑽石結算\n\n原應付＝"+bookRules.baseDiamonds()+"＋"+bookRules.diamondsPerTai()+"×台數。\n放槍者付給胡牌者；自摸由其餘三家各付一份。\n莊家相關每筆另計莊連拉：1＋2×連莊次數台。\n\n只限四位真人，全部準備才扣押金。不抽水。",
            "§2押金與帳戶付款\n\n本局每人押金："+bookEscrow+" 顆鑽石。\n\n先扣押金，不足再扣麻將帳戶餘額；不自動扣背包。兩者仍不足時，按各筆應付比例分配現有鑽石，只有差額算欠債。\n\n結算顯示實付；不足時同時顯示原應付。未輸掉的押金全退帳戶，不抽水。",
            "§2帳戶與離線\n\n/mj deposit 數量：存鑽石\n/mj withdraw 數量：領回\n/mj balance：查餘額\n/mj wallet：自訂存領數量、全部存入／領回最多。Shift 點背包鑽石可整組存入。\n\n只收原版鑽石，不收裝飾寶石。鑽石局不能中途退款；逾時與離線會自動出牌，押金仍結算。重啟後恢復已保存牌局。",
            "§2補電腦免費玩\n\n1～3 位真人先入座，用 /mj bots 或選單免費按鈕補齊空位；4 位真人也能選免費玩。\n\n所有真人須在線並靠近牌桌。免費局不收押金、不扣鑽石，電腦不參加鑽石賭局。\n\n有人離開或斷線由電腦接手；最後一位真人離開才結束免費局。",
            "§2天空還債挑戰\n\n鑽石局應付超過押金＋麻將帳戶餘額，才把未付差額記欠債並送進天空競技場；剛好付清不算欠債。\n\n擊敗兩隻伏守者：清除欠債，64 顆獎勵存入麻將帳戶。\n死亡：清除欠債，不掉裝備、背包與經驗。\n\n排隊與斷線不清債，回來繼續。免費局不觸發。",
            "§2觀看與獎勵\n\n/mj arena watch：到觀眾席\n/mj arena leave：離開看台\n/mj arena status：查欠債\n\n觀眾不能干擾戰鬥。\n勝利獎勵用 /mj withdraw 64 領取；背包放不下可稍後領。\n胡牌方分配押金與帳戶實際付出的鑽石；欠債部分不額外補發。",
            "§2常見台型\n\n門清 1、自摸 1\n門清自摸合計 3\n平胡 2、碰碰胡 4\n混一色 4、清一色 8\n字一色 16\n三暗刻 2、四暗刻 5、五暗刻 8（只取最高）\n獨聽 1、全求 2\n零台：仍可胡，收底。",
            "§2字牌與門清\n\n中／發／白刻子或槓各 1。\n門風、圈風刻子或槓各 1，可疊計。\n小三元 4、大三元 8\n小四喜 8、大四喜 16\n大／小三元不再加三元刻；大／小四喜不再加風刻。\n門清：未吃、碰或明槓；暗槓仍算門清。",
            "§2正花\n\n東：春、梅\n南：夏、蘭\n西：秋、菊\n北：冬、竹\n\n配合自己的門風，每張 1 台，正花最多 2 台。\n春夏秋冬或梅蘭竹菊集滿一組，花槓各 2 台。",
            "§2特殊胡法\n\n天胡 16、地胡 16、人胡 8\n七搶一 8、八仙過海 8\n四槓胡 8\n搶槓 1、槓上／補花自摸 1\n海底撈月／河底胡 1\n\n144 張含花，保留最後 16 張。無可摸牌時流局。\n天胡／地胡／人胡不重複計門清自摸。",
            "§2自摸與補牌\n\n自摸：自己摸到的牌完成胡牌。\n補花、暗槓、加槓的補牌自摸：槓上開花加 1 台。\n明槓後補牌不能自摸，即使補牌又遇花也一樣。\n最後可摸牌自摸可疊計海底與槓上開花。\n可搶別人的加槓，不能搶暗槓。",
            "§2開局特殊胡\n\n天胡：莊家配完牌即自摸胡，無吃碰槓。\n地胡：閒家第一次摸牌就自摸胡，尚未出牌且無吃碰槓。\n人胡：閒家胡莊家開局第一張棄牌，無吃碰槓。\n\n七搶一：持有七花，胡持有第八花的一家。\n八仙過海：自己取得八花，三家各付。",
            "§2過水與逾時\n\n放過可胡的棄牌後，下次摸牌前不能榮和，仍可自摸。\n\n摸牌與出牌各限時 45 秒；吃碰胡回應 12 秒。\n摸牌逾時自動摸；出牌逾時能胡則胡，否則摸打。\n\n關掉選單不會離座，/mj 可重新打開。",
            "§2本桌規則\n\n平胡：全順子、無字無花、雙頭聽、非自摸。\n獨聽：只有一種可胡牌，且為中洞、邊張或將眼；單一對倒不算。\n全求人不另加獨聽。\n宣告聽牌是本桌牌形鎖定規則，不額外加台。\n七對子、國士無雙不混入十六張。\n聽牌與胡牌全服趣味公告，音效與粒子只在桌邊。"
        );meta.addPage(roomHelp());book.setItemMeta(meta);p.openBook(book);
    }
    private String roomHelp(){return "§2房主與每桌設定\n\n第一位入座者當房主。先選台麻或日麻並公告，再確認金額。\n/mj settings：設定選單\n/mj mode tw：台麻\n/mj mode jp：日麻\n\n台麻 /mj stakes 5 2：底5、每台2鑽石。\n日麻 /mj stakes 2：每1000點2鑽石。\n/mj length east 或 south：日麻東風或半莊。\n\n改設定後全桌重新準備；開局後鎖定設定。";}
    private void japaneseBook(Player p,MahjongRules r,long escrow){
        ItemStack book=new ItemStack(Material.WRITTEN_BOOK);BookMeta meta=(BookMeta)book.getItemMeta();meta.setTitle("日本立直麻將");meta.setAuthor("伺服器牌桌");
        meta.setPages(roomHelp(),
            "§2日本十三張立直\n\n136張，無花、無赤五。每人13張，莊家14張先出牌。四組面子加一對將眼；另有七對子、國士無雙。\n\n至少一役才能胡，寶牌只加分，不算役。門清自摸、立直、役牌、斷么九都是常見役。可吃碰，但部分役須門清。",
            "§2摸牌與立直\n\n/mj 開選單；點摸牌，再點手牌出牌。\n選「立直」或 /mj riichi，再選出牌。須門清、至少1000點、出牌後聽牌。\n\n立直自願，不自動宣告；有其他役可直接胡。立直後摸切或胡牌，只准不改聽口及面子構成的暗槓。宣告牌被榮和時立直不成立，不收供託。",
            "§2振聽\n\n捨牌振聽：自己的棄牌（含被鳴走的牌）含目前聽牌種類，不能榮和。改變聽口可解除。\n同巡振聽：放過能完成牌形的牌，到自己下次出牌後才解除。\n立直振聽：立直後放過和牌，本局永遠不能榮和。\n\n振聽均可自摸。吃只限上家，碰槓可拿任一家；禁止換食。",
            "§2王牌與寶牌\n\n保留14張王牌，最多4次槓。槓後補牌並補充王牌，維持14張。明槓補牌也能嶺上開花。\n\n選單顯示寶牌指示牌，下一張才是寶牌：9回1、東南西北循環、白發中循環。每次槓增加指示牌，立直胡牌才計裏寶牌。\n可搶加槓，不搶暗槓。",
            "§2點數與流局\n\n每人25000點，符＋飜自動算點；門清榮和、將眼、面子、等待都影響符數。七對子25符，連風將眼4符。\n\n滿貫、跳滿、倍滿、三倍滿、累計役滿與疊加役滿自動結算。親子付款不同。每本場放槍多付300點，自摸每家多付100點。\n立直1000供託；流局供託留到下一局。",
            "§2流局與局數\n\n流局時不聽者合付3000點給聽牌者；全聽／全不聽不付款。\n莊家胡或流局聽牌連莊，流局不聽則換莊。流局本場加一；閒家胡則本場歸零。\n\n東風：東場四局；半莊：東南八局；連莊另計。有人負點提前結束，剛好0點不結束。\n沒有西入、順位馬、返還點或中途流局。",
            "§2常見役\n\n1飜：立直、一發、門清自摸、斷么九、平和、一盃口、役牌、搶槓、嶺上、海底／河底。\n2飜：雙立直、七對子、對對和、三暗刻、三色同刻、三槓子、小三元、混老頭。\n一氣通貫、三色同順、混全帶么九2飜，副露減1。混一色、純全帶么九3飜，副露減1。",
            "§2高階役\n\n二盃口3飜；清一色6飜，副露減1。流局滿貫按滿貫自摸支付。\n役滿：天和、地和、人和、國士無雙、四暗刻、大三元、小四喜、大四喜、字一色、清老頭、綠一色、九蓮寶燈、四槓子。\n國士十三面、純正九蓮、四暗刻單騎為雙倍役滿；役滿可疊加。",
            "§2本服明確選項\n\n多家榮和取離放槍者逆時針最近的一家（頭跳）。無赤牌，不切上滿貫。流局滿貫須未副露、棄牌全為么九字牌且未被鳴走。\n\n局數到達仍可依條件連莊；全場結束的剩餘供託給第一名，同分按最初座位順序。\n棄牌六張一列，鳴牌橫放標示來源；立直宣告牌也橫放。",
            "§2點數換鑽石\n\n每1000點＝"+r.diamondsPerTai()+"顆鑽石。\n整場結束後，最終點數減25000得出淨輸贏，再換算鑽石。\n總鑽石額向上取整，輸贏雙方各依小數餘數分配，同餘數按座位。收付總量相等，不抽水。\n\n此為整場結算，不再按底＋每飜收費。",
            "§2押金與帳戶\n\n四真人都準備才開鑽石場；每人押金"+escrow+"顆，整場只收一次。\n/mj deposit 數量：存入\n/mj withdraw 數量：領回\n\n先用押金，再用帳戶餘額，不扣背包。仍付不起的差額才算欠債，送天空競技場。免費電腦場不扣鑽石。\n中途離線自動代打；重啟恢復場次、點數、供託與押金。"
        );book.setItemMeta(meta);p.openBook(book);
    }
    static byte[] encode(Session s)throws IOException{ByteArrayOutputStream b=new ByteArrayOutputStream();try(ObjectOutputStream out=new ObjectOutputStream(b)){out.writeObject(s);}return b.toByteArray();}
    static Session decode(byte[] bytes)throws IOException{
        try(ObjectInputStream in=new ObjectInputStream(new ByteArrayInputStream(bytes))){in.setObjectInputFilter(info->info.depth()>100||info.references()>100000||info.streamBytes()>8000000?ObjectInputFilter.Status.REJECTED:ObjectInputFilter.Status.UNDECIDED);return (Session)in.readObject();}
        catch(ClassNotFoundException|ClassCastException ex){throw new IOException("無法恢復麻將牌局",ex);}
    }
}
