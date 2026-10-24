package tw.minecraft.mahjong.arena;

import static tw.minecraft.mahjong.arena.ArenaDebtStore.TARGET_DEFEATS;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.logging.Level;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import tw.minecraft.mahjong.bank.DiamondLedger;
import tw.minecraft.mahjong.arena.ArenaDebtStore.ReturnPoint;
import tw.minecraft.mahjong.arena.ArenaDebtStore.Status;
import tw.minecraft.mahjong.arena.ArenaDebtStore.Ticket;

/** A single isolated punishment fight. It never spends a player's physical inventory. */
public final class ArenaManager implements Listener, AutoCloseable {
    private static final String TAG="twmahjong_arena";
    private final JavaPlugin plugin;
    private final DiamondLedger ledger;
    private final Consumer<UUID> releaseSeat;
    private final NamespacedKey mobKey, deathKey;
    private final Map<UUID,ReturnPoint> watchers=new LinkedHashMap<>();
    private final Set<UUID> ownTeleports=new HashSet<>(), changingMode=new HashSet<>(), dying=new HashSet<>();
    private final Map<UUID,Warden> wardens=new LinkedHashMap<>();
    private ArenaDebtStore store;
    private SkyArena sky;
    private BukkitTask task;
    private String activeTicket;
    private boolean enabled, faulted, closing;

    public ArenaManager(JavaPlugin plugin,DiamondLedger ledger,Consumer<UUID> releaseSeat){
        this.plugin=Objects.requireNonNull(plugin);this.ledger=Objects.requireNonNull(ledger);
        this.releaseSeat=Objects.requireNonNull(releaseSeat);
        mobKey=new NamespacedKey(plugin,"arena_ticket");deathKey=new NamespacedKey(plugin,"arena_death");
    }

    public void enable()throws IOException{
        if(enabled)return;
        store=new ArenaDebtStore(plugin.getDataFolder().toPath().resolve("arena-debts.dat"));
        sky=new SkyArena(plugin);
        sky.ensureBuilt();
        loadWatchers();
        // The same obligation and original return point survive shutdowns and disconnects.
        for(Ticket ticket:store.tickets()){
            if(!terminal(ticket)&&ticket.defeated().size()>=TARGET_DEFEATS)store.resolve(ticket.id(),true);
            else if(ticket.status()==Status.ACTIVE)store.queue(ticket.id());
        }
        cleanupOwnedEntities();
        Bukkit.getPluginManager().registerEvents(this,plugin);
        installGraveGuard();
        installSkillExperienceGuard();
        enabled=true;
        task=Bukkit.getScheduler().runTaskTimer(plugin,this::tick,20L,20L);
        for(Player player:Bukkit.getOnlinePlayers())recoverPlayer(player);
    }

    public void enroll(String gameId,UUID player,long debt)throws IOException{
        if(!enabled)throw new IOException("競技場尚未啟用");
        Ticket ticket=store.enroll(gameId,player,debt);
        if(ticket.returned())return;
        Player online=Bukkit.getPlayer(player);
        if(online!=null)message(online,"本局押金＋麻將帳戶仍不足支付，未付的 "+debt+" 顆鑽石列為競技場欠款。輪到你時將挑戰 "+TARGET_DEFEATS+" 隻伏守者；死亡也清債並保留物品。");
    }

    public boolean hasDebt(UUID player){return store!=null&&store.hasUnresolved(player);}

    /** Accepts either [arena, subcommand] or [subcommand]. */
    public boolean command(Player player,String[] args){
        int offset=args.length>0&&args[0].equalsIgnoreCase("arena")?1:0;
        String action=args.length>offset?args[offset].toLowerCase(Locale.ROOT):"status";
        try{
            switch(action){
                case "watch","spectate" -> watch(player);
                case "leave" -> {
                    if(isFighter(player))message(player,"挑戰中不能離場；擊敗 "+TARGET_DEFEATS+" 隻伏守者或死亡即可清債。離線只會暫停。");
                    else if(watchers.containsKey(player.getUniqueId()))leaveWatch(player);
                    else message(player,"你目前沒有觀戰。");
                }
                case "status" -> {
                    Optional<Ticket> ticket=store.tickets().stream().filter(t->t.player().equals(player.getUniqueId())&&!t.returned()).findFirst();
                    if(ticket.isPresent()){
                        Ticket t=ticket.get();message(player,"欠款 "+t.debt()+" 顆｜已擊敗 "+t.defeated().size()+"/"+TARGET_DEFEATS+"｜"+statusName(t.status()));
                    }else message(player,"目前沒有競技場欠款。");
                    message(player,"/mj arena watch 觀戰；/mj arena leave 返回。勝利獎勵 64 顆存入麻將錢包。");
                    if(faulted)message(player,"競技場存檔發生錯誤，已暫停並保留紀錄，請通知管理員。");
                }
                default -> message(player,"用法：/mj arena [watch|leave|status]");
            }
        }catch(IllegalArgumentException|IllegalStateException ex){message(player,ex.getMessage()==null?"目前不能執行這個競技場操作。":ex.getMessage());}
        catch(IOException|RuntimeException ex){failure(ex);message(player,"競技場暫停，紀錄已保留，請通知管理員。");}
        return true;
    }

