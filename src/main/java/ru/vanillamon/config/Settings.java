package ru.vanillamon.config;

import ru.vanillamon.domain.*;
import ru.vanillamon.domain.Animation.*;
import org.bukkit.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.joml.Vector3f;
import java.io.*;
import java.util.*;
import java.util.function.Predicate;

/** Fully validate a candidate before replacing the running settings. */
public record Settings(Map<String,Species> species, Map<String,Move> moves,
    Map<Element,Map<Element,Double>> types, Map<String,Model> models,
    Map<String,Map<State,Clip>> animations, List<SpawnRule> spawns,
    YamlConfiguration general, YamlConfiguration messages,
    Map<String,Map<String,Skill>> skills,EffectLimits effectLimits,Map<String,BallType> balls,CatchRules.Policy catchPolicy) {
    public static final List<String> BALL_IDS=List.of("poke");
    public record EffectLimits(boolean enabled,int maxActive,int particlesPerTick) {}
    public record SpawnRule(String species,List<String> worlds,Set<String> biomes,int min,int max,int from,int to,int weight,Species.Habitat habitat) {}
    public static final List<String> IDS=List.of("pikachu","charmander","bulbasaur","squirtle","eevee","magikarp","slowpoke","pidgey","psyduck","vulpix");
    public static Settings load(JavaPlugin plugin) throws Exception {
        File config=new File(plugin.getDataFolder(),"config.yml");
        if(config.exists()&&!"free".equals(YamlConfiguration.loadConfiguration(config).getString("edition")))throw new IllegalArgumentException("This data folder belongs to another edition. Use Premium or a separate test server.");
        List<String> files=new ArrayList<>(List.of("config.yml","species.yml","moves.yml","types.yml","spawns.yml","messages_en.yml","messages_ru.yml","effects.yml","balls.yml"));
        IDS.forEach(id->{files.add("models/"+id+".yml");files.add("animations/"+id+".yml");});
        for(String file:files)if(!new File(plugin.getDataFolder(),file).exists())plugin.saveResource(file,false);
        return load(plugin.getDataFolder());
    }
    public static Settings load(File root) throws Exception {
        Settings settings=load(root,Material::isBlock,Material::isItem,biome->Registry.BIOME.get(NamespacedKey.minecraft(biome))!=null);
        for(var byMove:settings.skills().values())for(Skill skill:byMove.values()){
            require(Registry.SOUNDS.get(NamespacedKey.fromString(skill.effect().launchSound()))!=null,"Unknown launch sound");
            require(Registry.SOUNDS.get(NamespacedKey.fromString(skill.effect().impactSound()))!=null,"Unknown impact sound");
        }
        return settings;
    }
    /** Registry checks are injected in unit tests; the production entry always uses Paper registries. */
    public static Settings load(File root,Predicate<Material> blockCheck,Predicate<Material> itemCheck,Predicate<String> biomeCheck) throws Exception {
        YamlConfiguration g=yaml(root,"config.yml"), text=Localization.load(root.toPath(),g.getString("language","en")), sy=yaml(root,"species.yml"), my=yaml(root,"moves.yml"), ty=yaml(root,"types.yml");
        range(g,"limits.wild",1,200); range(g,"limits.visible-per-player",2,12); range(g,"limits.view-radius",8,64);
        range(g,"engine.update-ticks",1,10); range(g,"engine.interpolation-ticks",0,59);
        require("free".equals(g.getString("edition")),"This is not a Free configuration");
        range(g,"spawn.interval-ticks",20,72000); range(g,"spawn.attempts",1,20); range(g,"spawn.near-player",8,28);
        range(g,"spawn.max-near-player",1,12);range(g,"spawn.minimum-distance",8,28);range(g,"spawn.minimum-spacing",2,32);range(g,"spawn.lifetime-ticks",1200,72000);
        require(g.getInt("spawn.minimum-distance")<=g.getInt("spawn.near-player"),"Spawn distance order");require(g.contains("spawn.chance"),"Spawn chance required");finite(g.getDouble("spawn.chance"),0,1);
        range(g,"game.starter-level",1,100); range(g,"game.starter-balls",1,64); range(g,"game.heal-delay-seconds",0,86400);
        range(g,"game.xp-factor",1,10000); range(g,"game.xp-per-level",1,10000);
        require(g.contains("capture.maximum"),"Capture settings required");finite(g.getDouble("capture.maximum"),0,1);
        for(String key:List.of("reference-level","level-offset","level-exponent","hp-bonus","minimum"))require(g.contains("capture."+key),"Missing catch policy "+key);
        CatchRules.Policy catchPolicy=new CatchRules.Policy(g.getDouble("capture.reference-level"),g.getDouble("capture.level-offset"),g.getDouble("capture.level-exponent"),g.getDouble("capture.hp-bonus"),g.getDouble("capture.minimum"),g.getDouble("capture.maximum"));
        finite(catchPolicy.referenceLevel(),1,100);finite(catchPolicy.levelOffset(),1,100);finite(catchPolicy.levelExponent(),.01,2);finite(catchPolicy.hpBonus(),.01,5);finite(catchPolicy.minimum(),0,.5);require(catchPolicy.maximum()>=catchPolicy.minimum()&&catchPolicy.maximum()<1,"Catch cap order; Master bypasses cap");
        Map<String,BallType> balls=new LinkedHashMap<>();YamlConfiguration by=yaml(root,"balls.yml");require(by.getKeys(false).equals(new HashSet<>(BALL_IDS)),"Free includes the standard Poke Ball");Set<List<String>> recipes=new HashSet<>();
        for(String id:BALL_IDS){
            var b=by.getConfigurationSection(id);double multiplier=b.getDouble("multiplier");finite(multiplier,.1,10);
            String top=b.getString("top",""),band=b.getString("band",""),trim=b.getString("trim","");for(String material:List.of(top,band,trim))require(blockCheck.test(Material.valueOf(material)),"Ball block material");
            Map<String,Double> parameters=new HashMap<>();var ps=b.getConfigurationSection("parameters");if(ps!=null)for(String key:ps.getKeys(false)){double value=ps.getDouble(key);finite(value,.01,72000);parameters.put(key,value);}
            BallType.Rule rule=BallType.Rule.valueOf(b.getString("rule",""));
            List<String> recipe=b.getStringList("recipe");int amount=b.getInt("craft-amount");require(amount>=1&&amount<=16,"Ball craft amount");
            require(recipe.isEmpty()||recipe.size()==9,"Ball recipe grid");for(String item:recipe)require(itemCheck.test(Material.valueOf(item)),"Ball recipe item");
            if(!recipe.isEmpty()){
                List<String> mirrored=new ArrayList<>();for(int row=0;row<3;row++)for(int col=2;col>=0;col--)mirrored.add(recipe.get(row*3+col));
                require(!recipes.contains(recipe)&&!recipes.contains(mirrored),"Duplicate ball recipe");recipes.add(recipe);recipes.add(mirrored);
            }
            balls.put(id,new BallType(id,rule,multiplier,Map.copyOf(parameters),top,band,trim,b.getBoolean("heal"),List.copyOf(recipe),amount));
            for(String key:List.of("name","description"))require(text.isString("balls."+id+"."+key),"Missing ball localization");
        }
        Map<String,Move> moves=new LinkedHashMap<>();
        for(String id:my.getKeys(false)) { ConfigurationSection s=my.getConfigurationSection(id); require(s!=null,"move "+id); Element type=Element.valueOf(s.getString("type","")); double power=s.getDouble("power");int uses=s.getInt("uses"),bonus=s.getInt("speed-bonus",0); require(Double.isFinite(power)&&power>0&&power<=300&&uses>0&&uses<=1000&&bonus>=-100&&bonus<=100,"move bounds"); moves.put(id,new Move(id,type,power,uses,bonus)); }
        require(moves.size()==40,"Free includes 40 signature moves");
        YamlConfiguration effects=yaml(root,"effects.yml");
        range(effects,"max-active",1,64);range(effects,"particles-per-tick",16,1000);
        EffectLimits effectLimits=new EffectLimits(effects.getBoolean("enabled",true),effects.getInt("max-active"),effects.getInt("particles-per-tick"));
        Map<String,Skill.Effect> effectData=new HashMap<>();
        for(String id:moves.keySet()){
            ConfigurationSection e=effects.getConfigurationSection("moves."+id);require(e!=null,"Missing move effect "+id);
            Particle particle=Particle.valueOf(e.getString("particle",""));require(particle.getDataType()==Void.class||particle==Particle.DUST,"Unsupported particle data");
            String color=e.getString("color","");require(color.matches("#[0-9a-fA-F]{6}"),"Effect color");
            String launch=e.getString("launch-sound",""),impact=e.getString("impact-sound","");
            require(launch.matches("minecraft:[a-z0-9_./]+")&&impact.matches("minecraft:[a-z0-9_./]+"),"Effect sound key");
            float size=(float)e.getDouble("size",1),volume=(float)e.getDouble("volume",.4),pitch=(float)e.getDouble("pitch",1);finite(size,.1,3);finite(volume,0,1);finite(pitch,.5,2);
            int variant=e.getInt("variant",0);float spread=(float)e.getDouble("spread",1),twist=(float)e.getDouble("twist",0);finite(variant,0,10000);finite(spread,.1,3);finite(twist,0,3);
            Particle accent=Particle.valueOf(e.getString("accent","CRIT"));require(accent.getDataType()==Void.class,"Accent particle must not require data");
            List<SkillScene.Layer> scene=new ArrayList<>();
            for(Map<?,?> layer:e.getMapList("scene.layers")){
                float start=decimal(layer,"start"),end=decimal(layer,"end"),scale=decimal(layer,"scale"),spin=decimal(layer,"spin"),pointSize=decimal(layer,"size");
                finite(start,0,2);finite(end,0,2);require(end>start,"Scene phase order");finite(scale,.05,3);finite(spin,-720,720);finite(pointSize,.1f,3);
                require(layer.get("count") instanceof Number,"Scene count");int count=((Number)layer.get("count")).intValue();require(count>=2&&count<=24,"Scene count bounds");
                String ink=Objects.toString(layer.get("color"));require(ink.matches("#[0-9a-fA-F]{6}"),"Scene color");
                List<Vector3f> path=vectors(layer.get("path")),glyph=vectors(layer.get("glyph"));require(!path.isEmpty()&&path.size()<=32&&!glyph.isEmpty()&&glyph.size()<=32,"Scene points");
                for(Vector3f v:path)require(v.x>=-.5&&v.x<=1.5&&Math.abs(v.y)<=2&&Math.abs(v.z)<=2,"Scene path bounds");
                for(Vector3f v:glyph)require(v.length()<=1.5,"Scene glyph bounds");
                scene.add(new SkillScene.Layer(start,end,SkillScene.Motion.valueOf(Objects.toString(layer.get("motion"))),path,glyph,count,scale,spin,Integer.parseInt(ink.substring(1),16),pointSize));
            }
            require(scene.size()<=5,"Scene layer cap");
            effectData.put(id,new Skill.Effect(Skill.Style.valueOf(e.getString("style","")),particle.name(),Integer.parseInt(color.substring(1),16),size,launch,impact,volume,pitch,variant,spread,twist,accent.name(),List.copyOf(scene)));
        }
        Map<Element,Map<Element,Double>> types=new EnumMap<>(Element.class);
        for(Element a:Element.values()) { Map<Element,Double> row=new EnumMap<>(Element.class); for(Element b:Element.values()) { require(ty.contains(a+"."+b),"Missing type cell"); double v=ty.getDouble(a+"."+b); finite(v,0,4); row.put(b,v); } types.put(a,Map.copyOf(row)); }
        Map<String,Species> species=new LinkedHashMap<>(); Map<String,Model> models=new HashMap<>(); Map<String,Map<State,Clip>> animations=new HashMap<>();Map<String,Map<String,Skill>> skills=new HashMap<>();
        for(String id:IDS) {
            ConfigurationSection s=sy.getConfigurationSection(id); require(s!=null,"species "+id);
            List<String> attacks=s.getStringList("moves"); require(attacks.size()==4&&new HashSet<>(attacks).size()==4&&moves.keySet().containsAll(attacks),"Four unique known moves");
            double rate=s.getDouble("catch-rate"); finite(rate,0,1); require(s.getInt("speed")>0&&s.getInt("speed")<=1000,"speed");
            Material icon=Material.valueOf(s.getString("icon","")); require(itemCheck.test(icon),"icon");
            for(String stat:List.of("hp","attack","defence","speed","special"))range(sy,id+".base-stats."+stat,1,255);
            Species.Stats stats=new Species.Stats(s.getInt("base-stats.hp"),s.getInt("base-stats.attack"),s.getInt("base-stats.defence"),s.getInt("base-stats.speed"),s.getInt("base-stats.special"));
            String secondary=s.getString("secondary-type");
            float metres=(float)s.getDouble("size.metres"),reference=(float)s.getDouble("size.reference-length",0);finite(metres,.1,10);finite(reference,0,20);
            float hover=(float)s.getDouble("size.hover",0);finite(hover,0,1);
            BodySize size=new BodySize(metres,BodySize.Axis.valueOf(s.getString("size.axis","")),reference,hover);
            double weight=s.getDouble("weight-kg");finite(weight,.1,2000);
            species.put(id,new Species(id,Element.valueOf(s.getString("type","")),stats.speed(),rate,icon.name(),List.copyOf(attacks),stats,secondary==null?null:Element.valueOf(secondary),Species.Habitat.valueOf(s.getString("habitat","")),size,weight));
            YamlConfiguration m=yaml(root,"models/"+id+".yml"); List<Part> parts=new ArrayList<>();
            for(Map<?,?> p:m.getMapList("parts")) {
                String material=Objects.toString(p.get("material")); require(blockCheck.test(Material.valueOf(material)),"block material");
                require(p.get("group") instanceof String group&&!group.isBlank(),"Part group required");
                Vector3f scale=vector(p.get("scale")); require(scale.x>0&&scale.y>0&&scale.z>0&&scale.length()<4,"scale");
                Vector3f position=vector(p.get("position")),pivot=vector(p.get("pivot"));require(position.length()<10&&pivot.length()<10,"model coordinate bounds");
                parts.add(new Part(material,position,scale,vector(p.get("rotation")).mul((float)Math.PI/180),Objects.toString(p.get("group")),pivot));
            }
            require(!parts.isEmpty()&&parts.size()<=24,"Model part cap");
            float height=(float)m.getDouble("height"),width=(float)m.getDouble("width"); finite(height,.7f,1.6f); finite(width,.3f,1.6f);
            BodySize.Fit fitted=size.fit(new Model(height,width,List.copyOf(parts)));models.put(id,fitted.model());
            finite(fitted.model().height(),.1,12);finite(fitted.model().width(),.1,16);
            YamlConfiguration a=yaml(root,"animations/"+id+".yml"); Map<State,Clip> clips=new EnumMap<>(State.class);
            Set<String> groups=new HashSet<>(List.of("root")); parts.forEach(p->groups.add(p.group()));
            for(State state:State.values()) {
                ConfigurationSection c=a.getConfigurationSection(state.name().toLowerCase(Locale.ROOT)); require(c!=null,"animation "+state);
                int ticks=c.getInt("ticks"); require(ticks>=4&&ticks<=400,"duration"); float end=(float)c.getDouble("scale-end",1); finite(end,0,2);
                Map<String,Track> tracks=new HashMap<>(); ConfigurationSection ts=c.getConfigurationSection("groups");
                if(ts!=null) for(String group:ts.getKeys(false)) { require(groups.contains(group),"Unknown animation group "+group); ConfigurationSection tr=ts.getConfigurationSection(group); Vector3f move=vector(tr.get("move"));require(move.length()<4,"animation movement bounds");float phase=(float)tr.getDouble("phase");finite(phase,-100,100);tracks.put(group,new Track(move,vector(tr.get("rotate")).mul((float)Math.PI/180),phase)); }
                clips.put(state,BodySize.scale(new Clip(ticks,c.getBoolean("loop"),end,Map.copyOf(tracks)),fitted.scale()));
            }
            require(clips.get(State.IDLE).loop()&&clips.get(State.WALK).loop(),"Idle and walk must loop");
            for(State st:List.of(State.ATTACK,State.HURT,State.CAPTURE,State.FAINT)) require(!clips.get(st).loop(),"Action must finish");
            animations.put(id,Map.copyOf(clips));
            Map<String,Skill> attacksByMove=new LinkedHashMap<>();
            for(String move:attacks){
                ConfigurationSection c=a.getConfigurationSection("attacks."+move);require(c!=null,"Missing attack animation "+id+":"+move);
                int ticks=c.getInt("ticks"),hit=c.getInt("hit-tick");require(ticks>=8&&ticks<=100&&hit>=4&&hit<ticks-1,"Attack timing");
                Map<String,Track> tracks=new HashMap<>();ConfigurationSection ts=c.getConfigurationSection("groups");require(ts!=null&&ts.contains("root"),"Attack root track");
                for(String group:ts.getKeys(false)){
                    require(groups.contains(group),"Unknown attack group "+group);ConfigurationSection tr=ts.getConfigurationSection(group);Vector3f moveVector=vector(tr.get("move"));require(moveVector.length()<4,"Attack displacement");
                    float phase=(float)tr.getDouble("phase");finite(phase,-100,100);tracks.put(group,new Track(moveVector,vector(tr.get("rotate")).mul((float)Math.PI/180),phase));
                }
                attacksByMove.put(move,new Skill(BodySize.scale(new Clip(ticks,false,1,Map.copyOf(tracks)),fitted.scale()),hit,effectData.get(move)));
                require(!effectData.get(move).scene().isEmpty(),"Authored scene required: "+move);
            }
            skills.put(id,Collections.unmodifiableMap(attacksByMove));
        }
        List<SpawnRule> spawns=new ArrayList<>(); YamlConfiguration sp=yaml(root,"spawns.yml");
        for(String id:IDS) {
            ConfigurationSection s=sp.getConfigurationSection(id); require(s!=null,"spawn "+id);
            int min=s.getInt("min-level"),max=s.getInt("max-level"),from=s.getInt("time-from"),to=s.getInt("time-to"),weight=s.getInt("weight");
            require(min>=1&&max>=min&&max<=100&&from>=0&&from<24000&&to>=0&&to<24000&&weight>0&&weight<=10000,"spawn bounds");
            Set<String> biomes=new HashSet<>(s.getStringList("biomes")); for(String biome:biomes) require(biomeCheck.test(biome),"biome "+biome);
            require(Species.Habitat.valueOf(s.getString("habitat",""))==species.get(id).habitat(),"Spawn habitat differs from species: "+id);
            spawns.add(new SpawnRule(id,List.copyOf(s.getStringList("worlds")),Set.copyOf(biomes),min,max,from,to,weight,Species.Habitat.valueOf(s.getString("habitat",""))));
        }
        require(g.isBoolean("recipe.enabled"),"Recipe enabled flag required");
        return new Settings(Map.copyOf(species),Map.copyOf(moves),Map.copyOf(types),Map.copyOf(models),Map.copyOf(animations),List.copyOf(spawns),g,text,Map.copyOf(skills),effectLimits,Collections.unmodifiableMap(balls),catchPolicy);
    }
    private static YamlConfiguration yaml(File root,String file) throws Exception { YamlConfiguration y=new YamlConfiguration(); y.load(new File(root,file)); return y; }
    private static float decimal(Map<?,?> map,String key){require(map.get(key) instanceof Number,"Scene number "+key);return ((Number)map.get(key)).floatValue();}
    private static List<Vector3f> vectors(Object value){require(value instanceof List<?>,"Scene vector list");return ((List<?>)value).stream().map(Settings::vector).toList();}
    private static Vector3f vector(Object value) {
        require(value instanceof List<?> l&&l.size()==3,"3D vector required"); List<?> l=(List<?>)value;
        float[] v=new float[3]; for(int i=0;i<3;i++) { require(l.get(i) instanceof Number,"numeric vector"); v[i]=((Number)l.get(i)).floatValue(); finite(v[i],-360,360); }
        return new Vector3f(v[0],v[1],v[2]);
    }
    private static void range(ConfigurationSection y,String key,int min,int max) { require(y.contains(key),"Missing "+key); int v=y.getInt(key); require(v>=min&&v<=max,"Invalid "+key); }
    private static void finite(double v,double min,double max) { require(Double.isFinite(v)&&v>=min&&v<=max,"Numeric bounds"); }
    private static void require(boolean condition,String message) { if(!condition) throw new IllegalArgumentException(message); }
    public int number(String path) { return general.getInt(path); }
    public String text(String key,Object... pairs) {
        String value=messages.getString(key,key); for(int i=0;i+1<pairs.length;i+=2) value=value.replace("{"+pairs[i]+"}",String.valueOf(pairs[i+1])); return value;
    }
    public String speciesName(String id) { return text("species."+id); }
}
