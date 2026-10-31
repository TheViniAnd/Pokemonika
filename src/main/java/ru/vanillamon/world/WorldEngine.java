package ru.vanillamon.world;

import ru.vanillamon.VanillaMon;
import ru.vanillamon.config.Settings;
import ru.vanillamon.domain.*;
import ru.vanillamon.domain.Creature;
import ru.vanillamon.domain.Animation.*;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.world.*;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;
import java.util.*;
import java.util.function.*;

public final class WorldEngine implements Listener {
    private final VanillaMon plugin;
    public final Map<UUID,Actor> actors=new LinkedHashMap<>();
    public final BattleEffects effects;
    private final Map<UUID,Actor> interactions=new HashMap<>();
    private final NamespacedKey marker;
    public long tick;
    public Consumer<Actor> onRemove=a->{};
    public BiConsumer<Player,Actor> onSeen=(p,a)->{};
    public Predicate<Location> spawnAllowed=l->true;
    private final Random random=new Random();
    private int navigationBudget;
    private boolean probingPath;
    public WorldEngine(VanillaMon plugin) { this.plugin=plugin; marker=new NamespacedKey("vanillamon","representation");effects=new BattleEffects(plugin::settings); }
    private Settings settings() { return plugin.settings(); }
    private void mark(Entity e) { e.setPersistent(false);e.setInvulnerable(true);e.setGravity(false);e.setSilent(true);e.setVisibleByDefault(false);e.getPersistentDataContainer().set(marker,PersistentDataType.BYTE,(byte)1); }
    public Actor spawn(Creature c,UUID owner,Location location) {

        if(owner==null&&!spawnAllowed.test(location))return null;
        if(!canSpawn(settings().species().get(c.species),location))return null;
        Actor a=new Actor(c,owner,location,settings().models().get(c.species),settings().animations().get(c.species));
        try {
            for(Part part:a.model.parts()) {
                BlockDisplay block=location.getWorld().spawn(location,BlockDisplay.class,e->{
                    mark(e);e.setBlock(Material.valueOf(part.material()).createBlockData());e.setInterpolationDuration(settings().number("engine.interpolation-ticks"));e.setInterpolationDelay(0);e.setTeleportDuration(settings().number("engine.update-ticks"));e.setViewRange(1f);e.setDisplayWidth(Math.max(4,a.model.width()*2));e.setDisplayHeight(Math.max(4,a.model.height()*2));
                }); a.blocks.add(block);a.matrices.add(null);
            }
            a.hitbox=location.getWorld().spawn(location,Interaction.class,e->{mark(e);e.setInteractionWidth(a.model.width());e.setInteractionHeight(a.model.height());e.setResponsive(true);});
            a.label=location.getWorld().spawn(location.clone().add(0,a.model.height()+.2,0),TextDisplay.class,e->{mark(e);e.setBillboard(Display.Billboard.CENTER);e.setSeeThrough(false);e.setShadowed(true);e.setViewRange(1);e.setTeleportDuration(settings().number("engine.update-ticks"));});
            a.lastNear=tick;a.born=tick;actors.put(a.id,a);interactions.put(a.hitbox.getUniqueId(),a); render(a,true);visibility(); return a;
        } catch(RuntimeException e) { a.entities().forEach(Entity::remove);throw e; }
    }
    public boolean canSpawn(Species definition,Location location){
        if(location==null||location.getWorld()==null||!loaded(location))return false;Model footprint=settings().models().get(definition.id());
        boolean submerged=safeWater(location,footprint.width(),footprint.height());
        return definition.habitat()==Species.Habitat.WATER?submerged:safe(location,footprint.width(),footprint.height())||definition.habitat()==Species.Habitat.SHORE&&submerged;
    }
    public boolean representation(Entity entity){return entity.getPersistentDataContainer().has(marker);}
    public Actor interaction(UUID entity) { return interactions.get(entity); }
    public void remove(Actor a) {
        if(a==null||a.removed) return; a.removed=true;actors.remove(a.id); if(a.hitbox!=null) interactions.remove(a.hitbox.getUniqueId());
        effects.remove(a);onRemove.accept(a); a.entities().forEach(Entity::remove);a.viewers.clear();a.destination=null;
    }
    public void clear() { effects.clear();for(Actor a:List.copyOf(actors.values())) remove(a); }
    public int parts() { return actors.values().stream().mapToInt(a->a.blocks.size()+2).sum(); }
    public void update() {
        tick++;navigationBudget=6000;
        effects.update(tick);
        if(tick%settings().number("engine.update-ticks")==0) for(Actor a:List.copyOf(actors.values())) {
            if(a.removed) continue;
            if(a.wild()&&a.lockedBy==null&&!a.capturing&&tick-a.born>settings().number("spawn.lifetime-ticks")){remove(a);continue;}
            if(!loaded(a.location)||!a.hitbox.isValid()||a.blocks.stream().anyMatch(e->!e.isValid())) {remove(a);continue;}
            if(settings().species().get(a.creature.species).habitat()==Species.Habitat.WATER&&!safeWater(a.location,a.model.width(),a.model.height())){remove(a);continue;}
            if(a.animation.finished(tick)&&!a.capturing&&a.animation.state()!=State.FAINT) a.animation.set(State.IDLE,tick);
            if(a.lockedBy==null&&!a.capturing&&a.animation.state()!=State.FAINT) move(a);
            render(a,false);
        }
        if(tick%20==0) visibility();
        if(tick%settings().number("spawn.interval-ticks")==0&&settings().general().getBoolean("spawn.enabled")) naturalSpawn();
    }
    private void render(Actor a,boolean force) {
        Map<String,Animation.Pose> poses=a.animation.poses(tick);float scale=a.animation.scale(tick);
        Location current=a.blocks.getFirst().getLocation();boolean sameWorld=current.getWorld().equals(a.location.getWorld());boolean moved=!sameWorld||current.distanceSquared(a.location)>.00001;
        if(moved) {
            boolean jump=!sameWorld||current.distanceSquared(a.location)>16;
            for(BlockDisplay b:a.blocks) {b.setTeleportDuration(jump?0:settings().number("engine.update-ticks"));b.teleport(a.location);}
            a.hitbox.teleport(a.location);a.label.teleport(a.location.clone().add(0,a.model.height()+.2,0));
        }
        for(int i=0;i<a.blocks.size();i++) {
            Matrix4f m=Animation.matrix(a.model.parts().get(i),poses,a.yaw,scale);Matrix4f old=a.matrices.get(i);
            if(force||old==null||!old.equals(m,.0001f)) {BlockDisplay b=a.blocks.get(i);b.setInterpolationDelay(0);b.setTransformationMatrix(m);a.matrices.set(i,m);}
        }
        String text=settings().text("world.label","name",settings().speciesName(a.creature.species),"level",a.creature.level,"hp",a.creature.hp,"max",a.creature.maxHp());
        if(!text.equals(a.lastLabel)) {a.label.text(Component.text(text));a.lastLabel=text;}
        if(a.creature.species.equals("charmander")&&!a.capturing&&tick%10==0&&!a.viewers.isEmpty()) {
            Part tail=a.model.parts().stream().filter(p->p.group().startsWith("tail")).reduce((a1,b)->b).orElse(a.model.parts().getLast());
            var point=Animation.matrix(tail,poses,a.yaw,scale).transformPosition(new org.joml.Vector3f(.5f,1,.5f));effects.emit(Particle.FLAME,a.location.clone().add(point.x,point.y,point.z),1,.01,.01,.01,null);
        }
    }
    private void move(Actor a) {
        if(!a.wild())return;
        if(a.destination==null&&tick>=a.nextWander) {
            a.nextWander=tick+40+random.nextInt(100);
            Location proposed=a.home.clone().add(random.nextDouble(-5,5),0,random.nextDouble(-5,5));
            a.destination=movementSurface(a,proposed,2);
        }
        if(a.destination==null) {a.animation.set(State.IDLE,tick);return;}
        Vector delta=a.destination.toVector().subtract(a.location.toVector());delta.setY(0);
        if(delta.lengthSquared()<.15) {a.destination=null;a.animation.set(State.IDLE,tick);return;}
        delta.normalize().multiply(.055*settings().number("engine.update-ticks"));
        Location next=a.location.clone().add(delta);Location ground=movementSurface(a,next,1);
        if(ground==null||Math.abs(ground.getY()-a.location.getY())>.55) {
            // Deflect around simple obstacles; each candidate still goes through collision checks.
            boolean found=false;
            for(double angle:new double[]{Math.PI/3,-Math.PI/3}) {Location side=a.location.clone().add(delta.clone().rotateAroundY(angle));Location safe=movementSurface(a,side,1);if(safe!=null&&Math.abs(safe.getY()-a.location.getY())<=.55) {ground=safe;delta=safe.toVector().subtract(a.location.toVector());found=true;break;}}
            if(!found) {a.destination=null;a.animation.set(State.IDLE,tick);return;}
        }
        a.yaw=(float)Math.atan2(delta.getX(),delta.getZ());a.location=ground;a.animation.set(State.WALK,tick);
    }
    private boolean loaded(Location l) {return l.getWorld()!=null&&l.getWorld().isChunkLoaded(l.getBlockX()>>4,l.getBlockZ()>>4);}
    private Location movementSurface(Actor a,Location location,int vertical) {
        Species.Habitat habitat=settings().species().get(a.creature.species).habitat();
        if(habitat==Species.Habitat.WATER||habitat==Species.Habitat.SHORE&&a.location.getBlock().getType()==Material.WATER)return waterSurface(location,a.model.width(),a.model.height(),vertical);
        return surface(location,a.model.width(),a.model.height(),vertical);
    }
    public Location near(Location origin,double radius,Species species) {
        Model model=settings().models().get(species.id());
        for(double r:new double[]{radius,radius+2,radius+4})for(int i=0;i<16;i++){
            double angle=i*Math.PI/8;Location p=origin.clone().add(Math.cos(angle)*r,0,Math.sin(angle)*r);
            Location safe=species.habitat()==Species.Habitat.WATER?waterSurface(p,model.width(),model.height(),6):surface(p,model.width(),model.height(),6);
            if(safe!=null)return safe;
        }return null;
    }
    public Location near(Location origin,double radius) {
        return near(origin,radius,1,1.6f);
    }
    public Location near(Location origin,double radius,float width,float height) {
        for(int i=0;i<16;i++) {double angle=i*Math.PI/8;Location p=origin.clone().add(Math.cos(angle)*radius,0,Math.sin(angle)*radius);Location safe=surface(p,width,height,3);if(safe!=null)return safe;}
        return null;
    }
    public Location surface(Location candidate,float width,float height,int vertical) {
        if(!loaded(candidate)) return null;World world=candidate.getWorld();int center=candidate.getBlockY();
        for(int offset=0;offset<=vertical*2;offset++) {
            int y=center+(offset%2==0?offset/2:-(offset+1)/2);if(y<=world.getMinHeight()||y+height>=world.getMaxHeight())continue;
            Location foot=new Location(world,candidate.getX(),y,candidate.getZ());if(safe(foot,width,height))return foot;
        } return null;
    }
    public static boolean hazard(Material m) {return m==Material.LAVA||m==Material.FIRE||m==Material.SOUL_FIRE||m==Material.CACTUS||m==Material.MAGMA_BLOCK||m==Material.CAMPFIRE||m==Material.SOUL_CAMPFIRE||m==Material.POWDER_SNOW||m==Material.SWEET_BERRY_BUSH;}
    public Location waterSurface(Location candidate,float width,float height,int vertical) {
        if(!loaded(candidate))return null;
        for(int offset=0;offset<=vertical*2;offset++){
            int y=candidate.getBlockY()+(offset%2==0?offset/2:-(offset+1)/2);
            Location foot=new Location(candidate.getWorld(),candidate.getX(),y,candidate.getZ());
            if(safeWater(foot,width,height))return foot;
        }return null;
    }
    public boolean safeWater(Location foot,float width,float height) {
        World world=foot.getWorld();return world!=null&&SpawnPolicy.waterVolume(foot.getX(),foot.getY(),foot.getZ(),width,height,world.getMinHeight(),world.getMaxHeight(),
            (x,y,z)->navigationProbe()&&world.isChunkLoaded(x>>4,z>>4)&&world.getBlockAt(x,y,z).getType()==Material.WATER);
    }
    private boolean safe(Location foot,float width,float height) {
        if(!clearVolume(foot,width,height))return false;
        World w=foot.getWorld();double half=width/2;boolean supported=false;
        for(int x=(int)Math.floor(foot.getX()-half);x<=Math.floor(foot.getX()+half);x++) for(int z=(int)Math.floor(foot.getZ()-half);z<=Math.floor(foot.getZ()+half);z++) {
            var floor=w.getBlockAt(x,foot.getBlockY()-1,z);
            if(hazard(floor.getType()))return false;
            if(floor.getType().isSolid()&&floor.getBoundingBox().contains(x+.5,foot.getY()-.01,z+.5))supported=true;
        }return supported;
    }
    private boolean clearVolume(Location foot,float width,float height){
        World w=foot.getWorld();if(w==null||foot.getY()<=w.getMinHeight()||foot.getY()+height>=w.getMaxHeight())return false;double half=width/2;
        for(int x=(int)Math.floor(foot.getX()-half);x<=Math.floor(foot.getX()+half);x++)for(int z=(int)Math.floor(foot.getZ()-half);z<=Math.floor(foot.getZ()+half);z++){
            if(!w.isChunkLoaded(x>>4,z>>4))return false;
            for(int y=foot.getBlockY();y<foot.getY()+height;y++){if(!navigationProbe())return false;var block=w.getBlockAt(x,y,z);if(!block.isPassable()||block.isLiquid()||hazard(block.getType()))return false;}
        }return true;
    }
    private boolean navigationProbe(){return !probingPath||navigationBudget-->0;}
    public void visibility() {
        int cap=settings().number("limits.visible-per-player"),radius=settings().number("limits.view-radius");Set<UUID> online=new HashSet<>();
        for(Player p:Bukkit.getOnlinePlayers()) {
            if(p.hasMetadata("NPC"))continue;
            online.add(p.getUniqueId());List<Actor> near=actors.values().stream().filter(a->a.location.getWorld().equals(p.getWorld())&&(a.battleViewers.contains(p.getUniqueId())||a.location.distanceSquared(p.getLocation())<=radius*radius))
                .sorted(Comparator.<Actor>comparingInt(a->a.battleViewers.contains(p.getUniqueId())?-1:p.getUniqueId().equals(a.lockedBy)||p.getUniqueId().equals(a.owner)?0:1).thenComparingDouble(a->a.location.distanceSquared(p.getLocation()))).limit(cap).toList();
            Set<UUID> wanted=new HashSet<>();near.forEach(a->{wanted.add(a.id);a.lastNear=tick;});
            for(Actor a:List.copyOf(actors.values())) {
                if(wanted.contains(a.id)) {if(a.viewers.add(p.getUniqueId())) {a.entities().forEach(e->p.showEntity(plugin,e));onSeen.accept(p,a);}}
                else if(a.viewers.remove(p.getUniqueId()))a.entities().forEach(e->p.hideEntity(plugin,e));
            }
        }
        for(Actor a:List.copyOf(actors.values())) {a.viewers.retainAll(online);if(a.wild()&&a.lockedBy==null&&tick-a.lastNear>1200)remove(a);}
    }
    private void naturalSpawn() {
        Settings settings=settings();int max=settings.number("limits.wild");
        for(Player p:Bukkit.getOnlinePlayers()) {
            if(p.hasMetadata("NPC"))continue;
            int global=(int)actors.values().stream().filter(Actor::wild).count();if(global>=max)return;
            int nearby=(int)actors.values().stream().filter(a->a.wild()&&a.location.getWorld().equals(p.getWorld())&&a.location.distanceSquared(p.getLocation())<48*48).count();
            if(!SpawnPolicy.admit(global,max,nearby,settings.number("spawn.max-near-player"),random.nextDouble(),settings.general().getDouble("spawn.chance")))continue;
            for(int attempt=0;attempt<settings.number("spawn.attempts");attempt++) {
                double angle=random.nextDouble(Math.PI*2),distance=random.nextDouble(settings.number("spawn.minimum-distance"),settings.number("spawn.near-player")+1);
                Location candidate=p.getLocation().clone().add(Math.cos(angle)*distance,0,Math.sin(angle)*distance);
                if(!loaded(candidate))continue;
                String biome=Registry.BIOME.getKey(candidate.getBlock().getBiome()).getKey();long time=candidate.getWorld().getTime();
                List<Settings.SpawnRule> rules=settings.spawns().stream().filter(r->r.worlds().contains(p.getWorld().getName())&&r.biomes().contains(biome)&&SpawnPolicy.time(time,r.from(),r.to())).toList();
                int sum=rules.stream().mapToInt(Settings.SpawnRule::weight).sum();if(sum==0)continue;
                Settings.SpawnRule selected=null;int roll=random.nextInt(sum);for(var rule:rules){roll-=rule.weight();if(roll<0){selected=rule;break;}}
                if(selected==null)continue;var model=settings.models().get(selected.species());
                Location safe=selected.habitat()==Species.Habitat.WATER?waterSurface(candidate,model.width(),model.height(),8):surface(candidate,model.width(),model.height(),5);
                if(safe==null||selected.habitat()==Species.Habitat.SHORE&&!water(safe))continue;
                double spacing=settings.number("spawn.minimum-spacing");Location finalSafe=safe;
                if(actors.values().stream().anyMatch(a->a.wild()&&a.location.getWorld().equals(finalSafe.getWorld())&&a.location.distanceSquared(finalSafe)<spacing*spacing))continue;
                if(spawn(Creature.create(settings.species().get(selected.species()),random.nextInt(selected.min(),selected.max()+1),-1,settings.moves()),null,safe)!=null)break;
            }
        }
    }
    private boolean water(Location l) {for(int dx=-4;dx<=4;dx++)for(int dz=-4;dz<=4;dz++){Location p=l.clone().add(dx,0,dz);if(!loaded(p))continue;for(int y=-2;y<=0;y++)if(p.getWorld().getBlockAt(p.getBlockX(),p.getBlockY()+y,p.getBlockZ()).getType()==Material.WATER)return true;}return false;}
    @EventHandler(ignoreCancelled=true) public void unload(ChunkUnloadEvent event) {for(Actor a:List.copyOf(actors.values()))if(a.location.getWorld().equals(event.getWorld())&&(a.location.getBlockX()>>4)==event.getChunk().getX()&&(a.location.getBlockZ()>>4)==event.getChunk().getZ()){
        remove(a);
    }}
    @EventHandler public void load(ChunkLoadEvent event) {for(Entity e:event.getChunk().getEntities())if(e.getPersistentDataContainer().has(marker)&&!interactions.containsKey(e.getUniqueId())&&actors.values().stream().noneMatch(a->a.entities().contains(e)))e.remove();}
}