    private void tick(){
        if(!enabled||closing||faulted)return;
        try{
            for(Ticket ticket:store.tickets())if(terminal(ticket)&&!ticket.returned())completeReturn(ticket);
            if(sky.isReady())for(Player player:sky.world().getPlayers()){
                if(protectedPlace(player.getLocation())&&!restricted(player)&&!player.isDead())teleport(player,sky.returnSafeFallback());
                else if(isWatcher(player)&&!sky.isSpectator(player.getLocation())&&!player.isDead())teleport(player,sky.spectatorSpawn());
            }
            if(activeTicket!=null){
                Ticket ticket=store.find(activeTicket).orElseThrow();
                Player fighter=Bukkit.getPlayer(ticket.player());
                if(ticket.status()!=Status.ACTIVE){clearFight();return;}
                if(fighter==null||!fighter.isOnline()){pauseFight();return;}
                if(fighter.isDead())return; // PlayerDeathEvent resolves it; never penalize a disconnected player.
                if(ticket.defeated().size()>=TARGET_DEFEATS){resolveFight(true);return;}
                enforceFighter(fighter);
                wardens.values().removeIf(w->w.isDead()||!w.isValid());
                int missing=TARGET_DEFEATS-ticket.defeated().size()-wardens.size();
                if(missing>0)spawnWardens(ticket,fighter,missing);
                for(Warden warden:wardens.values()){
                    if(!sky.containsFight(warden.getLocation()))teleport(warden,sky.wardenSpawns().get(Math.floorMod(warden.getEntityId(),sky.wardenSpawns().size())));
                    warden.setAnger(fighter,150);warden.setTarget(fighter);warden.setRemoveWhenFarAway(false);
                }
                fighter.sendActionBar(net.kyori.adventure.text.Component.text("競技場："+ticket.defeated().size()+"/"+TARGET_DEFEATS+" 隻伏守者｜欠款 "+ticket.debt()+" 顆"));
            }else{
                for(Ticket ticket:store.tickets())if(ticket.status()==Status.QUEUED){
                    Player player=Bukkit.getPlayer(ticket.player());
                    if(player!=null&&player.isOnline()&&!player.isDead()){
                        start(ticket,player);break;
                    }
                }
            }
        }catch(IOException|RuntimeException ex){failure(ex);}
    }

    private void start(Ticket ticket,Player player)throws IOException{
        if(ticket.defeated().size()>=TARGET_DEFEATS){
            store.resolve(ticket.id(),true);
            completeReturn(store.find(ticket.id()).orElseThrow());return;
        }
        if(watchers.containsKey(player.getUniqueId()))leaveWatch(player);
        sky.ensureBuilt();
        releaseSeat.accept(player.getUniqueId());
        player.closeInventory();player.leaveVehicle();
        store.activate(ticket.id(),ticket.returnPoint()==null?snapshot(player):ticket.returnPoint());
        activeTicket=ticket.id();
        try{
            enforceFighter(player);
            if(!teleport(player,sky.spawn()))throw new IOException("競技場傳送被其他插件拒絕");
            player.saveData();
            Ticket current=store.find(ticket.id()).orElseThrow();
            spawnWardens(current,player,TARGET_DEFEATS-current.defeated().size());
            message(player,"開始 20×20 競技場挑戰！擊敗 "+TARGET_DEFEATS+" 隻伏守者：清債並獲得 64 顆鑽石；死亡：清債、保留物品與經驗。");
            Bukkit.broadcast(net.kyori.adventure.text.Component.text("[麻將競技場] "+player.getName()+" 正在挑戰伏守者！/mj arena watch 可免費觀戰。"));
        }catch(IOException|RuntimeException ex){
            store.queue(ticket.id());clearFight();restoreState(player,store.find(ticket.id()).orElseThrow().returnPoint());
            throw ex;
        }
    }

