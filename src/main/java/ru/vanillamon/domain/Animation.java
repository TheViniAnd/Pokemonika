package ru.vanillamon.domain;
import java.util.*;
import org.joml.Vector3f;
import org.joml.Matrix4f;

public final class Animation {
    public enum State { IDLE, WALK, ATTACK, HURT, CAPTURE, FAINT }
    public record Track(Vector3f move, Vector3f rotate, float phase) {}
    public record Clip(int ticks, boolean loop, float scaleEnd, Map<String,Track> tracks) {}
    public record Part(String material, Vector3f position, Vector3f scale, Vector3f rotation, String group, Vector3f pivot) {}
    public record Model(float height, float width, List<Part> parts) {}
    public static final class Controller {
        private State state=State.IDLE;
        private long start;
        private Map<String,Pose> previous=Map.of();
        private float previousScale=1;
        private final Map<State,Clip> clips;
        private Clip attack;
        public Controller(Map<State,Clip> clips) { this.clips=clips; }
        public State state() { return state; }
        public void set(State next,long tick) {
            if(next==state) return;
            previous=poses(tick); previousScale=scale(tick); state=next; start=tick;attack=null;
        }
        public void playAttack(Clip clip,long tick) {
            previous=poses(tick);previousScale=scale(tick);state=State.ATTACK;attack=clip;start=tick;
        }
        private Clip clip() {return state==State.ATTACK&&attack!=null?attack:clips.get(state);}
        public boolean finished(long tick) { return !clip().loop()&&tick-start>=clip().ticks(); }
        public float scale(long tick) {
            Clip c=clip(); float progress=Math.min(1f,Math.max(0,(tick-start)/(float)c.ticks()));
            float target=1+(c.scaleEnd()-1)*progress;
            return previousScale+(target-previousScale)*Math.min(1,(tick-start)/4f);
        }
        public Map<String,Pose> poses(long tick) {
            Clip clip=clip(); float t=Math.max(0,tick-start)/(float)clip.ticks();
            float blend=Math.min(1,Math.max(0,(tick-start)/4f)); Map<String,Pose> result=new HashMap<>();
            Set<String> groups=new HashSet<>(clip.tracks().keySet()); groups.addAll(previous.keySet());
            for(String g:groups) {
                Track track=clip.tracks().get(g); Pose target=Pose.ZERO;
                if(track!=null) {
                    float wave=clip.loop()?(float)Math.sin(t*Math.PI*2+track.phase()): (float)Math.sin(Math.min(t,1)*Math.PI);
                    if(state==State.FAINT) wave=Math.min(t,1);
                    target=new Pose(new Vector3f(track.move()).mul(wave),new Vector3f(track.rotate()).mul(wave));
                }
                Pose old=previous.getOrDefault(g,Pose.ZERO);
                result.put(g,new Pose(new Vector3f(old.move).lerp(target.move,blend),new Vector3f(old.rotate).lerp(target.rotate,blend)));
            }
            return result;
        }
    }
    public record Pose(Vector3f move,Vector3f rotate) { public static final Pose ZERO=new Pose(new Vector3f(),new Vector3f()); }
    /** Every frame starts from immutable model data, avoiding cumulative rotation and drift. */
    public static Matrix4f matrix(Part part, Map<String,Pose> poses, float yaw, float scale) {
        Pose root=poses.getOrDefault("root",Pose.ZERO), group=poses.getOrDefault(part.group(),Pose.ZERO);
        Matrix4f m=new Matrix4f().rotateY(yaw).scale(scale).translate(root.move).rotateXYZ(root.rotate.x,root.rotate.y,root.rotate.z);
        if(!part.group().equals("root")) m.translate(group.move).translate(part.pivot()).rotateXYZ(group.rotate.x,group.rotate.y,group.rotate.z).translate(new Vector3f(part.pivot()).negate());
        return m.translate(part.position()).rotateXYZ(part.rotation().x,part.rotation().y,part.rotation().z).scale(part.scale());
    }
}
