package ru.vanillamon.domain;

/** Explicit server rules: rarity × continuous HP bonus × absolute-level factor × ball conditions. */
public final class CatchRules {
    private CatchRules() {}
    public record Policy(double referenceLevel,double levelOffset,double levelExponent,double hpBonus,double minimum,double maximum) {}
    public record Result(double base,double hpFactor,double levelFactor,double ballFactor,double chance) {}
    public static Result calculate(Species species,Creature target,BallType ball,Policy policy){
        double hp=Math.clamp(target.hp/(double)target.maxHp(),0,1),health=1+policy.hpBonus()*(1-hp);
        double level=Math.pow((policy.referenceLevel()+policy.levelOffset())/(target.level+policy.levelOffset()),policy.levelExponent());
        double modifier=ball.multiplier(),chance;
        if(target.hp<=0||species.catchRate()<=0)chance=0;
        else chance=Math.clamp(species.catchRate()*health*level*modifier,policy.minimum(),policy.maximum());
        return new Result(species.catchRate(),health,level,modifier,chance);
    }
}