    private void spawnWardens(Ticket ticket,Player fighter,int amount)throws IOException{
        List<Location> spawns=sky.wardenSpawns();
        for(int i=0;i<amount;i++){
            int index=(ticket.defeated().size()+wardens.size())%spawns.size();
            Warden warden=sky.world().spawn(spawns.get(index),Warden.class,CreatureSpawnEvent.SpawnReason.CUSTOM,created->{
                created.getPersistentDataContainer().set(mobKey,PersistentDataType.STRING,ticket.id());
                created.addScoreboardTag(TAG);
                created.setMetadata("NPC",new FixedMetadataValue(plugin,true));
                created.setCustomName("§4競技場伏守者");created.setCustomNameVisible(true);
                created.setPersistent(true);created.setRemoveWhenFarAway(false);created.setCanPickupItems(false);
                // Native loot tables remain untouched; both death phases and arena item/orb spawn guards remove rewards.
                created.setSilent(false);
                created.setAnger(fighter,150);
            });
            if(!warden.isValid()){warden.remove();throw new IOException("競技場伏守者生成遭拒絕");}
            wardens.put(warden.getUniqueId(),warden);warden.setTarget(fighter);
        }
    }

    private void enforceFighter(Player player){
        if(player.getGameMode()!=GameMode.SURVIVAL)setMode(player,GameMode.SURVIVAL);
        if(player.getGameMode()!=GameMode.SURVIVAL)throw new IllegalStateException("其他插件阻擋了競技場生存模式");
        player.setFlying(false);player.setAllowFlight(false);player.setInvulnerable(false);
        if(player.isGliding())player.setGliding(false);
        if(!sky.containsFight(player.getLocation()))teleport(player,sky.spawn());
    }

    private void pauseFight()throws IOException{
        if(activeTicket!=null)store.queue(activeTicket);
        clearFight();
    }

    private void resolveFight(boolean won)throws IOException{
        if(activeTicket==null)return;
        String id=activeTicket;
        store.resolve(id,won);
        clearFight();
        // Return after the current death/damage event has finished, avoiding nested death state changes.
        Bukkit.getScheduler().runTask(plugin,()->{
            try{completeReturn(store.find(id).orElseThrow());}catch(IOException|RuntimeException ex){failure(ex);}
        });
    }

    private void completeReturn(Ticket ticket)throws IOException{
        if(!terminal(ticket)||ticket.returned())return;
        if(ticket.status()==Status.WON)ledger.credit(ticket.player(),64,"arena-win:"+ticket.id());
        Player player=Bukkit.getPlayer(ticket.player());
        if(player==null||!player.isOnline()||player.isDead())return;
        if(!restoreState(player,ticket.returnPoint()))return;
        player.getPersistentDataContainer().remove(deathKey);
        player.saveData();
        store.markReturned(ticket.id());
        dying.remove(ticket.player());
        message(player,ticket.status()==Status.WON?"挑戰成功，欠款已清除！64 顆鑽石已存入麻將錢包，可用 /mj withdraw 64 領出。":"挑戰結束，欠款已清除；你的物品、裝備與經驗已保留。");
    }

    private void clearFight(){
        activeTicket=null;
        for(Warden warden:new ArrayList<>(wardens.values()))warden.remove();
        wardens.clear();
        if(sky!=null&&sky.isReady())for(Entity e:sky.world().getEntities())
            if(sky.containsStructure(e.getLocation())&&(e instanceof Projectile||e instanceof AreaEffectCloud||e instanceof Item||e instanceof ExperienceOrb))e.remove();
    }

    private void watch(Player player)throws IOException{
        if(faulted)throw new IOException("Arena paused");
        if(player.isDead())throw new IllegalArgumentException("請先重生，再進入觀戰台。");
        if(hasDebt(player.getUniqueId())){message(player,"你有待完成的競技場挑戰，不能轉為觀戰。");return;}
        sky.ensureBuilt();releaseSeat.accept(player.getUniqueId());
        if(!watchers.containsKey(player.getUniqueId())){
            watchers.put(player.getUniqueId(),snapshot(player));
            try{saveWatchers();}catch(IOException ex){watchers.remove(player.getUniqueId());throw ex;}
        }
        player.closeInventory();player.leaveVehicle();setMode(player,GameMode.ADVENTURE);
        if(player.getGameMode()!=GameMode.ADVENTURE){leaveWatch(player);throw new IOException("其他插件阻擋了觀戰模式");}
        player.setFlying(false);player.setAllowFlight(false);player.setInvulnerable(true);
        if(!teleport(player,sky.spectatorSpawn())){leaveWatch(player);throw new IOException("觀戰傳送被拒絕");}
        player.saveData();message(player,"已進入安全觀戰台，不能干預戰鬥。/mj arena leave 返回原處。");
    }

