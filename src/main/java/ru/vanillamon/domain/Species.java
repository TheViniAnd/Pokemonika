package ru.vanillamon.domain;
import java.util.List;
public record Species(String id, Element type, int speed, double catchRate, String icon, List<String> moves,
                      Stats stats, Element secondaryType, Habitat habitat, BodySize size, double weightKg) {
    public Species(String id,Element type,int speed,double catchRate,String icon,List<String> moves,Stats stats,Element secondaryType,Habitat habitat,BodySize size) {
        this(id,type,speed,catchRate,icon,moves,stats,secondaryType,habitat,size,1);
    }
    public enum Habitat { LAND, WATER, SHORE }
    public record Stats(int hp,int attack,int defence,int speed,int special) {
        public int health(int level) { return 2*hp*level/100+level+10; }
        public int value(int base,int level) { return 2*base*level/100+5; }
    }
    /** Compatibility constructor for legacy storage fixtures. Runtime species always have base stats. */
    public Species(String id,Element type,int speed,double catchRate,String icon,List<String> moves) {
        this(id,type,speed,catchRate,icon,moves,null,null,Habitat.LAND,null);
    }
    public Species(String id,Element type,int speed,double catchRate,String icon,List<String> moves,Stats stats,Element secondaryType,Habitat habitat) {
        this(id,type,speed,catchRate,icon,moves,stats,secondaryType,habitat,null);
    }
    public List<Element> types() { return secondaryType==null||secondaryType==type?List.of(type):List.of(type,secondaryType); }
}
