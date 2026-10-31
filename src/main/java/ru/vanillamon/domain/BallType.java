package ru.vanillamon.domain;

import java.util.*;

/** Server-balanced variants, using ordinary tagged snowballs and vanilla block colours. */
public record BallType(String id,Rule rule,double multiplier,Map<String,Double> parameters,
                       String top,String band,String trim,boolean heal,List<String> recipe,int craftAmount) {
    public enum Rule { STANDARD }
    public double parameter(String key,double fallback){return parameters.getOrDefault(key,fallback);}
    public static String identify(boolean tagged,String type,Set<String> known){
        if(!tagged)return null;String id=type==null?"poke":type;return known.contains(id)?id:null;
    }
}
