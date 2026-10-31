package ru.vanillamon.domain;
import java.util.*;

public final class Profile {
    public final UUID owner;
    public long revision;
    public boolean starter;
    public UUID active;
    public long healedAt;
    public final List<Creature> creatures=new ArrayList<>();
    /** 1 = seen, 2 = caught. */
    public final Map<String,Integer> dex=new HashMap<>();
    public Profile(UUID owner) { this.owner=owner; }
    public Profile copy() {
        Profile p=new Profile(owner); p.revision=revision; p.starter=starter; p.active=active; p.healedAt=healedAt;
        creatures.forEach(c->p.creatures.add(c.copy())); p.dex.putAll(dex); return p;
    }
    public Creature at(int slot) { return creatures.stream().filter(c->c.slot==slot).findFirst().orElse(null); }
    public Creature byId(UUID id) { return creatures.stream().filter(c->c.id.equals(id)).findFirst().orElse(null); }
    public int freeSlot() { for(int i=0;i<51;i++) if(at(i)==null) return i; return -1; }
    public Creature fighter() {
        Creature chosen=byId(active);
        return chosen!=null && chosen.slot<6 && chosen.hp>0 ? chosen : creatures.stream().filter(c->c.slot<6&&c.hp>0).min(Comparator.comparingInt(c->c.slot)).orElse(null);
    }
    public void validate() {
        Set<Integer> slots=new HashSet<>(); Set<UUID> ids=new HashSet<>();
        for(Creature c:creatures) if(c.slot<0||c.slot>=51||!slots.add(c.slot)||!ids.add(c.id)||c.level<1||c.level>100||c.xp<0||c.pp.values().stream().anyMatch(pp->pp<0||pp>1000)||c.hp<0||c.hp>c.maxHp()) throw new IllegalArgumentException("Invalid creature/placement");
        Creature a=byId(active); if(active!=null&&(a==null||a.slot>=6)) throw new IllegalArgumentException("Invalid active member");
    }
    public void normalizeActive() { Creature a=byId(active); if(a==null||a.slot>=6) active=creatures.stream().filter(c->c.slot<6).map(c->c.id).findFirst().orElse(null); }
}