    private void leaveWatch(Player player)throws IOException{
        ReturnPoint point=watchers.get(player.getUniqueId());
        if(point==null)return;
        if(!restoreState(player,point))return;
        player.saveData();watchers.remove(player.getUniqueId());
        try{saveWatchers();}catch(IOException ex){watchers.put(player.getUniqueId(),point);throw ex;}
        message(player,"已返回觀戰前的位置。");
    }

    private ReturnPoint snapshot(Player player){
        Location p=player.getLocation();
        // Paper 26.3 isInvulnerable() tests effective generic-damage immunity, including creative mode.
        // Persist the underlying flag so restoring a creative spectator cannot grant permanent god mode.
        return new ReturnPoint(p.getWorld().getUID(),p.getX(),p.getY(),p.getZ(),p.getYaw(),p.getPitch(),player.getGameMode().name(),player.getAllowFlight(),player.isFlying(),player.isMarkedInvulnerable());
    }

    private Location destination(ReturnPoint point){
        if(point!=null){
            World world=Bukkit.getWorld(point.world());
            if(world!=null){
                Location location=new Location(world,point.x(),point.y(),point.z(),point.yaw(),point.pitch());
                if(location.getY()>world.getMinHeight()&&location.getY()<world.getMaxHeight()-1
                    &&!sky.containsStructure(location)&&location.getBlock().isPassable()
                    &&location.clone().add(0,1,0).getBlock().isPassable()&&!location.getBlock().isLiquid()
                    &&(point.allowFlight()||location.clone().add(0,-1,0).getBlock().getType().isSolid()))return location;
            }
        }
        return sky.returnSafeFallback();
    }

    private boolean restoreState(Player player,ReturnPoint point){
        if(point==null)return true;
        boolean moved=teleport(player,destination(point));
        setMode(player,GameMode.valueOf(point.gameMode()));
        player.setAllowFlight(point.allowFlight());player.setFlying(point.allowFlight()&&point.flying());
        player.setInvulnerable(point.invulnerable());player.setFallDistance(0);player.setFireTicks(0);
        player.setVelocity(new Vector());return moved&&player.getGameMode()==GameMode.valueOf(point.gameMode());
    }

    private boolean teleport(Entity entity,Location location){
        ownTeleports.add(entity.getUniqueId());
        try{return entity.teleport(location,PlayerTeleportEvent.TeleportCause.PLUGIN);}
        finally{ownTeleports.remove(entity.getUniqueId());}
    }
    private void setMode(Player player,GameMode mode){
        changingMode.add(player.getUniqueId());
        try{player.setGameMode(mode);}finally{changingMode.remove(player.getUniqueId());}
    }
    private Ticket active(){return activeTicket==null?null:store.find(activeTicket).orElse(null);}
    private boolean isFighter(Entity entity){Ticket t=active();return t!=null&&t.status()==Status.ACTIVE&&t.player().equals(entity.getUniqueId());}
    private boolean awaitingArenaReturn(Player player){
        return sky.containsFight(player.getLocation())&&store.tickets().stream().anyMatch(t->t.player().equals(player.getUniqueId())&&terminal(t)&&!t.returned());
    }
    private boolean isWatcher(Entity entity){return watchers.containsKey(entity.getUniqueId());}
    private boolean isOwned(Entity entity){return entity.getPersistentDataContainer().has(mobKey,PersistentDataType.STRING)||entity.getScoreboardTags().contains(TAG);}
    private boolean restricted(Player player){return isFighter(player)||isWatcher(player);}
    private boolean protectedPlace(Location location){return sky!=null&&sky.containsStructure(location);}
    private static boolean terminal(Ticket ticket){return ticket.status()==Status.WON||ticket.status()==Status.LOST;}
    private static String statusName(Status status){return switch(status){case QUEUED->"排隊／暫停中";case ACTIVE->"挑戰中";case WON->"勝利，等待返回";case LOST->"清債，等待重生";};}
    private static void message(Player player,String text){player.sendMessage("§6[麻將競技場] §f"+text);}

    private void recoverPlayer(Player player){
        try{
            if(watchers.containsKey(player.getUniqueId()))leaveWatch(player);
            String death=player.getPersistentDataContainer().get(deathKey,PersistentDataType.STRING);
            if(death!=null){
                Optional<Ticket> pending=store.find(death);
                if(pending.isPresent()&&!terminal(pending.get()))store.resolve(death,false);
            }
            for(Ticket ticket:store.tickets())if(ticket.player().equals(player.getUniqueId())&&terminal(ticket)&&!ticket.returned())completeReturn(ticket);
        }catch(IOException|RuntimeException ex){failure(ex);}
    }

