package ru.vanillamon.world;
import ru.vanillamon.domain.*;
import ru.vanillamon.domain.Creature;
import ru.vanillamon.domain.Animation.*;
import org.bukkit.Location;
import org.bukkit.entity.*;
import org.joml.Matrix4f;
import java.util.*;

public final class Actor {
    public final UUID id=UUID.randomUUID();
    public Creature creature;
    public final UUID owner;
    public final Location home;
    public Location location, destination;
    public float yaw;
    public final Model model;
    public final Controller animation;
    public final List<BlockDisplay> blocks=new ArrayList<>();
    public final List<Matrix4f> matrices=new ArrayList<>();
    public final Set<UUID> viewers=new HashSet<>();
    public final Set<UUID> battleViewers=new HashSet<>();
    public Interaction hitbox;
    public TextDisplay label;
    public UUID lockedBy;
    public boolean capturing, removed;
    public long nextWander, lastNear, born;
    public String lastLabel="";
    public Actor(Creature c,UUID owner,Location loc,Model model,Map<State,Clip> clips) {
        this.creature=c;this.owner=owner;this.home=loc.clone();this.location=loc.clone();this.model=model;this.animation=new Controller(clips);
    }
    public boolean wild() { return owner==null; }
    public synchronized boolean lock(UUID player) { if(lockedBy!=null||removed) return false; lockedBy=player; destination=null; return true; }
    public List<Entity> entities() { List<Entity> all=new ArrayList<>(blocks); if(hitbox!=null) all.add(hitbox); if(label!=null) all.add(label); return all; }
}
