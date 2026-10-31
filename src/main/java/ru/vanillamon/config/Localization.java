package ru.vanillamon.config;

import org.bukkit.configuration.file.YamlConfiguration;
import java.nio.file.Path;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

public final class Localization {
    private static final Pattern PARAM=Pattern.compile("\\{[a-zA-Z][a-zA-Z0-9_]*}");
    private static final Map<String,YamlConfiguration> BUNDLED=Map.of("en",bundled("en"),"ru",bundled("ru"));
    private Localization() {}
    private static YamlConfiguration bundled(String code){try(var in=Localization.class.getResourceAsStream("/messages_"+code+".yml")){if(in==null)throw new IllegalStateException("Missing bundled locale "+code);var y=new YamlConfiguration();y.loadFromString(new String(in.readAllBytes(),StandardCharsets.UTF_8));return y;}catch(Exception e){throw new IllegalStateException(e);}}
    private static Set<String> params(String text){Set<String> result=new HashSet<>();var m=PARAM.matcher(text);while(m.find())result.add(m.group());return result;}
    public static YamlConfiguration load(Path root,String code) throws Exception {
        if(!Set.of("en","ru").contains(code))throw new IllegalArgumentException("language must be en or ru");Map<String,YamlConfiguration> packs=new HashMap<>();
        for(String locale:List.of("en","ru")){var y=new YamlConfiguration();y.load(root.resolve("messages_"+locale+".yml").toFile());for(String key:BUNDLED.get("en").getKeys(true))if(BUNDLED.get("en").isString(key)){if(!y.isString(key)||!params(y.getString(key)).equals(params(BUNDLED.get("en").getString(key))))throw new IllegalArgumentException("Missing locale key or mismatched placeholders: "+locale+":"+key);}packs.put(locale,y);}
        return packs.get(code);
    }
    public static String name(YamlConfiguration messages,String key,String configured){if(configured==null)return null;for(var defaults:BUNDLED.values())if(configured.equals(defaults.getString(key)))return messages.getString(key,configured);return configured;}

}
