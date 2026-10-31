package ru.vanillamon.domain;
import java.util.*;

/** Mutable only on the server thread; copies cross the database boundary. */
public final class Creature {
    public final UUID id;
    public final String species;
    public int level, xp, hp, slot;
    public final LinkedHashMap<String,Integer> pp;
    private Species definition;
    public Creature(UUID id, String species, int level, int xp, int hp, int slot, Map<String,Integer> pp) {
        this.id=id; this.species=species; this.level=level; this.xp=xp; this.hp=hp; this.slot=slot;
        this.pp=new LinkedHashMap<>(pp);
    }
    public Creature copy() { Creature c=new Creature(id,species,level,xp,hp,slot,pp);c.definition=definition;return c; }
    public int maxHp() { return definition==null||definition.stats()==null?20+3*level:definition.stats().health(level); }
    public int attack() { return definition==null||definition.stats()==null?5+level:definition.stats().value(definition.stats().attack(),level); }
    public int defence() { return definition==null||definition.stats()==null?5+level:definition.stats().value(definition.stats().defence(),level); }
    public int speed() { return definition==null||definition.stats()==null?5+level:definition.stats().value(definition.stats().speed(),level); }
    public int special() { return definition==null||definition.stats()==null?5+level:definition.stats().value(definition.stats().special(),level); }
    public boolean sameType(Element type) { return definition!=null&&definition.types().contains(type); }
    /** Rebalance a loaded or reconfigured creature without healing it. Fainted creatures stay fainted. */
    public void bind(Species species,boolean proportional) {
        int previous=maxHp();definition=Objects.requireNonNull(species);
        hp=proportional?(hp<=0?0:Math.max(1,(int)Math.floor((double)hp*maxHp()/previous))):Math.clamp(hp,0,maxHp());
    }
    public static Creature create(Species s, int level, int slot, Map<String,Move> moves) {
        LinkedHashMap<String,Integer> pp=new LinkedHashMap<>();
        s.moves().forEach(m->pp.put(m,moves.get(m).uses()));
        Creature c=new Creature(UUID.randomUUID(),s.id(),level,0,0,slot,pp);c.definition=s;c.hp=c.maxHp();return c;
    }
    public void heal(Map<String,Move> moves) { hp=maxHp(); pp.replaceAll((m,p)->moves.get(m).uses()); }
}
