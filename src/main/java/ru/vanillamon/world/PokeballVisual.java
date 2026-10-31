package ru.vanillamon.world;

import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.joml.Matrix4f;
import java.util.*;
import ru.vanillamon.domain.BallType;

/** The real server projectile handles collisions; coloured vanilla pieces identify the selected ball. */
public final class PokeballVisual {
    public record Piece(Material material,float x,float y,float z,float sx,float sy,float sz) {}
    public static final List<Piece> PIECES=List.of(
        new Piece(Material.RED_CONCRETE,-.12f,.015f,-.12f,.24f,.095f,.24f),
        new Piece(Material.WHITE_CONCRETE,-.12f,-.11f,-.12f,.24f,.095f,.24f),
        new Piece(Material.BLACK_CONCRETE,-.123f,-.015f,-.123f,.246f,.03f,.246f),
        new Piece(Material.RED_CONCRETE,-.09f,.11f,-.09f,.18f,.035f,.18f),
        new Piece(Material.WHITE_CONCRETE,-.09f,-.145f,-.09f,.18f,.035f,.18f),
        new Piece(Material.BLACK_CONCRETE,-.05f,-.05f,.124f,.1f,.1f,.015f),
        new Piece(Material.WHITE_CONCRETE,-.025f,-.025f,.14f,.05f,.05f,.012f));
    private final List<BlockDisplay> blocks=new ArrayList<>();
    private final List<Piece> pieces;
    public static List<Piece> pieces(BallType type){
        if(type==null)return PIECES;
        List<Piece> result=new ArrayList<>();
        for(int i=0;i<PIECES.size();i++){Piece p=PIECES.get(i);Material material=i==0||i==3?Material.valueOf(type.top()):i==2?Material.valueOf(type.band()):p.material();result.add(new Piece(material,p.x(),p.y(),p.z(),p.sx(),p.sy(),p.sz()));}
        if(!type.trim().equals(type.top()))for(float x:new float[]{-.09f,.06f})result.add(new Piece(Material.valueOf(type.trim()),x,.08f,-.11f,.03f,.045f,.22f));
        return List.copyOf(result);
    }
    public PokeballVisual(JavaPlugin plugin,Location location){this(plugin,location,null);}
    public PokeballVisual(JavaPlugin plugin,Location location,BallType type){
        pieces=pieces(type);
        try{
            for(Piece piece:pieces)blocks.add(location.getWorld().spawn(location,BlockDisplay.class,e->{
                e.setPersistent(false);e.setInvulnerable(true);e.setGravity(false);e.setSilent(true);
                e.setBlock(piece.material().createBlockData());e.setInterpolationDuration(1);e.setTeleportDuration(1);e.setViewRange(.75f);e.setDisplayWidth(1);e.setDisplayHeight(1);
                e.getPersistentDataContainer().set(new NamespacedKey("vanillamon","pokeball_visual"),PersistentDataType.BYTE,(byte)1);
            }));
            update(location,0,false);
        }catch(RuntimeException error){remove();throw error;}
    }
    public List<BlockDisplay> entities(){return List.copyOf(blocks);}
    public void update(Location location,long age,boolean shaking){
        float spin=shaking?(float)Math.sin(age*.6)*.22f:age*.28f;
        for(int i=0;i<blocks.size();i++){
            BlockDisplay block=blocks.get(i);if(!block.isValid())continue;Piece p=pieces.get(i);
            block.teleport(location);block.setInterpolationDelay(0);
            block.setTransformationMatrix(new Matrix4f().rotateZ(spin).translate(p.x(),p.y(),p.z()).scale(p.sx(),p.sy(),p.sz()));
        }
    }
    public void remove(){blocks.forEach(Entity::remove);blocks.clear();}
}
