package ru.vanillamon;

import ru.vanillamon.config.Settings;
import ru.vanillamon.domain.Creature;
import ru.vanillamon.game.*;
import ru.vanillamon.storage.Repository;
import ru.vanillamon.ui.Menus;
import ru.vanillamon.world.*;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.bukkit.plugin.java.JavaPlugin;

public final class VanillaMon extends JavaPlugin implements CommandExecutor,TabCompleter {
    private volatile Settings settings;private Repository repository;private WorldEngine world;private Game game;private Menus menus;private final List<NamespacedKey> recipes=new ArrayList<>();
    public Settings settings(){return settings;}public Menus menus(){return menus;}public Game game(){return game;}
    @Override public void onEnable(){try{
        if(Bukkit.getPluginManager().getPlugin("VanillaMon")!=null)throw new IllegalStateException("Remove the old VanillaMon JAR first.");
        settings=Settings.load(this);repository=new Repository(getDataFolder().toPath().resolve("vanillamon.db"),()->settings.species(),()->settings.moves());repository.open().get(20,TimeUnit.SECONDS);
        world=new WorldEngine(this);game=new Game(this,repository,world);menus=new Menus(this,game);var manager=Bukkit.getPluginManager();manager.registerEvents(world,this);manager.registerEvents(game,this);manager.registerEvents(game.capture,this);manager.registerEvents(menus,this);
        manager.registerEvents(new CombatProtection(game.battles::containsKey,world::representation,e->e.getPersistentDataContainer().has(new NamespacedKey("vanillamon","ball_projectile")),at->false,game::isBall),this);
        for(String name:List.of("pokemonika","pc","dex")){Objects.requireNonNull(getCommand(name)).setExecutor(this);getCommand(name).setTabCompleter(this);}installRecipe();Bukkit.getScheduler().runTaskTimer(this,game::tick,1,1);Bukkit.getOnlinePlayers().forEach(game::load);getLogger().info("Pokemonika Free enabled");
    }catch(Exception e){getLogger().log(java.util.logging.Level.SEVERE,"Pokemonika Free initialization failed",e);Bukkit.getPluginManager().disablePlugin(this);}}
    @Override public void onDisable(){if(game!=null)game.shutdown();recipes.forEach(Bukkit::removeRecipe);recipes.clear();if(repository!=null)repository.close();}
    public boolean allowed(CommandSender sender,String node){String current="pokemonika."+node,old="vanillamon."+node;if(sender.isPermissionSet(current)&&!sender.hasPermission(current)||sender.isPermissionSet(old)&&!sender.hasPermission(old))return false;return sender.hasPermission(current)||sender.hasPermission(old);}
    private void reply(CommandSender sender,String key,Object... args){sender.sendMessage(Component.text(settings.text("prefix")+settings.text(key,args)));}
    private void installRecipe(){recipes.forEach(Bukkit::removeRecipe);recipes.clear();var key=new NamespacedKey(this,"ball_poke");Bukkit.removeRecipe(key);if(!settings.general().getBoolean("recipe.enabled"))return;var ball=settings.balls().get("poke");if(ball.recipe().isEmpty())return;var recipe=new ShapedRecipe(key,game.ball("poke",ball.craftAmount()));recipe.shape("abc","def","ghi");for(int n=0;n<9;n++){Material material=Material.valueOf(ball.recipe().get(n));if(material==Material.SNOWBALL)recipe.setIngredient((char)('a'+n),RecipeChoice.exactChoice(new ItemStack(material)));else recipe.setIngredient((char)('a'+n),material);}if(!Bukkit.addRecipe(recipe))throw new IllegalStateException("Cannot register Poke Ball recipe");recipes.add(key);Bukkit.getOnlinePlayers().forEach(this::discoverRecipes);}
    public void discoverRecipes(Player p){p.discoverRecipes(recipes);}
    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args){
        if(args.length>0&&args[0].equalsIgnoreCase("admin")){admin(sender,Arrays.copyOfRange(args,1,args.length));return true;}if(!(sender instanceof Player p)){reply(sender,"command.player");return true;}if(!allowed(p,"play")){reply(p,"denied");return true;}
        if(command.getName().equals("pc")){menus.pc(p,0);return true;}if(command.getName().equals("dex")){menus.dex(p);return true;}if(args.length==0){menus.team(p);return true;}
        switch(args[0].toLowerCase(Locale.ROOT)){case "starter"->menus.starter(p);case "heal"->game.heal(p);case "balls"->menus.balls(p);case "dex"->menus.dex(p);default->reply(p,"command.help");}return true;
    }
    private int number(String text,int min,int max){int value=Integer.parseInt(text);if(value<min||value>max)throw new IllegalArgumentException();return value;}
    private String species(String id){if(!settings.species().containsKey(id))throw new IllegalArgumentException();return id;}
    private void admin(CommandSender sender,String[] args){
        if(!allowed(sender,"admin")){reply(sender,"denied");return;}if(args.length==0){reply(sender,"admin.usage");return;}try{switch(args[0].toLowerCase(Locale.ROOT)){
            case "give"->{if(args.length!=4)throw new IllegalArgumentException();Player p=Objects.requireNonNull(Bukkit.getPlayerExact(args[1]));String id=species(args[2]);game.grant(p,id,number(args[3],1,100),false,ok->{if(ok)reply(sender,"admin.done");});}
            case "spawn"->{if(args.length!=3||!(sender instanceof Player p))throw new IllegalArgumentException();String id=species(args[1]);int level=number(args[2],1,100);if(world.actors.values().stream().filter(Actor::wild).count()>=settings.number("limits.wild")){reply(sender,"full");return;}Location at=world.near(p.getLocation(),3,settings.species().get(id));if(at==null){reply(sender,"world.unsafe");return;}world.spawn(Creature.create(settings.species().get(id),level,-1,settings.moves()),null,at);reply(sender,"admin.done");}
            case "balls"->{if(args.length!=3&&args.length!=4||args.length==4&&!args[2].equals("poke"))throw new IllegalArgumentException();Player p=Objects.requireNonNull(Bukkit.getPlayerExact(args[1]));game.giveBalls(p,"poke",number(args[args.length-1],1,1024),ok->{if(ok)reply(sender,"admin.done");});}
            case "clear"->{world.clear();reply(sender,"admin.done");}
            case "stats"->reply(sender,"admin.stats","actors",world.actors.size(),"parts",world.parts(),"battles",game.battles.size(),"players",game.sessions.size());
            case "reload"->{if(!game.battles.isEmpty()||game.capture.hasAnyThrow()||game.sessions.values().stream().anyMatch(s->s.busy)){reply(sender,"busy");return;}try{Settings candidate=Settings.load(this);world.clear();settings=candidate;game.reconfigure();installRecipe();reply(sender,"reload.ok");}catch(Exception e){getLogger().warning(e.getMessage());reply(sender,"reload.fail");}}
            default->reply(sender,"admin.usage");
        }}catch(IllegalArgumentException|NullPointerException e){reply(sender,"admin.usage");}
    }
    @Override public List<String> onTabComplete(CommandSender sender,Command command,String label,String[] args){List<String> choices=List.of();if(args.length==1)choices=allowed(sender,"admin")?List.of("starter","heal","balls","dex","admin"):List.of("starter","heal","balls","dex");else if(args.length==2&&args[0].equals("admin")&&allowed(sender,"admin"))choices=List.of("give","spawn","balls","clear","stats","reload");else if(args.length==3&&args[0].equals("admin")&&allowed(sender,"admin"))choices=args[1].equals("spawn")?Settings.IDS:Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();else if(args.length==4&&args[0].equals("admin")&&args[1].equals("give")&&allowed(sender,"admin"))choices=Settings.IDS;String prefix=args.length==0?"":args[args.length-1].toLowerCase(Locale.ROOT);return choices.stream().filter(s->s.startsWith(prefix)).toList();}
}
