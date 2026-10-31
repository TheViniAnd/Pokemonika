package ru.vanillamon.game;

import ru.vanillamon.VanillaMon;
import ru.vanillamon.domain.*;
import ru.vanillamon.domain.Creature;
import ru.vanillamon.domain.Animation.State;
import ru.vanillamon.world.Actor;
import ru.vanillamon.world.PokeballVisual;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.util.Vector;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import java.util.*;

/** Sweep logical hit regions explicitly: snowballs do not reliably collide with Interaction. */
public final class Capture implements Listener {
    private static final class Throw {
        final UUID operation=UUID.randomUUID(),player;
        final Game.Session session;
        final Player initiator;
        final Battle battle;
        final World world;
        final BallType ball;
        Snowball projectile;
        PokeballVisual visual;
        Location previous;
        Actor target;
        long started,finish;
        boolean committing,done;
        double chance;
        Throw(Player p,Game.Session s,Battle b,BallType ball,long tick){player=p.getUniqueId();initiator=p;session=s;battle=b;this.ball=ball;world=p.getWorld();started=tick;}
    }
    private final VanillaMon plugin;
    private final Game game;
    private final Map<UUID,Throw> throwsByPlayer=new HashMap<>();
    private final Map<UUID,Throw> projectiles=new HashMap<>();
    private final Random random=new Random();
    Capture(VanillaMon plugin,Game game){this.plugin=plugin;this.game=game;}
    public boolean protectedTarget(Actor a){return throwsByPlayer.values().stream().anyMatch(t->t.target==a&&t.committing);}
    public boolean hasThrow(UUID player){return throwsByPlayer.containsKey(player);}
    public boolean hasAnyThrow(){return !throwsByPlayer.isEmpty();}
    public void throwBall(Player p,EquipmentSlot hand){begin(p,hand,false,hand==null?null:game.ballType(p.getInventory().getItem(hand)));}
    public void inBattle(Player p){inBattle(p,"poke");}
    public void inBattle(Player p,String type){begin(p,null,true,type);}
    public CatchRules.Result chance(Player p,Actor target,String type,Battle battle){
        return CatchRules.calculate(game.settings().species().get(target.creature.species),target.creature,game.settings().balls().get(type),game.settings().catchPolicy());
    }
    private void begin(Player p,EquipmentSlot hand,boolean direct,String type){
        if(!game.available(p,false))return;Game.Session s=game.sessions.get(p.getUniqueId());Battle b=game.battles.get(p.getUniqueId());
        if(direct&&b==null)return;
        if(b!=null&&(!game.valid(b)||b.resolving))return;
        if(s.profile.freeSlot()<0){game.tell(p,"full");return;}
        if(type==null||!game.settings().balls().containsKey(type)||game.countBalls(p,type)<1){game.tell(p,"capture.no-ball");return;}
        Throw t=new Throw(p,s,b,game.settings().balls().get(type),game.world.tick);throwsByPlayer.put(t.player,t);s.busy=true;if(b!=null)b.resolving=true;
        game.repository.prepare(t.operation,t.player,"CAPTURE").whenComplete((prepared,error)->game.main(()->{
            s.busy=false;
            if(t.done||!game.current(p,s)||p.getWorld()!=t.world||p.isDead()||!plugin.allowed(p,"play")){abort(t,false);return;}
            if(error!=null){game.failure(error);game.tell(p,"error");abort(t,false);return;}
            if(!prepared){game.tell(p,"full");abort(t,false);return;}
            if(b!=null&&!game.valid(b)){abort(t,false);return;}
            if(!(direct?game.takeAnyBall(p,type):game.takeBall(p,hand,type))){game.tell(p,"capture.no-ball");abort(t,false);return;}
            try{p.saveData();}catch(RuntimeException failure){game.failure(failure);game.tell(p,"error");abort(t,true);return;}
            // Save consumption before any ownership commit; ambiguous failures do not refund.
            game.tell(p,"capture.throw");p.closeInventory();
            try{
                if(direct){if(!lock(t,b.wild))abort(t,true);return;}
                t.projectile=p.launchProjectile(Snowball.class,p.getEyeLocation().getDirection().multiply(1.35),ball->{ball.setVisibleByDefault(false);ball.setPersistent(false);ball.getPersistentDataContainer().set(new org.bukkit.NamespacedKey("vanillamon","ball_projectile"),org.bukkit.persistence.PersistentDataType.BYTE,(byte)1);});
                t.visual=new PokeballVisual(plugin,t.projectile.getLocation(),t.ball);
                t.previous=t.projectile.getLocation();projectiles.put(t.projectile.getUniqueId(),t);
            }catch(RuntimeException failure){plugin.getLogger().log(java.util.logging.Level.WARNING,"Pokeball representation failed",failure);abort(t,true);}
        }));
    }
    private boolean lock(Throw t,Actor a){
        if(a.removed||!a.wild()||a.capturing||a.creature.hp<=0)return false;
        if(t.battle!=null){if(t.battle.wild!=a||!game.valid(t.battle)||!t.player.equals(a.lockedBy))return false;}
        else if(!a.lock(t.player))return false;
        a.capturing=true;a.destination=null;t.target=a;
        t.chance=chance(t.initiator,a,t.ball.id(),t.battle).chance();
        t.session.profile.dex.merge(a.creature.species,1,Math::max);t.session.dirty=true;
        a.animation.set(State.CAPTURE,game.world.tick);t.finish=game.world.tick+game.settings().animations().get(a.creature.species).get(State.CAPTURE).ticks()+20;
        if(t.visual==null)t.visual=new PokeballVisual(plugin,a.location.clone().add(0,.2,0),t.ball);
        t.visual.update(a.location.clone().add(0,.2,0),game.world.tick-t.started,true);
        removeProjectile(t);return true;
    }
    public void tick(){
        for(Throw t:List.copyOf(throwsByPlayer.values())){
            if(t.done||t.committing)continue;
            if(t.target!=null){if(t.visual!=null)t.visual.update(t.target.location.clone().add(0,.2,0),game.world.tick-t.started,true);if(game.world.tick>=t.finish)resolve(t);else if(game.world.tick%8==0)game.world.effects.emit(Particle.CRIT,t.target.location.clone().add(0,.2,0),3,.1,.1,.1,null);}
            else if(t.projectile!=null){
                if(!t.projectile.isValid()||game.world.tick-t.started>100){miss(t);continue;}
                Location now=t.projectile.getLocation();if(t.visual!=null)t.visual.update(now,game.world.tick-t.started,false);sweep(t,t.previous,now);t.previous=now;
            }else if(game.world.tick-t.started>200)abort(t,false);
        }
    }
    private void sweep(Throw t,Location start,Location end){
        if(t.target!=null||t.done||start.getWorld()!=end.getWorld())return;
        Vector delta=end.toVector().subtract(start.toVector());double length=delta.length();if(length<.0001)return;Vector direction=delta.multiply(1/length);
        RayTraceResult wall=start.getWorld().rayTraceBlocks(start,direction,length,FluidCollisionMode.NEVER,true);
        double limit=wall==null?length:wall.getHitPosition().distance(start.toVector());
        Actor nearest=null;double distance=Double.MAX_VALUE;
        for(Actor a:game.world.actors.values()){
            if(!a.wild()||a.removed||a.capturing||a.location.getWorld()!=start.getWorld()||(t.battle!=null&&t.battle.wild!=a)||(a.lockedBy!=null&&!t.player.equals(a.lockedBy)))continue;
            double half=a.model.width()/2+.12;Location l=a.location;
            BoundingBox box=new BoundingBox(l.getX()-half,l.getY()-.12,l.getZ()-half,l.getX()+half,l.getY()+a.model.height()+.12,l.getZ()+half);
            RayTraceResult hit=box.rayTrace(start.toVector(),direction,limit);
            if(hit!=null){double d=hit.getHitPosition().distanceSquared(start.toVector());if(d<distance){distance=d;nearest=a;}}
        }
        if(nearest!=null)lock(t,nearest);
    }
    private void resolve(Throw t){
        Player p=t.initiator;Actor a=t.target;
        if(a==null||a.removed||p==null||!game.current(p,t.session)||p.getWorld()!=t.world){abort(t,false);return;}
        if(random.nextDouble()>=t.chance){game.tell(p,"capture.failure");abort(t,true);return;}
        t.committing=true;t.session.busy=true;Creature caught=a.creature.copy();
        game.repository.grant(t.operation,t.session.profile,caught,false).whenComplete((saved,error)->game.main(()->{
            t.session.busy=false;
            if(error!=null){game.failure(error);if(p.isOnline())game.tell(p,"error");t.committing=false;abort(t,true);return;}
            // The commit is authoritative even if the player disconnected during the DB call.
            game.receive(p,t.session,saved);finish(t);
            if(t.battle!=null)game.endBattle(t.battle,null,false);
            game.world.remove(a);
            if(game.current(p,t.session))game.tell(p,"capture.success","name",game.settings().speciesName(caught.species),"place",game.settings().text(saved.byId(caught.id).slot<6?"lore.team":"lore.pc"));
        }));
    }
    private void miss(Throw t){abort(t,true);}
    private void removeProjectile(Throw t){if(t.projectile!=null){projectiles.remove(t.projectile.getUniqueId());t.projectile.remove();t.projectile=null;}}
    private void finish(Throw t){t.done=true;throwsByPlayer.remove(t.player,t);removeProjectile(t);if(t.visual!=null){t.visual.remove();t.visual=null;}}
    private void abort(Throw t,boolean spentTurn){
        if(t.done||t.committing)return;finish(t);game.repository.abort(t.operation).exceptionally(e->{game.failure(e);return null;});
        Actor a=t.target;if(a!=null&&!a.removed){a.capturing=false;a.animation.set(State.IDLE,game.world.tick);if(t.battle==null)a.lockedBy=null;}
        if(t.battle!=null&&game.valid(t.battle)){if(spentTurn)game.counterattack(t.battle);else t.battle.resolving=false;}
    }
    public void cancelPlayer(UUID player){Throw t=throwsByPlayer.get(player);if(t!=null)abort(t,false);}
    public void removed(Actor actor){for(Throw t:List.copyOf(throwsByPlayer.values()))if(t.target==actor)abort(t,false);}
    public void shutdown(){for(Throw t:List.copyOf(throwsByPlayer.values())){if(t.committing)finish(t);else abort(t,false);}}
    @EventHandler public void cleanup(ChunkLoadEvent event){for(Entity entity:event.getChunk().getEntities())if(entity.getPersistentDataContainer().has(new NamespacedKey("vanillamon","pokeball_visual")))entity.remove();}
    @EventHandler(priority=EventPriority.HIGH) public void use(PlayerInteractEvent e){
        if((e.getAction()==Action.RIGHT_CLICK_AIR||e.getAction()==Action.RIGHT_CLICK_BLOCK)&&game.isBall(e.getItem())){e.setCancelled(true);if(e.getHand()!=null)throwBall(e.getPlayer(),e.getHand());}
    }
    @EventHandler(priority=EventPriority.HIGHEST) public void hit(ProjectileHitEvent e){
        Throw t=projectiles.get(e.getEntity().getUniqueId());if(t==null)return;
        Location end=e.getEntity().getLocation().clone().add(e.getEntity().getVelocity());sweep(t,t.previous,end);
        e.setCancelled(true);if(t.target==null)miss(t);
    }
}
