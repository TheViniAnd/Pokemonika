package ru.vanillamon.game;

import ru.vanillamon.*;
import ru.vanillamon.config.Settings;
import ru.vanillamon.domain.*;
import ru.vanillamon.domain.Creature;
import ru.vanillamon.domain.Animation.State;
import ru.vanillamon.storage.Repository;
import ru.vanillamon.world.*;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.*;
import org.bukkit.persistence.PersistentDataType;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.*;

public final class Game implements Listener {
    public static final class Session {
        public final UUID token=UUID.randomUUID();
        public Profile profile;
        public boolean busy, dirty;
        public Session(Profile p) {profile=p;}
    }
    private final VanillaMon plugin;
    public final Repository repository;
    public final WorldEngine world;
    public final Map<UUID,Session> sessions=new HashMap<>();
    public final Map<UUID,Battle> battles=new HashMap<>();
    private final Map<UUID,UUID> loads=new HashMap<>();
    private final Set<UUID> delivering=new HashSet<>();
    private final Random random=new Random();
    public final NamespacedKey ballKey;
    public final NamespacedKey ballTypeKey;
    public final Capture capture;
    private boolean stopping;
    public Game(VanillaMon plugin,Repository repository,WorldEngine world) {
        this.plugin=plugin;this.repository=repository;this.world=world;ballKey=new NamespacedKey("vanillamon","pokeball");ballTypeKey=new NamespacedKey("vanillamon","ball_type");
        capture=new Capture(plugin,this);
        world.onRemove=this::removed;
        world.onSeen=(p,a)->{Session s=sessions.get(p.getUniqueId());if(s!=null&&a.wild()&&!s.profile.dex.containsKey(a.creature.species)){s.profile.dex.put(a.creature.species,1);s.dirty=true;}};
    }
    public Settings settings() {return plugin.settings();}
    public void tell(Player p,String key,Object... values) {if(p!=null&&p.isOnline())p.sendMessage(Component.text(settings().text("prefix")+settings().text(key,values)));}
    public void main(Runnable action) {if(!stopping&&plugin.isEnabled())Bukkit.getScheduler().runTask(plugin,action);}
    public void failure(Throwable error) {plugin.getLogger().log(java.util.logging.Level.SEVERE,"SQLite operation failed",error);}
    public Session session(Player p) {Session s=sessions.get(p.getUniqueId());if(s==null)tell(p,"loading");return s;}
    public boolean available(Player p,boolean outsideBattle) {
        if(!plugin.allowed(p,"play")){tell(p,"denied");return false;}Session s=session(p);if(s==null)return false;
        if(activityLocked(p.getUniqueId())||capture.hasThrow(p.getUniqueId())){tell(p,"busy");return false;}
        if(outsideBattle&&battles.containsKey(p.getUniqueId())){tell(p,"heal.battle");return false;}return true;
    }
    public boolean activityLocked(UUID id){Session session=sessions.get(id);return session==null||session.busy;}
    public boolean current(Player p,Session s) {return p.isOnline()&&sessions.get(p.getUniqueId())==s;}
    public void load(Player p) {
        if(p.hasMetadata("NPC"))return;
        UUID token=UUID.randomUUID();loads.put(p.getUniqueId(),token);
        repository.load(p.getUniqueId()).whenComplete((profile,e)->main(()->{
            if(!token.equals(loads.get(p.getUniqueId()))||!p.isOnline())return;
            loads.remove(p.getUniqueId());if(e!=null){failure(e);tell(p,"error");return;}
            // Reject incompatible hand-edited storage instead of silently dropping owned creatures.
            if(profile.creatures.stream().anyMatch(c->!settings().species().containsKey(c.species)||!settings().moves().keySet().containsAll(c.pp.keySet()))){tell(p,"error");return;}
            sessions.put(p.getUniqueId(),new Session(profile));plugin.discoverRecipes(p);tell(p,"ready");deliverBalls(p);
        }));
    }
    private boolean mergeSeen(Profile before,Profile saved) {boolean changed=before.dex.entrySet().stream().anyMatch(e->e.getValue()>saved.dex.getOrDefault(e.getKey(),0));before.dex.forEach((id,status)->saved.dex.merge(id,status,Math::max));return changed;}
    public void persist(Player p,Session s,Consumer<Boolean> done) {
        if(s.busy){if(done!=null)done.accept(false);return;}s.busy=true;s.dirty=false;
        repository.save(s.profile).whenComplete((saved,error)->main(()->{s.busy=false;if(error!=null){failure(error);s.dirty=true;tell(p,"error");if(done!=null)done.accept(false);return;}s.dirty=mergeSeen(s.profile,saved);s.profile=saved;if(done!=null)done.accept(true);}));
    }
    public void receive(Player p,Session s,Profile saved){if(sessions.get(p.getUniqueId())!=s)return;s.dirty=mergeSeen(s.profile,saved);s.profile=saved;s.busy=false;}
    public void change(Player p,Consumer<Profile> mutation,Runnable done) {
        if(!available(p,true))return;Session s=sessions.get(p.getUniqueId());Profile copy=s.profile.copy();
        try {mutation.accept(copy);copy.normalizeActive();copy.validate();}catch(IllegalArgumentException e){tell(p,"team.invalid");return;}
        s.busy=true;
        repository.save(copy).whenComplete((saved,e)->main(()->{
            s.busy=false;if(e!=null){failure(e);tell(p,"error");return;}s.dirty=mergeSeen(s.profile,saved);s.profile=saved;
            if(current(p,s)){tell(p,"team.changed");if(done!=null)done.run();}
        }));
    }
    public void grant(Player p,String species,int level,boolean starter,Consumer<Boolean> done) {
        if(!available(p,true))return;Session s=sessions.get(p.getUniqueId());
        if(starter&&s.profile.starter){tell(p,"starter.already");return;}if(s.profile.freeSlot()<0){tell(p,"full");return;}
        s.busy=true;UUID op=starter?UUID.nameUUIDFromBytes((p.getUniqueId()+":starter").getBytes(StandardCharsets.UTF_8)):UUID.randomUUID();
        Creature c=Creature.create(settings().species().get(species),level,0,settings().moves());
        repository.grant(op,s.profile,c,starter,starter?settings().number("game.starter-balls"):0).whenComplete((saved,e)->main(()->{
            s.busy=false;if(e!=null){failure(e);tell(p,"error");if(done!=null)done.accept(false);return;}
            s.dirty=mergeSeen(s.profile,saved);s.profile=saved;
            if(current(p,s)){if(starter){tell(p,"starter.done");deliverBalls(p);}else tell(p,"admin.done");if(done!=null)done.accept(true);}
        }));
    }
    public ItemStack ball(int amount) {
        return ball("poke",amount);
    }
    public ItemStack ball(String type,int amount){
        if(!settings().balls().containsKey(type)||amount<1||amount>16)throw new IllegalArgumentException("Invalid ball item");
        ItemStack item=new ItemStack(Material.SNOWBALL,amount);var meta=item.getItemMeta();meta.displayName(Component.text(settings().text("balls."+type+".name")));
        meta.lore(List.of(Component.text(settings().text("balls."+type+".description"))));meta.getPersistentDataContainer().set(ballKey,PersistentDataType.BYTE,(byte)1);meta.getPersistentDataContainer().set(ballTypeKey,PersistentDataType.STRING,type);item.setItemMeta(meta);return item;
    }
    public String ballType(ItemStack item){
        if(item==null||item.getType()!=Material.SNOWBALL||!item.hasItemMeta())return null;var data=item.getItemMeta().getPersistentDataContainer();
        if(!data.has(ballKey,PersistentDataType.BYTE)||data.has(ballTypeKey)&&!data.has(ballTypeKey,PersistentDataType.STRING))return null;
        return BallType.identify(Byte.valueOf((byte)1).equals(data.get(ballKey,PersistentDataType.BYTE)),data.get(ballTypeKey,PersistentDataType.STRING),settings().balls().keySet());
    }
    public boolean isBall(ItemStack item) {return ballType(item)!=null;}
    public void giveBalls(Player p,int amount,Consumer<Boolean> done) {
        giveBalls(p,"poke",amount,done);
    }
    public void giveBalls(Player p,String type,int amount,Consumer<Boolean> done){
        if(!settings().balls().containsKey(type))throw new IllegalArgumentException("Unknown ball");
        repository.queueBalls(UUID.randomUUID(),p.getUniqueId(),amount,type).whenComplete((v,e)->main(()->{
            if(e!=null){failure(e);tell(p,"error");if(done!=null)done.accept(false);return;}
            tell(p,"delivery.queued");deliverBalls(p);if(done!=null)done.accept(true);
        }));
    }
    /** Inventory and a receipt are saved in the same player data before acknowledging the SQLite outbox. */
    public void deliverBalls(Player p) {
        UUID owner=p.getUniqueId();if(!p.isOnline()||!delivering.add(owner))return;
        NamespacedKey receipt=new NamespacedKey("vanillamon","ball_delivery_receipt");
        repository.nextDelivery(owner).whenComplete((delivery,e)->main(()->{
            if(e!=null){delivering.remove(owner);failure(e);return;}
            if(delivery==null||!p.isOnline()){delivering.remove(owner);return;}
            try {
                String previous=p.getPersistentDataContainer().get(receipt,PersistentDataType.STRING);
                if(!delivery.id().toString().equals(previous)) {
                    int space=0;ItemStack sample=ball(delivery.ballType(),1);
                    for(ItemStack i:p.getInventory().getStorageContents())space+=i==null||i.getType().isAir()?16:i.isSimilar(sample)?Math.max(0,16-i.getAmount()):0;
                    if(space<delivery.amount()){delivering.remove(owner);return;}
                    int remaining=delivery.amount();while(remaining>0){int n=Math.min(16,remaining);if(!p.getInventory().addItem(ball(delivery.ballType(),n)).isEmpty())throw new IllegalStateException("Delivery inventory capacity changed");remaining-=n;}
                    p.getPersistentDataContainer().set(receipt,PersistentDataType.STRING,delivery.id().toString());
                }
                p.saveData();
            }catch(RuntimeException error){delivering.remove(owner);failure(error);return;}
            repository.acknowledgeDelivery(delivery.id()).whenComplete((v,error)->main(()->{
                delivering.remove(owner);if(error!=null){failure(error);return;}
                tell(p,"delivery.done","amount",delivery.amount(),"ball",settings().text("balls."+delivery.ballType()+".name"));deliverBalls(p);
            }));
        }));
    }


