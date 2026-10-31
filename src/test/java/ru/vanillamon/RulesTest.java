package ru.vanillamon;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.bukkit.configuration.file.YamlConfiguration;
import ru.vanillamon.domain.*;
import java.io.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RulesTest {
    private static Map<Element,Map<Element,Double>> types;
    static Creature creature(int level){return new Creature(UUID.randomUUID(),"pikachu",level,0,20+level*3,0,Map.of("tackle",15));}
    static Species species(Element t){return new Species("test",t,20,.3,"",List.of());}
    @BeforeAll static void load(){
        var y=YamlConfiguration.loadConfiguration(new InputStreamReader(Objects.requireNonNull(RulesTest.class.getResourceAsStream("/types.yml"))));
        types=new EnumMap<>(Element.class);for(Element a:Element.values()){var row=new EnumMap<Element,Double>(Element.class);for(Element b:Element.values())row.put(b,y.getDouble(a+"."+b));types.put(a,row);}
    }
    @ParameterizedTest @CsvSource({"FIRE,GRASS,2","FIRE,WATER,0.5","WATER,FIRE,2","GRASS,WATER,2","ELECTRIC,WATER,2","ELECTRIC,GRASS,0.5","NORMAL,FIRE,1","WATER,WATER,0.5"})
    void configuredEffectiveness(Element from,Element to,double expected){assertEquals(expected,types.get(from).get(to));}
    @Test void everyTypePairIsDefined(){for(var a:Element.values())for(var b:Element.values())assertTrue(types.get(a).get(b)>=0);assertEquals(225,types.values().stream().mapToInt(Map::size).sum());}
    @Test void exactDamageFormula(){assertEquals(4,Rules.damage(new Move("ember",Element.FIRE,6,15),creature(5),creature(5),types,species(Element.GRASS)));}
    @Test void damageFloorsAndNeverFallsBelowOne(){assertEquals(1,Rules.damage(new Move("x",Element.WATER,6,15),creature(5),creature(5),types,species(Element.WATER)));assertEquals(1,Rules.damage(new Move("x",Element.FIRE,1,15),creature(1),creature(100),types,species(Element.WATER)));}
    @Test void chanceUsesCompleteTenPercentSteps(){Creature c=creature(10);Species p=new Species("pikachu",Element.ELECTRIC,30,.25,"",List.of());assertEquals(.25,Rules.catchChance(p,c,.03,.95),1e-9);c.hp=46;assertEquals(.25,Rules.catchChance(p,c,.03,.95),1e-9);c.hp=45;assertEquals(.28,Rules.catchChance(p,c,.03,.95),1e-9);c.hp=1;assertEquals(.52,Rules.catchChance(p,c,.03,.95),1e-9);}
    @Test void experienceCarriesAcrossMultipleLevels(){Creature c=creature(1);assertEquals(3,Rules.experience(c,65,10));assertEquals(4,c.level);assertEquals(5,c.xp);assertEquals(32,c.hp);}
    @Test void capAt100(){Creature c=creature(99);assertEquals(1,Rules.experience(c,100000,10));assertEquals(100,c.level);assertEquals(0,c.xp);}
    @Test void speedTiesUseBothRandomOutcomes(){Random r=new Random(21);Set<Boolean> results=new HashSet<>();for(int i=0;i<50;i++)results.add(Rules.first(10,10,r));assertEquals(Set.of(true,false),results);assertTrue(Rules.first(11,10,r));}
}
