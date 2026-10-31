package ru.vanillamon.domain;

/** Shared admission rules: probability is per interval, never multiplied by the number of species. */
public final class SpawnPolicy {
    private SpawnPolicy() {}
    public static boolean admit(int global,int globalCap,int nearby,int localCap,double roll,double chance) {
        return global<globalCap&&nearby<localCap&&roll<chance;
    }
    public static boolean time(long time,int from,int to) {return from<=to?time>=from&&time<=to:time>=from||time<=to;}
    @FunctionalInterface public interface WaterCell {boolean water(int x,int y,int z);}
    /** Every cell intersecting the body must be loaded water, rather than a nearby shoreline. */
    public static boolean waterVolume(double x,double y,double z,float width,float height,int minHeight,int maxHeight,WaterCell cells) {
        if(!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z)||width<=0||height<=0||y<=minHeight||y+height>=maxHeight)return false;
        double half=width/2;
        for(int bx=(int)Math.floor(x-half);bx<=Math.floor(x+half);bx++)for(int bz=(int)Math.floor(z-half);bz<=Math.floor(z+half);bz++)
            for(int by=(int)Math.floor(y);by<y+height;by++)if(!cells.water(bx,by,bz))return false;
        return true;
    }
}
