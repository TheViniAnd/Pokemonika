package ru.vanillamon.game;

import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.*;
import org.bukkit.event.block.*;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.Location;
import org.bukkit.inventory.ItemStack;
import org.bukkit.projectiles.ProjectileSource;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import io.papermc.paper.event.entity.EntityKnockbackEvent;
import java.util.*;
import java.util.function.Predicate;

/** Minecraft damage never changes Pokemon HP. The protection also isolates human participants. */
public final class CombatProtection implements Listener {
    private final Predicate<UUID> battling;private final Predicate<Entity> representation,ball;private final Predicate<Location> arena;private final Predicate<ItemStack> ballItem;
    public CombatProtection(Predicate<UUID> battling,Predicate<Entity> representation,Predicate<Entity> ball,Predicate<Location> arena,Predicate<ItemStack> ballItem){this.battling=battling;this.representation=representation;this.ball=ball;this.arena=arena;this.ballItem=ballItem;}
    public boolean participant(Entity e){return e instanceof Player&&battling.test(e.getUniqueId());}
    public boolean protectedEntity(Entity e){return e!=null&&(representation.test(e)||participant(e));}
    public boolean sourceInBattle(Entity e){return sourceInBattle(e,new HashSet<>(),0);}
    private boolean sourceInBattle(Entity e,Set<UUID> seen,int depth){
        if(e==null||depth>5||!seen.add(e.getUniqueId()))return false;if(participant(e))return true;
        if(e instanceof Tameable pet&&pet.getOwner()!=null&&battling.test(pet.getOwner().getUniqueId()))return true;
        if(e instanceof Projectile p&&p.getShooter() instanceof Entity owner)return sourceInBattle(owner,seen,depth+1);
        if(e instanceof AreaEffectCloud cloud&&cloud.getSource() instanceof Entity owner)return sourceInBattle(owner,seen,depth+1);
        if(e instanceof TNTPrimed tnt)return sourceInBattle(tnt.getSource(),seen,depth+1);
        if(e instanceof EvokerFangs fangs)return sourceInBattle(fangs.getOwner(),seen,depth+1);return false;
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void attack(PrePlayerAttackEntityEvent e){if(participant(e.getPlayer())||protectedEntity(e.getAttacked()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void damage(EntityDamageEvent e){
        if(protectedEntity(e.getEntity())||e.getEntity() instanceof Player&&arena.test(e.getEntity().getLocation())||e instanceof EntityDamageByEntityEvent by&&sourceInBattle(by.getDamager())||e.getDamageSource()!=null&&sourceInBattle(e.getDamageSource().getCausingEntity()))e.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void launch(ProjectileLaunchEvent e){if(sourceInBattle(e.getEntity())&&!ball.test(e.getEntity()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void splash(PotionSplashEvent e){boolean source=sourceInBattle(e.getEntity());for(LivingEntity target:e.getAffectedEntities())if(source||protectedEntity(target))e.setIntensity(target,0);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void cloud(AreaEffectCloudApplyEvent e){if(sourceInBattle(e.getEntity()))e.getAffectedEntities().clear();else e.getAffectedEntities().removeIf(this::protectedEntity);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void potion(EntityPotionEffectEvent e){if(e.getNewEffect()!=null&&(protectedEntity(e.getEntity())||sourceInBattle(e.getSource())))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void knockback(EntityKnockbackEvent e){if(protectedEntity(e.getEntity()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void velocity(PlayerVelocityEvent e){if(participant(e.getPlayer()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void combust(EntityCombustEvent e){if(protectedEntity(e.getEntity())||e instanceof EntityCombustByEntityEvent by&&sourceInBattle(by.getCombuster()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void target(EntityTargetLivingEntityEvent e){if(protectedEntity(e.getTarget()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void fish(PlayerFishEvent e){if(participant(e.getPlayer())||protectedEntity(e.getCaught())){e.setCancelled(true);e.getHook().remove();}}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void place(BlockPlaceEvent e){if(participant(e.getPlayer()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void breakBlock(BlockBreakEvent e){if(participant(e.getPlayer()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void empty(PlayerBucketEmptyEvent e){if(participant(e.getPlayer()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void fill(PlayerBucketFillEvent e){if(participant(e.getPlayer()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void use(PlayerInteractEvent e){if(participant(e.getPlayer())&&!ballItem.test(e.getItem()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void transform(EntityTransformEvent e){if(representation.test(e.getEntity()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void vehicle(VehicleEnterEvent e){if(protectedEntity(e.getEntered()))e.setCancelled(true);}
}