    public boolean takeBall(Player p,EquipmentSlot hand) {ItemStack item=p.getInventory().getItem(hand);if(!isBall(item)||item.getAmount()<1)return false;item.setAmount(item.getAmount()-1);p.getInventory().setItem(hand,item);return true;}
    public boolean takeBall(Player p,EquipmentSlot hand,String type){return type.equals(ballType(p.getInventory().getItem(hand)))&&takeBall(p,hand);}
    public EquipmentSlot findBall(Player p) {if(isBall(p.getInventory().getItemInMainHand()))return EquipmentSlot.HAND;if(isBall(p.getInventory().getItemInOffHand()))return EquipmentSlot.OFF_HAND;return null;}
    public boolean takeAnyBall(Player p) {
        for(int slot=0;slot<p.getInventory().getSize();slot++){ItemStack item=p.getInventory().getItem(slot);if(isBall(item)){item.setAmount(item.getAmount()-1);p.getInventory().setItem(slot,item);return true;}}return false;
    }
    public boolean takeAnyBall(Player p,String type){
        for(int slot=0;slot<p.getInventory().getSize();slot++){ItemStack item=p.getInventory().getItem(slot);if(type.equals(ballType(item))&&item.getAmount()>0){item.setAmount(item.getAmount()-1);p.getInventory().setItem(slot,item);return true;}}return false;
    }
    public int countBalls(Player p,String type){int total=0;for(ItemStack item:p.getInventory().getContents())if(type.equals(ballType(item)))total+=item.getAmount();return total;}
    public boolean hasBall(Player p) {for(ItemStack i:p.getInventory().getContents())if(isBall(i))return true;return false;}
    public void heal(Player p) {
        if(!available(p,true))return;Session s=sessions.get(p.getUniqueId());long now=System.currentTimeMillis();long wait=settings().number("game.heal-delay-seconds")*1000L-(now-s.profile.healedAt);
        if(wait>0){tell(p,"heal.wait","seconds",(wait+999)/1000);return;}
        change(p,profile->{profile.healedAt=now;profile.creatures.forEach(c->c.heal(settings().moves()));},()->tell(p,"heal.done"));
    }
    public void startBattle(Player p,Actor wild) {
        if(!available(p,true)||wild==null||!wild.wild()||wild.capturing||wild.creature.hp<=0)return;
        if(!p.getWorld().equals(wild.location.getWorld())||p.getLocation().distanceSquared(wild.location)>36)return;
        Session session=sessions.get(p.getUniqueId());Creature fighter=session.profile.fighter();if(fighter==null)fighter=session.profile.fighter();if(fighter==null){tell(p,"team.empty");return;}
        double spacing=Math.max(2.4,(wild.model.width()+settings().models().get(fighter.species).width())/2+.8);
        Location loc=world.near(wild.location,spacing,settings().species().get(fighter.species));if(loc==null){tell(p,"world.unsafe");return;}
        if(!wild.lock(p.getUniqueId())){tell(p,"busy");return;}
        Actor ally=world.spawn(fighter,p.getUniqueId(),loc);if(ally==null){wild.lockedBy=null;return;}
        session.profile.active=fighter.id;
        ally.lockedBy=p.getUniqueId();face(ally,wild);face(wild,ally);wild.animation.set(State.IDLE,world.tick);
        Battle battle=new Battle(p.getUniqueId(),wild,ally);battles.put(p.getUniqueId(),battle);session.profile.dex.merge(wild.creature.species,1,Math::max);session.dirty=true;
        tell(p,"battle.start","name",settings().speciesName(wild.creature.species));plugin.menus().battle(p,battle);
    }
    private void face(Actor a,Actor b) {a.yaw=(float)Math.atan2(b.location.getX()-a.location.getX(),b.location.getZ()-a.location.getZ());}
    public boolean valid(Battle b) {return b!=null&&!b.ended&&battles.get(b.player)==b&&!b.wild.removed&&!b.ally.removed;}
    private boolean canAct(Player p,Battle b) {return valid(b)&&!b.resolving&&sessions.containsKey(b.player)&&!sessions.get(b.player).busy&&!capture.hasThrow(b.player);}
    public void attack(Player p,String moveId) {
        Battle b=battles.get(p.getUniqueId());if(!canAct(p,b))return;Creature ally=b.ally.creature;Move move=settings().moves().get(moveId);
        if(move==null||ally.pp.getOrDefault(moveId,0)<=0){tell(p,"battle.no-pp");return;}
        b.resolving=true;p.closeInventory();String enemy=randomMove(b.wild.creature);
        boolean first=Rules.first(Rules.initiative(ally,move),Rules.initiative(b.wild.creature,enemy==null?null:settings().moves().get(enemy)),random);
        if(first) strike(b,b.ally,b.wild,moveId,()->{if(b.wild.creature.hp<=0||enemy==null)completeRound(b);else strike(b,b.wild,b.ally,enemy,()->completeRound(b));});
        else if(enemy==null)strike(b,b.ally,b.wild,moveId,()->completeRound(b));
        else strike(b,b.wild,b.ally,enemy,()->{if(b.ally.creature.hp<=0)completeRound(b);else strike(b,b.ally,b.wild,moveId,()->completeRound(b));});
    }
    private String randomMove(Creature c) {List<String> available=c.pp.entrySet().stream().filter(e->e.getValue()>0).map(Map.Entry::getKey).toList();return available.isEmpty()?null:available.get(random.nextInt(available.size()));}
    private int duration(Actor a,State state) {return settings().animations().get(a.creature.species).get(state).ticks();}
    private void later(Battle b,int ticks,Runnable next) {b.due=world.tick+ticks;b.next=next;}
    private void strike(Battle b,Actor from,Actor target,String moveId,Runnable after) {
        if(!valid(b))return;Move move=settings().moves().get(moveId);from.creature.pp.computeIfPresent(moveId,(m,pp)->Math.max(0,pp-1));
        Skill skill=settings().skills().get(from.creature.species).get(moveId);
        sessions.get(b.player).dirty=true;from.animation.playAttack(skill.clip(),world.tick);world.effects.start(b.id,from,target,skill,world.tick);
        later(b,skill.hitTick(),()->{
            if(!valid(b))return;
            int damage=Rules.damage(move,from.creature,target.creature,settings().types(),settings().species().get(target.creature.species));target.creature.hp=Math.max(0,target.creature.hp-damage);if(damage>0)target.animation.set(State.HURT,world.tick);
            tell(Bukkit.getPlayer(b.player),"battle.move","name",settings().speciesName(from.creature.species),"move",settings().text("moves."+moveId),"damage",damage);
            later(b,Math.max(skill.clip().ticks()-skill.hitTick(),duration(target,State.HURT)),after);
        });
    }

