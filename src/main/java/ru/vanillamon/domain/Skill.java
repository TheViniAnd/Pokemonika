package ru.vanillamon.domain;

import org.joml.Vector3f;
import java.util.*;

/** Configured move animation and deterministic, bounded geometry shared with the preview. */
public record Skill(Animation.Clip clip,int hitTick,Effect effect) {
    public enum Style { IMPACT, DASH, HEADBUTT, SLAM, EMBER, FLAME_STREAM, BUBBLES, WATER_JET, VINE, LEAVES, LIGHTNING, THUNDER_RING,
        ORBIT, PSYCHIC_WAVE, SHADOW_CLAW, POISON_SPIRAL, ROCK_SHARDS, EARTH_CRACK, ICE_SHARDS, AIR_CUT, DRAGON_BEAM, STAR_BURST, SONIC_WAVE, STING }
    public record Effect(Style style,String particle,int color,float size,String launchSound,String impactSound,float volume,float pitch,
                         int variant,float spread,float twist,String accent,List<SkillScene.Layer> scene) {
        public Effect(Style style,String particle,int color,float size,String launchSound,String impactSound,float volume,float pitch) {
            this(style,particle,color,size,launchSound,impactSound,volume,pitch,0,1,0,"CRIT",List.of());
        }
    }
    public static List<SkillScene.Sample> particles(Effect effect,Vector3f from,Vector3f to,int age,int hitTick,int duration){
        if(!effect.scene().isEmpty())return SkillScene.sample(effect.scene(),from,to,age,hitTick,duration);
        return frame(effect,from,to,age,hitTick,duration).stream().map(p->new SkillScene.Sample(p,effect.color(),effect.size())).toList();
    }
    public static List<Vector3f> frame(Effect effect,Vector3f from,Vector3f to,int age,int hitTick,int duration) {
        if(!effect.scene().isEmpty())return SkillScene.sample(effect.scene(),from,to,age,hitTick,duration).stream().map(SkillScene.Sample::point).toList();
        if(age<0||age>duration+8)return List.of();
        List<Vector3f> points=new ArrayList<>();
        float windup=Math.max(2,hitTick/3f),p=Math.clamp((age-windup)/Math.max(1,hitTick-windup),0,1);
        float after=Math.clamp((age-hitTick)/8f,0,1);
        Vector3f forward=new Vector3f(to).sub(from);if(forward.lengthSquared()<.00001f)forward.set(0,0,1);else forward.normalize();
        Vector3f side=new Vector3f(forward.z,0,-forward.x);if(side.lengthSquared()<.00001f)side.set(1,0,0);else side.normalize();
        if(age<windup) {
            if(effect.style().ordinal()>=Style.EMBER.ordinal())ring(points,from,.12f+age*.012f,age*.22f,8,false);
            return points;
        }
        switch(effect.style()) {
            case IMPACT->{if(age>=hitTick-2)star(points,to,.1f+after*.5f,10);}
            case DASH->{for(int i=0;i<16;i++){float t=Math.max(0,p-i*.035f);Vector3f point=new Vector3f(from).lerp(to,t).add(new Vector3f(side).mul(i%2==0?.1f:-.1f));points.add(point);}}
            case HEADBUTT->{if(age<hitTick)ring(points,from,.12f+p*.15f,age*.3f,10,false);else star(points,to,.25f+after*.4f,14);}
            case SLAM->{Vector3f ground=new Vector3f(to).add(0,-.5f,0);ring(points,ground,age<hitTick?.15f:.25f+after*.8f,0,24,true);}
            case EMBER->{Vector3f center=new Vector3f(from).lerp(to,p);ring(points,center,.12f+after*.2f,age*.4f,12,false);points.add(center);}
            case FLAME_STREAM->{for(int i=0;i<24;i++){float t=p*i/23f;double a=i*2.4+age*.35;Vector3f point=new Vector3f(from).lerp(to,t);point.add(new Vector3f(side).mul((float)Math.cos(a)*(.07f+.17f*t)));point.y+=(float)Math.sin(a)*(.07f+.17f*t);points.add(point);}}
            case BUBBLES->{for(int b=0;b<3;b++){float t=Math.max(0,p-b*.14f);Vector3f center=new Vector3f(from).lerp(to,t);center.y+=(float)Math.sin(t*Math.PI)*.22f;ring(points,center,.10f+b*.035f,age*.2f,8,false);}}
            case WATER_JET->{for(int i=0;i<28;i++){float t=p*i/27f;Vector3f point=new Vector3f(from).lerp(to,t);point.add(new Vector3f(side).mul((float)Math.sin(i*1.9+age)*.07f));point.y+=(float)Math.cos(i*1.9+age)*.07f;points.add(point);}}
            case VINE->{for(int branch:new int[]{-1,1})for(int i=0;i<15;i++){float t=p*i/14f;Vector3f point=new Vector3f(from).lerp(to,t);point.add(new Vector3f(side).mul(branch*.18f*(float)Math.sin(t*Math.PI*2)));point.y+=.1f*(float)Math.sin(t*Math.PI);points.add(point);}}
            case LEAVES->{for(int leaf=0;leaf<4;leaf++){float t=Math.max(0,p-leaf*.09f);Vector3f center=new Vector3f(from).lerp(to,t);center.add(new Vector3f(side).mul((leaf-1.5f)*.12f*(float)Math.sin(t*Math.PI)));center.y+=(float)Math.sin(t*Math.PI)*.35f;for(int i=0;i<5;i++){Vector3f point=new Vector3f(center).add(new Vector3f(side).mul((i-2)*.035f));point.y+=Math.abs(i-2)*.022f;points.add(point);}}}
            case LIGHTNING->{for(int i=0;i<22;i++){float t=p*i/21f;Vector3f point=new Vector3f(from).lerp(to,t);float zig=i%2==0?.14f:-.14f;point.add(new Vector3f(side).mul(zig));point.y+=(float)Math.sin(i*2+age)*.08f;points.add(point);}}
            case THUNDER_RING->{Vector3f center=age<hitTick?new Vector3f(from).lerp(to,p):new Vector3f(to);ring(points,center,.15f+p*.3f+after*.45f,age*.2f,20,true);for(int i=0;i<8;i++){Vector3f point=new Vector3f(center);point.y+=i*.09f;point.add(new Vector3f(side).mul(i%2==0?.1f:-.1f));points.add(point);}}
            case ORBIT,POISON_SPIRAL,DRAGON_BEAM->{
                int strands=effect.style()==Style.DRAGON_BEAM?3:2;
                for(int strand=0;strand<strands;strand++)for(int i=0;i<10;i++){
                    float t=p*i/9f;double angle=i*.8+age*.25+strand*Math.PI*2/strands;
                    float r=(effect.style()==Style.POISON_SPIRAL?.23f:.15f)*(float)Math.sin(t*Math.PI);
                    Vector3f point=new Vector3f(from).lerp(to,t).add(new Vector3f(side).mul((float)Math.cos(angle)*r));point.y+=(float)Math.sin(angle)*r;points.add(point);
                }
            }
            case PSYCHIC_WAVE,SONIC_WAVE->{for(int wave=0;wave<3;wave++){float t=Math.max(0,p-wave*.18f);Vector3f center=new Vector3f(from).lerp(to,t);ring(points,center,.1f+t*(effect.style()==Style.SONIC_WAVE?.5f:.3f),age*.12f,10,false);}}
            case SHADOW_CLAW,AIR_CUT->{for(int claw=-1;claw<=1;claw++)for(int i=0;i<9;i++){float t=i/8f;Vector3f point=new Vector3f(from).lerp(to,p).add(new Vector3f(side).mul((t-.5f)*.6f+claw*.16f));point.y+=(float)Math.sin(t*Math.PI)*.4f-.2f;points.add(point);}}
            case ROCK_SHARDS,ICE_SHARDS,STING->{for(int shard=0;shard<5;shard++){float t=Math.max(0,p-shard*.06f);Vector3f center=new Vector3f(from).lerp(to,t).add(new Vector3f(side).mul((shard-2)*.14f*(float)Math.sin(t*Math.PI)));center.y+=(float)Math.sin(t*Math.PI)*(effect.style()==Style.ROCK_SHARDS?.5f:.1f);for(int i=0;i<5;i++)points.add(new Vector3f(center).add(new Vector3f(forward).mul((i-2)*.05f)));}}
            case EARTH_CRACK->{for(int branch=-1;branch<=1;branch++)for(int i=0;i<10;i++){float t=p*i/9f;Vector3f point=new Vector3f(from).lerp(to,t).add(new Vector3f(side).mul(branch*.18f+(i%2==0?.08f:-.08f)));point.y-=.45f;points.add(point);}}
            case STAR_BURST->{Vector3f center=new Vector3f(from).lerp(to,p);star(points,center,.18f+after*.5f,20);}
        }
        // Individual trajectories vary in cross-section, orbit angle, curvature and rhythm.
        float length=new Vector3f(to).sub(from).length();
        for(int i=0;i<points.size();i++){
            Vector3f point=points.get(i);float along=new Vector3f(point).sub(from).dot(forward);
            Vector3f axis=new Vector3f(from).add(new Vector3f(forward).mul(along));
            Vector3f radial=new Vector3f(point).sub(axis).mul(effect.spread());
            radial.rotateAxis(effect.variant()*.42f+effect.twist()*age*.05f,forward.x,forward.y,forward.z);
            point.set(axis).add(radial);
            float progress=length<.001f?0:Math.clamp(along/length,0,1);
            float curve=(float)Math.sin(progress*Math.PI)*(.05f+effect.variant()%5*.03f)*(float)Math.sin(age*.2f+effect.variant()*.71f);
            point.add(new Vector3f(side).mul(curve));
        }
        return List.copyOf(points);
    }
    private static void ring(List<Vector3f> out,Vector3f center,float radius,float phase,int count,boolean horizontal) {
        for(int i=0;i<count;i++){double a=phase+i*Math.PI*2/count;float x=(float)Math.cos(a)*radius,y=(float)Math.sin(a)*radius;out.add(new Vector3f(center).add(x,horizontal?0:y,horizontal?y:0));}
    }
    private static void star(List<Vector3f> out,Vector3f center,float radius,int count) {
        for(int i=0;i<count;i++){double a=i*Math.PI*2/count;float r=i%2==0?radius:radius*.45f;out.add(new Vector3f(center).add((float)Math.cos(a)*r,(float)Math.sin(a)*r,0));}
    }
}