    private void failure(Exception ex){
        if(!faulted)plugin.getLogger().log(Level.SEVERE,"競技場已安全暫停；債務與獎勵紀錄保留，請處理後重啟。",ex);
        faulted=true;
        Ticket ticket=active();
        clearFight();
        if(ticket!=null){
            try{store.queue(ticket.id());}catch(IOException ignored){}
            Player player=Bukkit.getPlayer(ticket.player());
            if(player!=null&&!player.isDead()){restoreState(player,ticket.returnPoint());player.saveData();message(player,"競技場暫停，你的欠款與進度已保留。");}
        }
    }

    @EventHandler public void join(PlayerJoinEvent event){Bukkit.getScheduler().runTask(plugin,()->recoverPlayer(event.getPlayer()));}
    @EventHandler public void quit(PlayerQuitEvent event){
        Player player=event.getPlayer();
        try{
            if(isFighter(player)){
                Ticket ticket=active();pauseFight();
                if(ticket!=null&&!player.isDead()){restoreState(player,ticket.returnPoint());player.saveData();}
            }
            if(isWatcher(player))leaveWatch(player);
        }catch(IOException|RuntimeException ex){failure(ex);}
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void deathProtect(PlayerDeathEvent event){
        if(!isFighter(event.getEntity())&&!awaitingArenaReturn(event.getEntity()))return;
        dying.add(event.getEntity().getUniqueId());protectDeath(event);
    }
    @EventHandler(priority=EventPriority.MONITOR)
    public void deathFinish(PlayerDeathEvent event){
        Player player=event.getEntity();
        if(!dying.contains(player.getUniqueId()))return;
        if(event.isCancelled()){dying.remove(player.getUniqueId());return;}
        protectDeath(event); // GravesX MONITOR may force keepInventory false for OP; we run after its soft-dependency.
        Ticket ticket=active();
        if(ticket==null||!ticket.player().equals(player.getUniqueId()))return;
        player.getPersistentDataContainer().set(deathKey,PersistentDataType.STRING,ticket.id());
        try{resolveFight(false);}catch(IOException|RuntimeException ex){failure(ex);}
    }
    private void protectDeath(PlayerDeathEvent event){
        event.setKeepInventory(true);event.setKeepLevel(true);event.setDroppedExp(0);event.getDrops().clear();
    }
    @EventHandler(priority=EventPriority.HIGHEST)
    public void respawn(PlayerRespawnEvent event){
        for(Ticket ticket:store.tickets())if(ticket.player().equals(event.getPlayer().getUniqueId())&&terminal(ticket)&&!ticket.returned()){
            event.setRespawnLocation(destination(ticket.returnPoint()));
            Bukkit.getScheduler().runTask(plugin,()->recoverPlayer(event.getPlayer()));break;
        }
    }
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true)
    public void mobDeathEarly(EntityDeathEvent event){if(isOwned(event.getEntity())){event.getDrops().clear();event.setDroppedExp(0);}}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void mobDeath(EntityDeathEvent event){
        if(!isOwned(event.getEntity()))return;
        event.getDrops().clear();event.setDroppedExp(0);
        String ticket=event.getEntity().getPersistentDataContainer().get(mobKey,PersistentDataType.STRING);
        if(activeTicket==null||!activeTicket.equals(ticket)||!wardens.containsKey(event.getEntity().getUniqueId()))return;
        try{
            store.defeat(ticket,event.getEntity().getUniqueId());wardens.remove(event.getEntity().getUniqueId());
            if(store.find(ticket).orElseThrow().defeated().size()>=TARGET_DEFEATS)resolveFight(true);
        }catch(IOException|RuntimeException ex){failure(ex);}
    }

