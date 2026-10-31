package ru.vanillamon.ui;

import ru.vanillamon.*;
import ru.vanillamon.domain.*;
import ru.vanillamon.game.*;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import java.util.*;

public final class Menus implements Listener {
    public static final class Holder implements InventoryHolder {
        final UUID owner,session;
        final Map<Integer,Runnable> actions=new HashMap<>();
        Inventory inventory;
        Holder(UUID owner,UUID session){this.owner=owner;this.session=session;}
        @Override public Inventory getInventory(){return inventory;}
    }
    private final VanillaMon plugin;
    private final Game game;
    private final Map<UUID,Holder> open=new HashMap<>();
    public Menus(VanillaMon plugin,Game game){this.plugin=plugin;this.game=game;}
    private String text(String key,Object... values){return game.settings().text(key,values);}
    private Holder create(Player p,int size,String title,Object... values){
        Game.Session s=game.session(p);if(s==null)return null;Holder h=new Holder(p.getUniqueId(),s.token);h.inventory=Bukkit.createInventory(h,size,Component.text(text(title,values)));return h;
    }
    private void show(Player p,Holder h){if(h!=null){p.openInventory(h.inventory);open.put(p.getUniqueId(),h);}}
    private ItemStack item(Material material,String title,List<String> lore){ItemStack i=new ItemStack(material);var meta=i.getItemMeta();meta.displayName(Component.text(title));meta.lore(lore.stream().map(Component::text).toList());i.setItemMeta(meta);return i;}
    private void button(Holder h,int slot,Material material,String key,Runnable action){h.inventory.setItem(slot,item(material,text(key),List.of()));h.actions.put(slot,action);}
    private ItemStack creature(Creature c){return creature(c,true);}
    private ItemStack creature(Creature c,boolean ownedActive){
        Species s=game.settings().species().get(c.species);List<String> lore=new ArrayList<>();
        lore.add(text("lore.hp","hp",c.hp,"max",c.maxHp()));lore.add(text("lore.stats","attack",c.attack(),"defence",c.defence(),"speed",c.speed(),"xp",c.xp,"next",Rules.threshold(c.level,game.settings().number("game.xp-factor"))));
        lore.add(text("lore.special","special",c.special()));lore.add(text("lore.types","types",String.join(" / ",s.types().stream().map(t->text("types."+t)).toList())));
        lore.add(text("lore.size","measure",text(s.size().axis()==BodySize.Axis.HEIGHT?"size.height":"size.length"),"metres",s.size().metres()));
        c.pp.forEach((id,pp)->lore.add(text("lore.move","name",text("moves."+id),"power",game.settings().moves().get(id).power(),"pp",pp,"max",game.settings().moves().get(id).uses())));
        if(c.slot>=0)lore.add(text("lore.slot","slot",c.slot<6?c.slot+1:(c.slot-6)%45+1,"place",text(c.slot<6?"lore.team":"lore.pc")));
        Game.Session owner=game.sessions.values().stream().filter(v->v.profile.byId(c.id)!=null).findFirst().orElse(null);
        if(ownedActive&&owner!=null&&c.id.equals(owner.profile.active))lore.add(text("lore.active"));
        return item(Material.valueOf(s.icon()),text("creature.name","name",game.settings().speciesName(c.species),"level",c.level),lore);
    }
    public void team(Player p){
        Battle b=game.battles.get(p.getUniqueId());if(b!=null){battle(p,b);return;}if(!game.available(p,false))return;
        Game.Session s=game.sessions.get(p.getUniqueId());Holder h=create(p,36,"menu.team");
        for(Creature c:s.profile.creatures)if(c.slot<6){int slot=10+c.slot;h.inventory.setItem(slot,creature(c));h.actions.put(slot,()->detail(p,c.id));}
        button(h,27,Material.CHEST,"button.storage",()->pc(p,0));button(h,29,Material.BOOK,"menu.dex",()->dex(p));
        button(h,33,Material.APPLE,"button.heal",()->{p.closeInventory();game.heal(p);});
        if(!s.profile.starter)button(h,35,Material.NETHER_STAR,"menu.starter",()->starter(p));show(p,h);
    }
    public void starter(Player p){
        if(!game.available(p,true))return;if(game.sessions.get(p.getUniqueId()).profile.starter){game.tell(p,"starter.already");return;}
        Holder h=create(p,27,"menu.starter");int slot=11;
        for(String id:List.of("charmander","bulbasaur","squirtle")){Species species=game.settings().species().get(id);Creature c=Creature.create(species,game.settings().number("game.starter-level"),0,game.settings().moves());h.inventory.setItem(slot,creature(c));h.actions.put(slot,()->game.grant(p,id,c.level,true,ok->{if(ok)team(p);}));slot+=2;}show(p,h);
    }
    public void detail(Player p,UUID id){
        if(!game.available(p,true))return;Creature c=game.sessions.get(p.getUniqueId()).profile.byId(id);if(c==null)return;
        Holder h=create(p,36,"menu.detail");h.inventory.setItem(13,creature(c));
        button(h,20,Material.LIME_DYE,"button.active",()->game.change(p,profile->{Creature current=profile.byId(id);if(current==null||current.slot>=6)throw new IllegalArgumentException();profile.active=id;},()->team(p)));
        button(h,22,Material.HOPPER,"button.store",()->game.change(p,profile->{Creature current=profile.byId(id);if(current==null)throw new IllegalArgumentException();int free=-1;for(int i=6;i<51;i++)if(profile.at(i)==null){free=i;break;}if(free<0)throw new IllegalArgumentException();current.slot=free;},()->pc(p,0)));
        button(h,24,Material.COMPASS,"button.swap",()->swap(p,id));
        button(h,27,Material.ARROW,"button.back",()->team(p));show(p,h);
    }
    private void swap(Player p,UUID id){
        if(!game.available(p,true))return;Holder h=create(p,27,"button.swap");Profile profile=game.sessions.get(p.getUniqueId()).profile;
        for(int i=0;i<6;i++){int dest=i;Creature c=profile.at(i);h.inventory.setItem(10+i,c==null?item(Material.GRAY_STAINED_GLASS_PANE,text("team.slot","slot",i+1),List.of()):creature(c));h.actions.put(10+i,()->game.change(p,pr->{Creature from=pr.byId(id),to=pr.at(dest);if(from==null||from.slot>=6)throw new IllegalArgumentException();int old=from.slot;from.slot=dest;if(to!=null&&!to.id.equals(from.id))to.slot=old;},()->team(p)));}show(p,h);
    }
    public void pc(Player p,int page){
        if(!game.available(p,true))return;int index=Math.max(0,Math.min(0,page));Holder h=create(p,54,"menu.pc","page",index+1);Profile profile=game.sessions.get(p.getUniqueId()).profile;
        for(Creature c:profile.creatures)if(c.slot>=6+(index*45)&&c.slot<6+(index+1)*45){int slot=c.slot-6-index*45;h.inventory.setItem(slot,creature(c));h.actions.put(slot,()->game.change(p,pr->{Creature stored=pr.byId(c.id);if(stored==null||stored.slot<6)throw new IllegalArgumentException();int free=-1;for(int n=0;n<6;n++)if(pr.at(n)==null){free=n;break;}if(free<0)throw new IllegalArgumentException();stored.slot=free;},()->pc(p,index)));}
        if(index>0)button(h,45,Material.ARROW,"button.previous",()->pc(p,index-1));button(h,49,Material.CHEST,"button.back",()->team(p));show(p,h);
    }
    public void dex(Player p){dex(p,0);}
    public void dex(Player p,int page){
        if(!game.available(p,false))return;
        var ids=ru.vanillamon.config.Settings.IDS;int pages=(ids.size()+44)/45,index=Math.clamp(page,0,pages-1);
        Holder h=create(p,54,"menu.dex","page",index+1,"pages",pages);Profile profile=game.sessions.get(p.getUniqueId()).profile;
        for(int n=index*45;n<Math.min(ids.size(),(index+1)*45);n++){
            String id=ids.get(n);Species species=game.settings().species().get(id);int status=profile.dex.getOrDefault(id,0);
            var base=species.stats();List<String> lore=new ArrayList<>(List.of(text("dex."+status),text("lore.types","types",String.join(" / ",species.types().stream().map(t->text("types."+t)).toList())),text("lore.dex-stats","hp",base.hp(),"attack",base.attack(),"defence",base.defence()),text("lore.dex-speed","speed",base.speed(),"special",base.special())));
            h.inventory.setItem(n-index*45,item(status==0?Material.GRAY_DYE:Material.valueOf(species.icon()),game.settings().speciesName(id),lore));
        }
        if(index>0)button(h,45,Material.ARROW,"button.previous",()->dex(p,index-1));
        button(h,49,Material.ARROW,"button.back",()->team(p));
        if(index<pages-1)button(h,53,Material.ARROW,"button.next",()->dex(p,index+1));show(p,h);
    }
    private List<String> moveLore(Creature owner,String id,int pp){var move=game.settings().moves().get(id);return List.of(text("lore.move","name",text("moves."+id),"power",move.power(),"pp",pp,"max",move.uses()),text("lore.timing","seconds",String.format(Locale.ROOT,"%.2f",game.settings().skills().get(owner.species).get(id).hitTick()/20.0),"speed",String.format(Locale.ROOT,"%+d",move.speedBonus())));}
    public void battle(Player p,Battle b){
        if(!game.valid(b)||b.resolving||!game.sessions.containsKey(b.player))return;Holder h=create(p,54,"menu.battle");h.inventory.setItem(4,creature(b.wild.creature));h.inventory.setItem(13,creature(b.ally.creature));int[] slots={19,21,23,25};int index=0;
        for(var pp:b.ally.creature.pp.entrySet()){
            String move=pp.getKey();Move m=game.settings().moves().get(move);int slot=slots[index++];
            h.inventory.setItem(slot,item(pp.getValue()>0?Material.PAPER:Material.GRAY_DYE,text("moves."+move),moveLore(b.ally.creature,move,pp.getValue())));
            h.actions.put(slot,()->game.attack(p,move));
        }
        button(h,37,Material.SNOWBALL,"button.capture",()->capture(p,b));button(h,40,Material.CHEST,"button.switch",()->switchMenu(p,b));button(h,43,Material.FEATHER,"button.flee",()->game.flee(p));show(p,h);
    }
    public void capture(Player p,Battle b){
        if(!game.available(p,false)||!game.valid(b)||b.resolving)return;
        Holder h=create(p,36,"menu.balls-capture");int slot=0;
        for(var ball:game.settings().balls().values()){
            int count=game.countBalls(p,ball.id());String percent=String.format(Locale.ROOT,"%.1f",game.capture.chance(p,b.wild,ball.id(),b).chance()*100);
            h.inventory.setItem(slot,item(count>0?Material.SNOWBALL:Material.GRAY_DYE,text("balls."+ball.id()+".name"),List.of(text("balls."+ball.id()+".description"),text("lore.ball-chance","count",count,"chance",percent))));
            if(count>0)h.actions.put(slot,()->game.capture.inBattle(p,ball.id()));slot++;
        }
        button(h,31,Material.ARROW,"button.back",()->battle(p,b));show(p,h);
    }
    public void balls(Player p){
        if(!game.available(p,true))return;Holder h=create(p,36,"menu.balls");int slot=0;
        for(var ball:game.settings().balls().values()){
            boolean craftable=game.settings().general().getBoolean("recipe.enabled")&&!ball.recipe().isEmpty();
            List<String> lore=List.of(text("balls."+ball.id()+".description"),text(ball.recipe().isEmpty()?"lore.ball-admin":craftable?"lore.ball-recipe":"lore.ball-disabled"));
            h.inventory.setItem(slot,item(Material.SNOWBALL,text("balls."+ball.id()+".name"),lore));
            if(craftable)h.actions.put(slot,()->ballRecipe(p,ball.id()));slot++;
        }
        button(h,31,Material.ARROW,"button.back",()->team(p));show(p,h);
    }
    private void ballRecipe(Player p,String id){
        if(!game.available(p,true))return;var ball=game.settings().balls().get(id);if(ball==null||ball.recipe().size()!=9)return;
        Holder h=create(p,36,"menu.ball-recipe","ball",text("balls."+id+".name"));int[] slots={1,2,3,10,11,12,19,20,21};
        for(int n=0;n<9;n++)h.inventory.setItem(slots[n],new ItemStack(Material.valueOf(ball.recipe().get(n))));
        h.inventory.setItem(15,game.ball(id,ball.craftAmount()));button(h,31,Material.ARROW,"button.back",()->balls(p));show(p,h);
    }
    private void switchMenu(Player p,Battle b){
        if(!game.valid(b)||b.resolving)return;Holder h=create(p,27,"menu.switch");for(Creature c:game.sessions.get(b.player).profile.creatures)if(c.slot<6){h.inventory.setItem(10+c.slot,creature(c));h.actions.put(10+c.slot,()->game.switchTo(p,c.id));}button(h,22,Material.ARROW,"button.back",()->battle(p,b));show(p,h);
    }
    @EventHandler(priority=EventPriority.HIGHEST) public void click(InventoryClickEvent e){
        if(!(e.getView().getTopInventory().getHolder() instanceof Holder h))return;e.setCancelled(true);
        if(!(e.getWhoClicked() instanceof Player p)||!p.getUniqueId().equals(h.owner)||open.get(h.owner)!=h||game.sessions.get(h.owner)==null||!game.sessions.get(h.owner).token.equals(h.session))return;
        if(e.getRawSlot()<0||e.getRawSlot()>=h.inventory.getSize()||(e.getClick()!=ClickType.LEFT&&e.getClick()!=ClickType.RIGHT))return;
        Runnable action=h.actions.get(e.getRawSlot());if(action!=null)Bukkit.getScheduler().runTask(plugin,()->{if(p.isOnline()&&open.get(h.owner)==h&&game.sessions.get(h.owner)!=null&&game.sessions.get(h.owner).token.equals(h.session))action.run();});
    }
    @EventHandler(priority=EventPriority.HIGHEST) public void drag(InventoryDragEvent e){if(e.getView().getTopInventory().getHolder() instanceof Holder)e.setCancelled(true);}
    @EventHandler public void close(InventoryCloseEvent e){if(e.getInventory().getHolder() instanceof Holder h)open.remove(e.getPlayer().getUniqueId(),h);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void drop(PlayerDropItemEvent e){if(e.getPlayer().getOpenInventory().getTopInventory().getHolder() instanceof Holder)e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void hands(PlayerSwapHandItemsEvent e){if(e.getPlayer().getOpenInventory().getTopInventory().getHolder() instanceof Holder)e.setCancelled(true);}
}
