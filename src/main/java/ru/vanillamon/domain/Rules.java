package ru.vanillamon.domain;
import java.util.Map;
import java.util.random.RandomGenerator;

public final class Rules {
    private Rules() {}
    public static int damage(Move move, Creature attacker, Creature target, Map<Element,Map<Element,Double>> types, Species targetSpecies) {
        double multiplier=1;for(Element type:targetSpecies.types())multiplier*=types.get(move.type()).get(type);
        if(multiplier==0)return 0;
        int offence=move.type().special()?attacker.special():attacker.attack();
        int defence=move.type().special()?target.special():target.defence();
        double base=((2*attacker.level/5+2)*move.power()*offence/Math.max(1,defence))/50+2;
        return Math.max(1,(int)Math.floor(base*(attacker.sameType(move.type())?1.5:1)*multiplier));
    }
    public static double catchChance(Species s, Creature c, double bonus, double cap) {
        int steps=((c.maxHp()-c.hp)*10)/c.maxHp();
        return Math.min(cap,s.catchRate()+steps*bonus);
    }
    public static int threshold(int level, int factor) { return factor*level; }
    public static int experience(Creature winner, int amount, int factor) {
        int levels=0; winner.xp+=amount;
        while(winner.level<100&&winner.xp>=threshold(winner.level,factor)) {
            winner.xp-=threshold(winner.level,factor);int oldMax=winner.maxHp();winner.level++;winner.hp=winner.hp<=0?0:Math.min(winner.maxHp(),winner.hp+winner.maxHp()-oldMax);levels++;
        }
        if(winner.level==100) winner.xp=0;
        return levels;
    }
    public static boolean first(int a,int b,RandomGenerator random) { return a==b?random.nextBoolean():a>b; }
    public static int initiative(Creature creature,Move move) {return creature.speed()+(move==null?0:move.speedBonus());}
}