    private Entity source(Entity entity){
        if(entity instanceof Projectile projectile){ProjectileSource shooter=projectile.getShooter();return shooter instanceof Entity e?e:entity;}
        if(entity instanceof AreaEffectCloud cloud){ProjectileSource shooter=cloud.getSource();return shooter instanceof Entity e?e:entity;}
        return entity;
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void damage(EntityDamageEvent event){
        Entity victim=event.getEntity();
        if(isWatcher(victim)){event.setCancelled(true);return;}
        if(event instanceof EntityDamageByEntityEvent by){
            Entity attacker=source(by.getDamager());
            if(isOwned(victim)){
                if(!isFighter(attacker))event.setCancelled(true);
            }else if(isOwned(attacker)){
                if(!isFighter(victim))event.setCancelled(true);
            }else if(isFighter(victim)||isFighter(attacker)||isWatcher(attacker))event.setCancelled(true);
        }else if(isOwned(victim)){
            // Only direct player/projectile hits can kill a tagged challenge mob.
            event.setCancelled(true);
        }
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void target(EntityTargetLivingEntityEvent event){
        if(isOwned(event.getEntity())){
            if(event.getTarget()!=null&&!isFighter(event.getTarget()))event.setCancelled(true);
        }else if(event.getTarget()!=null&&(isFighter(event.getTarget())||isWatcher(event.getTarget())))event.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void healWarden(EntityRegainHealthEvent event){if(isOwned(event.getEntity()))event.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void spectatorDarkness(EntityPotionEffectEvent event){
        if(isWatcher(event.getEntity())&&event.getNewEffect()!=null&&event.getNewEffect().getType().equals(PotionEffectType.DARKNESS))event.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void splash(PotionSplashEvent event){
        Entity attacker=source(event.getPotion());
        for(LivingEntity affected:event.getAffectedEntities()){
            if(isWatcher(affected)||isOwned(affected)&&!isFighter(attacker)
                ||isFighter(affected)&&!isFighter(attacker)||isFighter(attacker)&&!isFighter(affected)&&!isOwned(affected))event.setIntensity(affected,0);
        }
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void cloud(AreaEffectCloudApplyEvent event){
        Entity attacker=source(event.getEntity());
        event.getAffectedEntities().removeIf(e->isWatcher(e)||isOwned(e)&&!isFighter(attacker)
            ||isFighter(e)&&!isFighter(attacker)||isFighter(attacker)&&!isFighter(e)&&!isOwned(e));
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void projectile(ProjectileLaunchEvent event){
        Entity attacker=source(event.getEntity());
        if(isWatcher(attacker)||protectedPlace(event.getEntity().getLocation())&&!isFighter(attacker))event.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void move(PlayerMoveEvent event){
        if(event instanceof PlayerTeleportEvent||event.getTo()==null)return;
        Player player=event.getPlayer();
        if(isFighter(player)&&!sky.containsFight(event.getTo()))event.setTo(sky.spawn());
        else if(isWatcher(player)&&!sky.isSpectator(event.getTo()))event.setTo(sky.spectatorSpawn());
        else if(!restricted(player)&&protectedPlace(event.getTo())&&!protectedPlace(event.getFrom()))event.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void playerTeleport(PlayerTeleportEvent event){
        if(ownTeleports.contains(event.getPlayer().getUniqueId())||event.getTo()==null)return;
        Player p=event.getPlayer();
        if(isFighter(p)&&!sky.containsFight(event.getTo())||isWatcher(p)&&!sky.isSpectator(event.getTo())
            ||!restricted(p)&&protectedPlace(event.getTo()))event.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void entityTeleport(EntityTeleportEvent event){
        if(ownTeleports.contains(event.getEntity().getUniqueId())||event.getTo()==null)return;
        if(isOwned(event.getEntity())&&!sky.containsFight(event.getTo())||!isOwned(event.getEntity())&&protectedPlace(event.getTo()))event.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void commands(PlayerCommandPreprocessEvent event){
        if(!restricted(event.getPlayer()))return;
        String text=event.getMessage().trim().toLowerCase(Locale.ROOT);
        if(!text.matches("/(?:taiwanmahjong:)?(?:mj|mahjong) arena(?: status)?")
            &&!(isWatcher(event.getPlayer())&&text.matches("/(?:taiwanmahjong:)?(?:mj|mahjong) arena leave"))){
            event.setCancelled(true);message(event.getPlayer(),"競技場期間請使用 /mj arena status"+(isWatcher(event.getPlayer())?" 或 /mj arena leave":"；不能用傳送指令離場"));
        }
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void mode(PlayerGameModeChangeEvent event){if(restricted(event.getPlayer())&&!changingMode.contains(event.getPlayer().getUniqueId()))event.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void fly(PlayerToggleFlightEvent event){if(restricted(event.getPlayer()))event.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void glide(EntityToggleGlideEvent event){if(isFighter(event.getEntity())||isWatcher(event.getEntity()))event.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void breakBlock(BlockBreakEvent event){if(protectedPlace(event.getBlock().getLocation())||restricted(event.getPlayer()))event.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void place(BlockPlaceEvent event){if(protectedPlace(event.getBlock().getLocation())||restricted(event.getPlayer()))event.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void interact(PlayerInteractEvent event){
        if(isWatcher(event.getPlayer())||(event.getClickedBlock()!=null&&protectedPlace(event.getClickedBlock().getLocation()))){
            event.setUseInteractedBlock(Event.Result.DENY);
            // Shields, food and bows remain usable by the fighter against arena walls.
            if(isWatcher(event.getPlayer()))event.setUseItemInHand(Event.Result.DENY);
        }
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void interactEntity(PlayerInteractEntityEvent event){if(restricted(event.getPlayer())||protectedPlace(event.getRightClicked().getLocation()))event.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void bucketEmpty(PlayerBucketEmptyEvent event){if(restricted(event.getPlayer())||protectedPlace(event.getBlock().getLocation())||protectedPlace(event.getBlockClicked().getLocation()))event.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void bucketFill(PlayerBucketFillEvent event){if(restricted(event.getPlayer())||protectedPlace(event.getBlock().getLocation()))event.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void drop(PlayerDropItemEvent event){if(restricted(event.getPlayer())||protectedPlace(event.getItemDrop().getLocation()))event.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void pickup(EntityPickupItemEvent event){if(isOwned(event.getEntity())||isFighter(event.getEntity())||isWatcher(event.getEntity())||protectedPlace(event.getItem().getLocation()))event.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void itemSpawn(ItemSpawnEvent event){if(protectedPlace(event.getLocation()))event.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void entitySpawn(EntitySpawnEvent event){if(protectedPlace(event.getLocation())&&event.getEntity() instanceof ExperienceOrb)event.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void creatureSpawn(CreatureSpawnEvent event){if(protectedPlace(event.getLocation())&&!isOwned(event.getEntity()))event.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void inventoryOpen(InventoryOpenEvent event){if(event.getPlayer() instanceof Player p&&restricted(p)&&event.getInventory().getType()!=InventoryType.CRAFTING&&event.getInventory().getType()!=InventoryType.PLAYER)event.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void inventoryClick(InventoryClickEvent event){if(event.getWhoClicked() instanceof Player p&&restricted(p)&&event.getAction().name().startsWith("DROP"))event.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void entityBlock(EntityChangeBlockEvent event){if(isOwned(event.getEntity())||protectedPlace(event.getBlock().getLocation()))event.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void blockExplode(BlockExplodeEvent event){event.blockList().removeIf(b->protectedPlace(b.getLocation()));}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void entityExplode(EntityExplodeEvent event){event.blockList().removeIf(b->protectedPlace(b.getLocation()));}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void burn(BlockBurnEvent event){if(protectedPlace(event.getBlock().getLocation()))event.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void ignite(BlockIgniteEvent event){if(protectedPlace(event.getBlock().getLocation()))event.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void flow(BlockFromToEvent event){if(protectedPlace(event.getBlock().getLocation())||protectedPlace(event.getToBlock().getLocation()))event.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void pistonExtend(BlockPistonExtendEvent event){if(protectedPlace(event.getBlock().getLocation())||event.getBlocks().stream().anyMatch(b->protectedPlace(b.getLocation())||protectedPlace(b.getRelative(event.getDirection()).getLocation())))event.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void pistonRetract(BlockPistonRetractEvent event){if(protectedPlace(event.getBlock().getLocation())||event.getBlocks().stream().anyMatch(b->protectedPlace(b.getLocation())||protectedPlace(b.getRelative(event.getDirection()).getLocation())))event.setCancelled(true);}
    @EventHandler public void loadEntities(EntitiesLoadEvent event){for(Entity entity:event.getEntities())if(isOwned(entity)&&!wardens.containsKey(entity.getUniqueId()))entity.remove();}

    private void cleanupOwnedEntities(){for(World world:Bukkit.getWorlds())for(Entity entity:world.getEntities())if(isOwned(entity))entity.remove();}

    @SuppressWarnings("unchecked")
    private void installGraveGuard()throws IOException{
        Plugin graves=Bukkit.getPluginManager().getPlugin("GravesX");
        if(graves==null)graves=Bukkit.getPluginManager().getPlugin("Graves");
        if(graves==null||!graves.isEnabled())return;
        try{
            Class<? extends Event> type=(Class<? extends Event>)Class.forName("dev.cwhead.GravesX.event.GravePreCreateEvent",true,graves.getClass().getClassLoader()).asSubclass(Event.class);
            var entityMethod=type.getMethod("getEntity");
            Bukkit.getPluginManager().registerEvent(type,this,EventPriority.HIGHEST,(listener,event)->{
                try{
                    Object entity=entityMethod.invoke(event);
                    if(entity instanceof Entity e&&(isOwned(e)||dying.contains(e.getUniqueId())||isFighter(e)))((Cancellable)event).setCancelled(true);
                }catch(ReflectiveOperationException ex){throw new org.bukkit.event.EventException(ex);}
            },plugin,false);
        }catch(ReflectiveOperationException|LinkageError ex){throw new IOException("無法安裝 GravesX 競技場物品保護，已拒絕啟用競技場",ex);}
    }

    @SuppressWarnings("unchecked")
    private void installSkillExperienceGuard()throws IOException{
        Plugin valhalla=Bukkit.getPluginManager().getPlugin("ValhallaMMO");
        if(valhalla==null||!valhalla.isEnabled())return;
        try{
            Class<? extends Event> type=(Class<? extends Event>)Class.forName("me.athlaeos.valhallammo.event.PlayerSkillExperienceGainEvent",true,valhalla.getClass().getClassLoader()).asSubclass(Event.class);
            Bukkit.getPluginManager().registerEvent(type,this,EventPriority.HIGHEST,(listener,event)->{
                Player player=((PlayerEvent)event).getPlayer();
                if(isFighter(player)||protectedPlace(player.getLocation()))((Cancellable)event).setCancelled(true);
            },plugin,false);
        }catch(ReflectiveOperationException|LinkageError ex){throw new IOException("無法安裝 ValhallaMMO 競技場經驗保護",ex);}
    }

    private void loadWatchers()throws IOException{
        Path file=plugin.getDataFolder().toPath().resolve("arena-watchers.yml");
        if(!Files.exists(file))return;
        YamlConfiguration yaml=new YamlConfiguration();
        try{yaml.load(file.toFile());
            ConfigurationSection players=yaml.getConfigurationSection("players");if(players==null)return;
            for(String id:players.getKeys(false)){
                ConfigurationSection c=players.getConfigurationSection(id);
                watchers.put(UUID.fromString(id),new ReturnPoint(UUID.fromString(c.getString("world")),c.getDouble("x"),c.getDouble("y"),c.getDouble("z"),(float)c.getDouble("yaw"),(float)c.getDouble("pitch"),c.getString("mode"),c.getBoolean("allow-flight"),c.getBoolean("flying"),c.getBoolean("invulnerable")));
            }
        }catch(Exception ex){throw new IOException("無法讀取競技場觀戰返回紀錄",ex);}
    }

    private void saveWatchers()throws IOException{
        YamlConfiguration yaml=new YamlConfiguration();
        watchers.forEach((player,p)->{
            String k="players."+player+".";yaml.set(k+"world",p.world().toString());yaml.set(k+"x",p.x());yaml.set(k+"y",p.y());yaml.set(k+"z",p.z());yaml.set(k+"yaw",p.yaw());yaml.set(k+"pitch",p.pitch());yaml.set(k+"mode",p.gameMode());yaml.set(k+"allow-flight",p.allowFlight());yaml.set(k+"flying",p.flying());yaml.set(k+"invulnerable",p.invulnerable());
        });
        Path dest=plugin.getDataFolder().toPath().resolve("arena-watchers.yml"),temp=dest.resolveSibling("arena-watchers.yml.tmp");
        byte[] bytes=yaml.saveToString().getBytes(StandardCharsets.UTF_8);
        try(FileChannel channel=FileChannel.open(temp,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING,StandardOpenOption.WRITE)){
            ByteBuffer buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())channel.write(buffer);channel.force(true);
        }
        for(int attempt=0;;attempt++)try{Files.move(temp,dest,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);break;}
        catch(AccessDeniedException blocked){
            if(attempt==4)throw blocked;
            try{Thread.sleep(10L<<attempt);}catch(InterruptedException ex){Thread.currentThread().interrupt();throw new IOException("觀戰紀錄寫入中斷",blocked);}
        }
    }

    @Override public void close(){
        if(closing)return;closing=true;
        if(task!=null)task.cancel();
        if(store!=null){
            Ticket ticket=active();
            if(ticket!=null){
                try{store.queue(ticket.id());}catch(IOException ex){plugin.getLogger().log(Level.SEVERE,"無法保存競技場暫停",ex);}
                Player player=Bukkit.getPlayer(ticket.player());if(player!=null&&!player.isDead()){restoreState(player,ticket.returnPoint());player.saveData();}
            }
            clearFight();
            for(UUID id:new ArrayList<>(watchers.keySet())){
                Player player=Bukkit.getPlayer(id);
                if(player!=null&&!player.isDead())try{leaveWatch(player);}catch(IOException ex){plugin.getLogger().log(Level.SEVERE,"觀戰返回紀錄待下次登入恢復",ex);}
            }
            try{store.close();}catch(IOException ex){plugin.getLogger().log(Level.SEVERE,"競技場存檔關閉失敗",ex);}
        }
        if(sky!=null)sky.close();
        HandlerList.unregisterAll(this);enabled=false;
    }
}