    private void completeRound(Battle b){if(!valid(b))return;sessions.get(b.player).dirty=true;checkAfter(b);}
    private void checkAfter(Battle b) {
        if(!valid(b))return;
        if(b.wild.creature.hp<=0&&b.ally.creature.hp<=0){endBattle(b,"battle.end");return;}if(b.wild.creature.hp<=0){win(b);return;}
        if(b.ally.creature.hp<=0) {
            b.ally.animation.set(State.FAINT,world.tick);
            later(b,duration(b.ally,State.FAINT),()->{
                Session s=sessions.get(b.player);if(s==null){endBattle(b,"battle.end");return;}
                Creature next=s.profile.creatures.stream().filter(c->c.slot<6&&c.hp>0).findFirst().orElse(null);
                if(next==null){tell(Bukkit.getPlayer(b.player),"battle.loss");endBattle(b,null);}
                else {replace(b,next);finishTurn(b);}
            });
        } else finishTurn(b);
    }
    private void win(Battle b) {
        b.wild.animation.set(State.FAINT,world.tick);Session session=sessions.get(b.player);Player player=Bukkit.getPlayer(b.player);
        Profile rewarded=session.profile.copy();int amount=b.wild.creature.level*settings().number("game.xp-per-level");int levels=Rules.experience(rewarded.byId(b.ally.creature.id),amount,settings().number("game.xp-factor"));
        session.busy=true;session.dirty=false;
        // Start the transaction now: leaving during the visual must not separate XP from its receipt.
        repository.saveVictory(rewarded,b.id).whenComplete((saved,error)->main(()->{
            session.busy=false;
            if(error!=null){failure(error);session.dirty=true;if(valid(b))endBattle(b,"battle.end");return;}
            if(player!=null)receive(player,session,saved);if(!valid(b))return;b.ally.creature=saved.byId(b.ally.creature.id);
            later(b,duration(b.wild,State.FAINT),()->{tell(player,"battle.win","xp",amount,"levels",levels);endBattle(b,null,false);world.remove(b.wild);});
        }));
    }
    public void finishTurn(Battle b) {
        if(!valid(b))return;Session s=sessions.get(b.player);if(s==null){endBattle(b,null);return;}
        b.turns++;
        persist(Bukkit.getPlayer(b.player),s,ok->{if(!valid(b))return;if(!ok){endBattle(b,"battle.end",false);return;}b.ally.creature=s.profile.byId(b.ally.creature.id);b.resolving=false;Player p=Bukkit.getPlayer(b.player);if(p!=null)plugin.menus().battle(p,b);});
    }
    public void counterattack(Battle b) {
        if(!valid(b))return;b.resolving=true;String move=randomMove(b.wild.creature);if(move==null)completeRound(b);else strike(b,b.wild,b.ally,move,()->completeRound(b));
    }
    public void switchTo(Player p,UUID creatureId) {
        Battle b=battles.get(p.getUniqueId());if(!canAct(p,b))return;Creature c=sessions.get(b.player).profile.byId(creatureId);
        if(c==null||c.slot>=6||c.hp<=0||c.id.equals(b.ally.creature.id))return;
        b.resolving=true;p.closeInventory();replace(b,c);counterattack(b);
    }
    private void replace(Battle b,Creature c) {
        Actor old=b.ally;Location loc=old.location.clone();Actor next=world.spawn(c,b.player,loc);
        if(next==null){loc=world.near(b.wild.location,Math.max(2.4,(b.wild.model.width()+settings().models().get(c.species).width())/2+.8),settings().species().get(c.species));if(loc!=null)next=world.spawn(c,b.player,loc);}
        if(next==null){endBattle(b,"battle.end");return;}
        b.ally=next;sessions.get(b.player).profile.active=c.id;sessions.get(b.player).dirty=true;next.lockedBy=b.player;face(next,b.wild);face(b.wild,next);world.remove(old);
    }
    public void flee(Player p) {Battle b=battles.get(p.getUniqueId());if(canAct(p,b))endBattle(b,"battle.end");}
    public void endBattle(Battle b,String message) {endBattle(b,message,true);}
    public void endBattle(Battle b,String message,boolean save) {
        if(b==null||b.ended)return;b.ended=true;b.next=null;battles.remove(b.player,b);capture.cancelPlayer(b.player);world.effects.stop(b.id);
        if(!capture.protectedTarget(b.wild)){b.wild.lockedBy=null;b.wild.capturing=false;if(!b.wild.removed)b.wild.animation.set(State.IDLE,world.tick);}world.remove(b.ally);
        if(b.wild.creature.hp<=0&&!capture.protectedTarget(b.wild))world.remove(b.wild);
        Player p=Bukkit.getPlayer(b.player);Session s=sessions.get(b.player);
        if(s!=null){s.dirty=true;if(save&&!s.busy)persist(p,s,null);}
        if(p!=null){p.closeInventory();if(message!=null)tell(p,message);}
    }
    private void removed(Actor a) {for(Battle b:List.copyOf(battles.values()))if(b.ally==a||b.wild==a)endBattle(b,"battle.end");capture.removed(a);}
    public void tick() {
        world.update();capture.tick();
        for(Battle b:List.copyOf(battles.values())) {
            Player p=Bukkit.getPlayer(b.player);
            if(p==null||!p.isOnline()||!p.getWorld().equals(b.wild.location.getWorld())||p.getLocation().distanceSquared(b.wild.location)>48*48){endBattle(b,"battle.end");continue;}
            if(b.next!=null&&world.tick>=b.due){Runnable next=b.next;b.next=null;next.run();}
        }
        if(world.tick%200==0) for(var e:sessions.entrySet()){Session s=e.getValue();Player p=Bukkit.getPlayer(e.getKey());if(p!=null)deliverBalls(p);if(s.dirty&&!s.busy&&!battles.containsKey(e.getKey())&&!capture.hasThrow(e.getKey())&&!activityLocked(e.getKey()))persist(p,s,null);
        }
    }
    public void quit(Player p) {
        loads.remove(p.getUniqueId());Battle battle=battles.get(p.getUniqueId());if(battle!=null)endBattle(battle,null,false);
        capture.cancelPlayer(p.getUniqueId());Session s=sessions.remove(p.getUniqueId());
        if(s!=null&&!s.busy)repository.save(s.profile).exceptionally(e->{failure(e);return null;});
    }
    public void reconfigure() {
        for(Session session:sessions.values()){
            for(Creature creature:session.profile.creatures)creature.bind(settings().species().get(creature.species),true);
            session.dirty=true;
        }
    }
    public void shutdown() {
        for(Battle b:List.copyOf(battles.values()))endBattle(b,null,false);capture.shutdown();
        for(Session s:sessions.values())if(!s.busy)repository.save(s.profile).exceptionally(e->{failure(e);return null;});
        stopping=true;world.clear();sessions.clear();
    }
    @EventHandler public void join(PlayerJoinEvent e){load(e.getPlayer());}
    @EventHandler public void leave(PlayerQuitEvent e){quit(e.getPlayer());}
    @EventHandler public void death(PlayerDeathEvent e) {Battle b=battles.get(e.getEntity().getUniqueId());if(b!=null)endBattle(b,"battle.end");capture.cancelPlayer(e.getEntity().getUniqueId());}
    @EventHandler public void worldChange(PlayerChangedWorldEvent e){Battle b=battles.get(e.getPlayer().getUniqueId());if(b!=null)endBattle(b,"battle.end");capture.cancelPlayer(e.getPlayer().getUniqueId());}
    @EventHandler(ignoreCancelled=true) public void interact(PlayerInteractEntityEvent e){Actor a=world.interaction(e.getRightClicked().getUniqueId());if(a!=null){e.setCancelled(true);if(e.getHand()==EquipmentSlot.HAND){if(isBall(e.getPlayer().getInventory().getItemInMainHand()))capture.throwBall(e.getPlayer(),EquipmentSlot.HAND);else startBattle(e.getPlayer(),a);}}}
}
