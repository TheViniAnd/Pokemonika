package ru.vanillamon;

import org.junit.jupiter.api.*;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.damage.DamageSource;
import org.bukkit.event.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import io.papermc.paper.event.entity.EntityKnockbackEvent;
import org.bukkit.util.Vector;
import ru.vanillamon.game.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.BiFunction;
import static org.junit.jupiter.api.Assertions.*;

class CombatProtectionTest {
    Set<UUID> battling=new HashSet<>(),models=new HashSet<>(),balls=new HashSet<>();
    CombatProtection protection=new CombatProtection(battling::contains,e->models.contains(e.getUniqueId()),e->balls.contains(e.getUniqueId()),l->false,item->false);
    @SuppressWarnings("unchecked") <T>T proxy(Class<T> type,UUID id,BiFunction<String,Object[],Object> extra){return (T)Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(p,m,a)->switch(m.getName()){case "getUniqueId"->id;case "equals"->p==a[0];case "hashCode"->System.identityHashCode(p);case "toString"->type.getSimpleName();default->extra.apply(m.getName(),a);});}
    <T>T entity(Class<T> type){return proxy(type,UUID.randomUUID(),(n,a)->null);}
    Player player(){return entity(Player.class);}
    DamageSource source(Entity owner){return proxy(DamageSource.class,UUID.randomUUID(),(n,a)->n.equals("getCausingEntity")?owner:null);}
    EntityDamageByEntityEvent hit(Entity from,Entity to){return new EntityDamageByEntityEvent(from,to,EntityDamageEvent.DamageCause.ENTITY_ATTACK,source(from),4);}
    @Test void ordinaryPlayersMayFightOutsidePokemonBattles(){Player a=player(),b=player();var e=hit(a,b);protection.damage(e);assertFalse(e.isCancelled());var click=new PrePlayerAttackEntityEvent(a,b,true);protection.attack(click);assertFalse(click.isCancelled());}
    @Test void eitherHumanParticipantPreventsIncomingAndOutgoingMelee(){Player a=player(),b=player();battling.add(a.getUniqueId());for(var pair:List.of(new Entity[]{a,b},new Entity[]{b,a})){var e=hit(pair[0],pair[1]);protection.damage(e);assertTrue(e.isCancelled());}var click=new PrePlayerAttackEntityEvent(a,b,true);protection.attack(click);assertTrue(click.isCancelled());}
    @Test void leavingBattleRestoresNormalDamage(){Player a=player(),b=player();battling.add(a.getUniqueId());var first=hit(a,b);protection.damage(first);assertTrue(first.isCancelled());battling.clear();var second=hit(a,b);protection.damage(second);assertFalse(second.isCancelled());}
    @Test void PokemonRepresentationCannotBeHitEvenOutsideBattle(){Player a=player();Interaction mon=entity(Interaction.class);models.add(mon.getUniqueId());var click=new PrePlayerAttackEntityEvent(a,mon,true);protection.attack(click);assertTrue(click.isCancelled());var e=hit(a,mon);protection.damage(e);assertTrue(e.isCancelled());}
    @Test void environmentDamageAndKnockbackCannotInterruptParticipant(){Player a=player();battling.add(a.getUniqueId());var e=new EntityDamageEvent(a,EntityDamageEvent.DamageCause.FIRE,source(null),4);protection.damage(e);assertTrue(e.isCancelled());var push=new EntityKnockbackEvent(a,EntityKnockbackEvent.Cause.ENTITY_ATTACK,new Vector(1,0,0));protection.knockback(push);assertTrue(push.isCancelled());}
    @Test void arrowsResolveShooterAndCannotBeLaunchedDuringBattleExceptPokeball(){Player a=player(),b=player();battling.add(a.getUniqueId());Arrow arrow=proxy(Arrow.class,UUID.randomUUID(),(n,x)->n.equals("getShooter")?a:null);var event=hit(arrow,b);protection.damage(event);assertTrue(event.isCancelled());var launch=new ProjectileLaunchEvent(arrow);protection.launch(launch);assertTrue(launch.isCancelled());Snowball ball=proxy(Snowball.class,UUID.randomUUID(),(n,x)->n.equals("getShooter")?a:null);balls.add(ball.getUniqueId());var capture=new ProjectileLaunchEvent(ball);protection.launch(capture);assertFalse(capture.isCancelled());}
    @Test void petsAndTntAreAttributedToTheirBattlingOwner(){Player a=player(),b=player();battling.add(a.getUniqueId());Wolf wolf=proxy(Wolf.class,UUID.randomUUID(),(n,x)->n.equals("getOwner")?a:null);TNTPrimed tnt=proxy(TNTPrimed.class,UUID.randomUUID(),(n,x)->n.equals("getSource")?a:null);for(Entity e:List.of(wolf,tnt)){var damage=hit(e,b);protection.damage(damage);assertTrue(damage.isCancelled());}}
    @Test void potionFromOutsiderAffectsOnlyUnprotectedRecipients(){Player a=player(),b=player(),thrower=player();battling.add(a.getUniqueId());ThrownPotion potion=proxy(ThrownPotion.class,UUID.randomUUID(),(n,x)->n.equals("getShooter")?thrower:null);var e=new PotionSplashEvent(potion,null,null,null,new HashMap<>(Map.of(a,1d,b,1d)));protection.splash(e);assertEquals(0,e.getIntensity(a));assertEquals(1,e.getIntensity(b));}
    @Test void battlingPlayerCannotUseSplashAgainstOthers(){Player a=player(),b=player();battling.add(a.getUniqueId());ThrownPotion potion=proxy(ThrownPotion.class,UUID.randomUUID(),(n,x)->n.equals("getShooter")?a:null);var e=new PotionSplashEvent(potion,null,null,null,new HashMap<>(Map.of(b,1d)));protection.splash(e);assertEquals(0,e.getIntensity(b));}
    @Test void lingeringCloudFiltersParticipantsAndResolvesOwner(){Player a=player(),b=player();battling.add(a.getUniqueId());AreaEffectCloud cloud=entity(AreaEffectCloud.class);var e=new AreaEffectCloudApplyEvent(cloud,new ArrayList<>(List.of(a,b)));protection.cloud(e);assertEquals(List.of(b),e.getAffectedEntities());AreaEffectCloud owned=proxy(AreaEffectCloud.class,UUID.randomUUID(),(n,x)->n.equals("getSource")?a:null);var attack=new AreaEffectCloudApplyEvent(owned,new ArrayList<>(List.of(b)));protection.cloud(attack);assertTrue(attack.getAffectedEntities().isEmpty());}
    @Test void hostileMobsDoNotAcquireParticipantAsTarget(){Player a=player();battling.add(a.getUniqueId());var e=new EntityTargetLivingEntityEvent(entity(Zombie.class),a,EntityTargetEvent.TargetReason.CLOSEST_PLAYER);protection.target(e);assertTrue(e.isCancelled());}
    @Test void indirectDamageSourceCannotBypassOutgoingProtection(){Player a=player(),b=player();battling.add(a.getUniqueId());var e=new EntityDamageEvent(b,EntityDamageEvent.DamageCause.ENTITY_EXPLOSION,source(a),5);protection.damage(e);assertTrue(e.isCancelled());}
    @Test void fishingHookCannotPullParticipant(){Player a=player(),outsider=player();battling.add(a.getUniqueId());boolean[] removed={false};FishHook hook=proxy(FishHook.class,UUID.randomUUID(),(n,x)->{if(n.equals("remove"))removed[0]=true;return null;});var e=new PlayerFishEvent(outsider,a,hook,PlayerFishEvent.State.CAUGHT_ENTITY);protection.fish(e);assertTrue(e.isCancelled());assertTrue(removed[0]);}
    @Test void boatsCannotTakeProtectedEntityOrParticipantButWorkForOrdinaryPlayers(){Villager nurse=entity(Villager.class);models.add(nurse.getUniqueId());Player a=player(),b=player();battling.add(a.getUniqueId());for(Entity target:List.of(nurse,a)){var e=new VehicleEnterEvent(entity(Boat.class),target);protection.vehicle(e);assertTrue(e.isCancelled());}var ordinary=new VehicleEnterEvent(entity(Boat.class),b);protection.vehicle(ordinary);assertFalse(ordinary.isCancelled());}
    @Test void cyclicProjectileOwnersCannotHangEventHandler(){UUID id=UUID.randomUUID();Entity[] cyclic=new Entity[1];cyclic[0]=proxy(TNTPrimed.class,id,(n,x)->n.equals("getSource")?cyclic[0]:null);assertFalse(protection.sourceInBattle(cyclic[0]));}
    @Test void eventHandlersHaveRegistrableHandlerLists() throws Exception {for(Class<?> listener:List.of(CombatProtection.class)){for(Method method:listener.getDeclaredMethods())if(method.isAnnotationPresent(EventHandler.class)){Class<?> event=method.getParameterTypes()[0],at=event;Method handler=null;while(at!=null&&Event.class.isAssignableFrom(at)){try{handler=at.getDeclaredMethod("getHandlerList");break;}catch(NoSuchMethodException ignored){at=at.getSuperclass();}}assertNotNull(handler,event.getName());assertTrue(Modifier.isStatic(handler.getModifiers()));assertNotNull(handler.invoke(null));}}assertNull(Bukkit.getServer());}
}
