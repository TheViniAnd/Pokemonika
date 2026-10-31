package ru.vanillamon;
import ru.vanillamon.domain.Animation;
import ru.vanillamon.domain.Animation.*;
import ru.vanillamon.domain.Creature;
import ru.vanillamon.world.Actor;
import org.bukkit.Location;
import org.joml.Vector3f;
import org.junit.jupiter.api.*;
import org.bukkit.configuration.file.YamlConfiguration;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class AnimationTest {
    private static Map<State,Clip> clips(){Map<State,Clip> clips=new EnumMap<>(State.class);for(State s:State.values())clips.put(s,new Clip(s==State.CAPTURE?24:20,s==State.IDLE||s==State.WALK,s==State.CAPTURE?0:1,Map.of("arm",new Track(new Vector3f(),new Vector3f(.5f,0,0),0))));return clips;}
    @Test void rotationKeepsJointFixed(){
        Vector3f pivot=new Vector3f(0,.7f,0);Part part=new Part("STONE",new Vector3f(pivot),new Vector3f(1),new Vector3f(),"arm",pivot);
        var m=Animation.matrix(part,Map.of("arm",new Pose(new Vector3f(),new Vector3f(1.1f,0,.5f))),0,1);
        assertTrue(new Vector3f(pivot).equals(m.transformPosition(new Vector3f()),1e-5f));
    }
    @Test void yawRotatesEveryPartFromSameOrigin(){Part p=new Part("STONE",new Vector3f(0,0,1),new Vector3f(1),new Vector3f(),"head",new Vector3f());assertTrue(new Vector3f(1,0,0).equals(Animation.matrix(p,Map.of(),(float)Math.PI/2,1).transformPosition(new Vector3f()),1e-5f));}
    @Test void noCumulativeDriftAfterTenThousandFrames(){Part p=new Part("STONE",new Vector3f(.1f,.8f,.2f),new Vector3f(.2f),new Vector3f(),"head",new Vector3f());var first=Animation.matrix(p,Map.of(),.7f,1);for(int i=0;i<10000;i++)assertEquals(first,Animation.matrix(p,Map.of(),.7f,1));}
    @Test void captureEndsAtZeroAndTransitionRestoresScale(){Controller c=new Controller(clips());c.set(State.CAPTURE,10);assertEquals(1,c.scale(10));assertEquals(.5,c.scale(22),1e-5);assertEquals(0,c.scale(34));assertTrue(c.finished(34));c.set(State.IDLE,34);assertEquals(0,c.scale(34));assertEquals(1,c.scale(38));}
    @Test void stateChangeCancelsPriorActionAndDoesNotAccumulate(){Controller c=new Controller(clips());c.set(State.ATTACK,10);Map<String,Pose> before=c.poses(18);c.set(State.HURT,18);assertEquals(State.HURT,c.state());assertEquals(before,c.poses(18));assertFalse(c.finished(25));assertTrue(c.finished(38));}
    @Test void allModelsMeetPartBudgetAndHaveSixStates(){Set<String> silhouettes=new HashSet<>();for(String id:ru.vanillamon.config.Settings.IDS){var y=YamlConfiguration.loadConfiguration(new InputStreamReader(Objects.requireNonNull(getClass().getResourceAsStream("/models/"+id+".yml"))));assertTrue(y.getMapList("parts").size()<=24);assertTrue(y.getDouble("height")>=.7&&y.getDouble("height")<=1.6);silhouettes.add(y.getMapList("parts").stream().map(p->p.get("position").toString()+p.get("scale")).toList().toString());var a=YamlConfiguration.loadConfiguration(new InputStreamReader(Objects.requireNonNull(getClass().getResourceAsStream("/animations/"+id+".yml"))));for(State state:State.values())assertNotNull(a.getConfigurationSection(state.name().toLowerCase(Locale.ROOT)));}assertEquals(10,silhouettes.size());}
    @Test void onlyOneConcurrentTargetLeaseSucceeds() throws Exception {
        Actor actor=new Actor(RulesTest.creature(5),null,new Location(null,0,0,0),new Model(1,1,List.of()),clips());AtomicInteger successes=new AtomicInteger();
        try(var pool=Executors.newFixedThreadPool(8)){List<Callable<Void>> jobs=new ArrayList<>();for(int i=0;i<64;i++)jobs.add(()->{if(actor.lock(UUID.randomUUID()))successes.incrementAndGet();return null;});for(var f:pool.invokeAll(jobs))f.get();}assertEquals(1,successes.get());
    }
}
