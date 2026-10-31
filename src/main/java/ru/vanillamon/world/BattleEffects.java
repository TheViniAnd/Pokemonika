package ru.vanillamon.world;

import ru.vanillamon.domain.Skill;
import ru.vanillamon.config.Settings;
import org.bukkit.*;
import org.joml.Vector3f;
import java.util.*;
import java.util.function.Supplier;

/** Updated by the existing world loop. No entities, fire, damage, or per-effect scheduler tasks. */
public final class BattleEffects {
    private record Active(UUID battle,Actor from,Actor to,Skill skill,long start) {}
    private final List<Active> active=new ArrayList<>();
    private final Supplier<Settings> settings;
    private long emitted;
    private int remaining;
    public BattleEffects(Supplier<Settings> settings){this.settings=settings;}
    public int activeCount(){return active.size();}
    public long emittedPoints(){return emitted;}
    public void start(UUID battle,Actor from,Actor to,Skill skill,long tick){
        Settings s=settings.get();if(!s.effectLimits().enabled())return;
        if(active.size()>=s.effectLimits().maxActive())active.removeFirst();
        active.add(new Active(battle,from,to,skill,tick));
        sound(from.location,skill.effect().launchSound(),skill.effect());
    }
    public void stop(UUID battle){active.removeIf(a->a.battle.equals(battle));}

    public void remove(Actor actor){active.removeIf(a->a.from==actor||a.to==actor);}
    public void clear(){active.clear();}
    public void update(long tick){
        int budget=settings.get().effectLimits().enabled()?settings.get().effectLimits().particlesPerTick():0;
        Iterator<Active> iterator=active.iterator();
        while(iterator.hasNext()){
            Active a=iterator.next();int age=(int)(tick-a.start);
            if(a.from.removed||a.to.removed||a.from.location.getWorld()!=a.to.location.getWorld()||age>a.skill.clip().ticks()+8){iterator.remove();continue;}
            if(age==a.skill.hitTick())sound(a.to.location,a.skill.effect().impactSound(),a.skill.effect());
            if(((age&1)!=0&&age!=a.skill.hitTick())||budget<=0)continue;
            World world=a.from.location.getWorld();Vector3f from=center(a.from,.72),to=center(a.to,.55);
            Particle particle=Particle.valueOf(a.skill.effect().particle());
            Map<String,Particle.DustOptions> inks=new HashMap<>();
            for(var sample:Skill.particles(a.skill.effect(),from,to,age,a.skill.hitTick(),a.skill.clip().ticks())){
                if(budget--<=0)break;
                Vector3f point=sample.point();
                if(!world.isChunkLoaded(((int)Math.floor(point.x))>>4,((int)Math.floor(point.z))>>4))continue;
                Particle.DustOptions dust=particle==Particle.DUST?inks.computeIfAbsent(sample.color()+":"+sample.size(),k->new Particle.DustOptions(Color.fromRGB(sample.color()),sample.size())):null;
                world.spawnParticle(particle,new Location(world,point.x,point.y,point.z),1,0,0,0,0,dust);emitted++;
            }
            if(age==a.skill.hitTick()&&budget>0){
                Particle accent=Particle.valueOf(a.skill.effect().accent());int count=Math.min(4,budget);budget-=count;
                world.spawnParticle(accent,new Location(world,to.x,to.y,to.z),count,.08,.08,.08,0);emitted+=count;
            }
        }
        remaining=Math.max(0,budget);
    }
    public <T> void emit(Particle particle,Location at,int requested,double dx,double dy,double dz,T data){
        if(!settings.get().effectLimits().enabled()||at.getWorld()==null||!at.getWorld().isChunkLoaded(at.getBlockX()>>4,at.getBlockZ()>>4))return;
        int count=Math.max(0,Math.min(requested,remaining));if(count==0)return;remaining-=count;emitted+=count;at.getWorld().spawnParticle(particle,at,count,dx,dy,dz,0,data);
    }

    private static Vector3f center(Actor a,double fraction){return new Vector3f((float)a.location.getX(),(float)(a.location.getY()+a.model.height()*fraction),(float)a.location.getZ());}
    private static void sound(Location location,String sound,Skill.Effect effect){if(!sound.isBlank())location.getWorld().playSound(location,sound,effect.volume(),effect.pitch());}
}
