package ru.vanillamon.domain;

import org.joml.Vector3f;
import ru.vanillamon.domain.Animation.*;
import java.util.*;

/** Pokédex metres become world blocks. Length is measured along a coiled body's centreline. */
public record BodySize(float metres, Axis axis, float referenceLength, float hover) {
    public BodySize(float metres,Axis axis,float referenceLength){this(metres,axis,referenceLength,0);}
    public enum Axis { HEIGHT, LENGTH }
    public record Fit(Model model,float scale) {}
    public Fit fit(Model raw) {
        Vector3f min=new Vector3f(Float.POSITIVE_INFINITY),max=new Vector3f(Float.NEGATIVE_INFINITY);
        for(Part p:raw.parts()) {
            var matrix=Animation.matrix(p,Map.of(),0,1);
            for(int x=0;x<=1;x++)for(int y=0;y<=1;y++)for(int z=0;z<=1;z++){
                Vector3f v=matrix.transformPosition(new Vector3f(x,y,z));min.min(v);max.max(v);
            }
        }
        float reference=axis==Axis.HEIGHT?max.y-min.y:referenceLength>0?referenceLength:max.z-min.z;
        if(!Float.isFinite(reference)||reference<.01f)throw new IllegalArgumentException("Invalid size reference");
        float factor=metres/reference;
        if(!Float.isFinite(factor)||factor<.02f||factor>20)throw new IllegalArgumentException("Invalid model size factor");
        List<Part> fitted=new ArrayList<>();float radius=0;
        for(Part p:raw.parts()) {
            fitted.add(new Part(p.material(),new Vector3f(p.position()).sub(0,min.y,0).mul(factor).add(0,hover,0),new Vector3f(p.scale()).mul(factor),p.rotation(),p.group(),new Vector3f(p.pivot()).sub(0,min.y,0).mul(factor).add(0,hover,0)));
            var matrix=Animation.matrix(p,Map.of(),0,1);
            for(int x=0;x<=1;x++)for(int y=0;y<=1;y++)for(int z=0;z<=1;z++){
                Vector3f v=matrix.transformPosition(new Vector3f(x,y,z));radius=Math.max(radius,(float)Math.hypot(v.x,v.z)*factor);
            }
        }
        // A radial footprint remains safe when the model turns; do not clip tails through walls.
        return new Fit(new Model((max.y-min.y)*factor+hover,Math.max(.15f,radius*2+.06f),List.copyOf(fitted)),factor);
    }
    public static Clip scale(Clip clip,float factor) {
        Map<String,Track> tracks=new HashMap<>();clip.tracks().forEach((group,t)->tracks.put(group,new Track(new Vector3f(t.move()).mul(factor),t.rotate(),t.phase())));
        return new Clip(clip.ticks(),clip.loop(),clip.scaleEnd(),Map.copyOf(tracks));
    }
}
