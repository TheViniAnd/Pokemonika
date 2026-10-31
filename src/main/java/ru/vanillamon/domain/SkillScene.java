package ru.vanillamon.domain;

import org.joml.Vector3f;
import java.util.*;

/** Authored multi-stage effects. Paths use (fraction along attack, metres sideways, metres upwards).
 * Glyphs use metres in the same local frame. No species IDs, random seeds or colour-based geometry. */
public final class SkillScene {
    private SkillScene() {}
    public enum Motion { TRACE, STAMP, SPIN, BURST, CONTRACT, TRAIL }
    public record Layer(float start,float end,Motion motion,List<Vector3f> path,List<Vector3f> glyph,
                        int count,float scale,float spin,int color,float size) {}
    public record Sample(Vector3f point,int color,float size) {}
    public static List<Sample> sample(List<Layer> layers,Vector3f from,Vector3f to,int age,int hit,int duration){
        if(age<0||age>duration+8)return List.of();
        float phase=age<=hit?age/(float)Math.max(1,hit):1+(age-hit)/8f;
        Vector3f forward=new Vector3f(to).sub(from);if(forward.lengthSquared()<.00001f)forward.set(0,0,1);else forward.normalize();
        Vector3f side=new Vector3f(forward.z,0,-forward.x);if(side.lengthSquared()<.00001f)side.set(1,0,0);else side.normalize();
        Vector3f up=new Vector3f(forward).cross(side).normalize();
        List<List<Sample>> visible=new ArrayList<>();
        for(Layer layer:layers){
            if(phase<layer.start()||phase>layer.end())continue;
            float p=Math.clamp((phase-layer.start())/(layer.end()-layer.start()),0,1);
            List<Sample> points=new ArrayList<>();int count=layer.count();
            for(int i=0;i<count;i++){
                float f=count==1?0:i/(float)(count-1);Vector3f local;
                if(layer.motion()==Motion.TRACE)local=path(layer.path(),p*f);
                else if(layer.motion()==Motion.TRAIL)local=path(layer.path(),Math.max(0,p-f*.32f));
                else local=path(layer.path(),p);
                Vector3f center=new Vector3f(from).lerp(to,local.x).add(new Vector3f(side).mul(local.y)).add(new Vector3f(up).mul(local.z));
                if(layer.motion()!=Motion.TRACE){
                    Vector3f glyph=path(layer.glyph(),f).mul(layer.scale());
                    if(layer.motion()==Motion.BURST)glyph.mul(.2f+p*1.3f);
                    if(layer.motion()==Motion.CONTRACT)glyph.mul(1.5f-p*1.3f);
                    float angle=(float)Math.toRadians(layer.spin())*p,c=(float)Math.cos(angle),s=(float)Math.sin(angle);
                    float sideways=glyph.y*c-glyph.z*s,vertical=glyph.y*s+glyph.z*c;
                    center.add(new Vector3f(forward).mul(glyph.x)).add(new Vector3f(side).mul(sideways)).add(new Vector3f(up).mul(vertical));
                }
                points.add(new Sample(center,layer.color(),layer.size()));
            }
            visible.add(points);
        }
        // Fairly sample simultaneous layers instead of losing the impact behind the outgoing trail.
        List<Sample> result=new ArrayList<>();int row=0;
        while(result.size()<32){boolean added=false;for(List<Sample> layer:visible)if(row<layer.size()){
            result.add(layer.get(row));added=true;if(result.size()==32)break;
        }if(!added)break;row++;}
        return List.copyOf(result);
    }
    private static Vector3f path(List<Vector3f> points,float t){
        if(points.isEmpty())return new Vector3f();if(points.size()==1)return new Vector3f(points.getFirst());
        float index=Math.clamp(t,0,1)*(points.size()-1);int a=Math.min(points.size()-2,(int)index);
        return new Vector3f(points.get(a)).lerp(points.get(a+1),index-a);
    }
}
