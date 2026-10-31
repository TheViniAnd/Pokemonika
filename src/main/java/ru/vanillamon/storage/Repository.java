package ru.vanillamon.storage;

import ru.vanillamon.domain.*;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** One dedicated DB worker; no Bukkit objects or world access on this thread. */
public final class Repository implements AutoCloseable {
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r->new Thread(r,"Pokemonika-SQLite"));
    private Connection db;
    private final Path path;
    private final Supplier<Map<String,Species>> species;
    private final Supplier<Map<String,Move>> moves;
    public Repository(Path path) { this(path,Map::of,Map::of); }
    public Repository(Path path,Supplier<Map<String,Species>> species,Supplier<Map<String,Move>> moves) { this.path=path;this.species=species;this.moves=moves; }
    @FunctionalInterface private interface Work<T> { T run() throws Exception; }
    private <T> CompletableFuture<T> async(Work<T> work) {
        return CompletableFuture.supplyAsync(()->{ try { return work.run(); } catch(Exception e) { throw new CompletionException(e); } },worker);
    }
    private <T> T transaction(Work<T> work) throws Exception {
        db.setAutoCommit(false);
        try { T value=work.run(); db.commit(); return value; }
        catch(Exception e) { db.rollback(); throw e; }
        finally { db.setAutoCommit(true); }
    }
    public CompletableFuture<Integer> open() { return async(()->{
        Class.forName("org.sqlite.JDBC"); db=DriverManager.getConnection("jdbc:sqlite:"+path);
        try(Statement s=db.createStatement()) {
            try(var v=s.executeQuery("PRAGMA user_version")){if(v.next()&&v.getInt(1)>3)throw new SQLException("Premium data cannot be opened by Free");}
            s.execute("PRAGMA foreign_keys=ON"); s.execute("PRAGMA journal_mode=WAL"); s.execute("PRAGMA synchronous=FULL"); s.execute("PRAGMA busy_timeout=5000");
            s.execute("CREATE TABLE IF NOT EXISTS players(owner TEXT PRIMARY KEY,revision INTEGER NOT NULL DEFAULT 0,starter INTEGER NOT NULL DEFAULT 0,active TEXT,healed INTEGER NOT NULL DEFAULT 0)");
            s.execute("CREATE TABLE IF NOT EXISTS creatures(id TEXT PRIMARY KEY,owner TEXT NOT NULL REFERENCES players(owner),species TEXT NOT NULL,level INTEGER NOT NULL CHECK(level BETWEEN 1 AND 100),xp INTEGER NOT NULL,hp INTEGER NOT NULL,slot INTEGER NOT NULL CHECK(slot BETWEEN 0 AND 185),UNIQUE(owner,slot))");
            boolean hasRules=false;try(ResultSet columns=s.executeQuery("PRAGMA table_info(creatures)")){while(columns.next())if(columns.getString("name").equals("rules_version"))hasRules=true;}
            if(!hasRules){
                boolean occupied;try(ResultSet rows=s.executeQuery("SELECT count(*) FROM creatures")){rows.next();occupied=rows.getInt(1)>0;}
                if(occupied){
                    Path backup=path.toAbsolutePath().getParent().resolve("backups/upgrade-1.2/vanillamon-before-1.2.db");Files.createDirectories(backup.getParent());
                    if(!Files.exists(backup))try(PreparedStatement snapshot=db.prepareStatement("VACUUM INTO ?")){snapshot.setString(1,backup.toString());snapshot.execute();}
                }
                s.execute("ALTER TABLE creatures ADD COLUMN rules_version INTEGER NOT NULL DEFAULT 0");
            }
            s.execute("CREATE TABLE IF NOT EXISTS moves(creature TEXT NOT NULL REFERENCES creatures(id) ON DELETE CASCADE,position INTEGER NOT NULL,move TEXT NOT NULL,pp INTEGER NOT NULL CHECK(pp>=0),PRIMARY KEY(creature,position))");
            s.execute("CREATE TABLE IF NOT EXISTS dex(owner TEXT NOT NULL REFERENCES players(owner),species TEXT NOT NULL,status INTEGER NOT NULL,PRIMARY KEY(owner,species))");
            s.execute("CREATE TABLE IF NOT EXISTS operations(id TEXT PRIMARY KEY,owner TEXT NOT NULL,kind TEXT NOT NULL,status TEXT NOT NULL,result TEXT,created INTEGER NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS deliveries(id TEXT PRIMARY KEY,owner TEXT NOT NULL,amount INTEGER NOT NULL CHECK(amount>0),status TEXT NOT NULL,created INTEGER NOT NULL,ball_type TEXT NOT NULL DEFAULT 'poke')");
            boolean typed=false;try(ResultSet columns=s.executeQuery("PRAGMA table_info(deliveries)")){while(columns.next())if(columns.getString("name").equals("ball_type"))typed=true;}
            if(!typed){
                boolean occupied;try(ResultSet rows=s.executeQuery("SELECT count(*) FROM players")){rows.next();occupied=rows.getInt(1)>0;}
                if(occupied){Path backup=path.toAbsolutePath().getParent().resolve("backups/upgrade-1.5/vanillamon-before-1.5.db");Files.createDirectories(backup.getParent());if(!Files.exists(backup))try(PreparedStatement snapshot=db.prepareStatement("VACUUM INTO ?")){snapshot.setString(1,backup.toString());snapshot.execute();}}
                s.execute("ALTER TABLE deliveries ADD COLUMN ball_type TEXT NOT NULL DEFAULT 'poke'");
            }
            s.execute("PRAGMA user_version=3");
            // Prepared throws have no persisted ownership result. Never replay item delivery on restart.
            return s.executeUpdate("UPDATE operations SET status='ABORTED' WHERE status='PREPARED'");
        }
    }); }
    private Profile read(UUID owner) throws SQLException {
        try(PreparedStatement s=db.prepareStatement("INSERT OR IGNORE INTO players(owner) VALUES(?)")) { s.setString(1,owner.toString()); s.executeUpdate(); }
        Profile p=new Profile(owner);
        try(PreparedStatement s=db.prepareStatement("SELECT * FROM players WHERE owner=?")) {
            s.setString(1,owner.toString()); try(ResultSet r=s.executeQuery()) { r.next(); p.revision=r.getLong("revision"); p.starter=r.getBoolean("starter"); p.healedAt=r.getLong("healed"); String a=r.getString("active"); p.active=a==null?null:UUID.fromString(a); }
        }
        try(PreparedStatement s=db.prepareStatement("SELECT * FROM creatures WHERE owner=? ORDER BY slot")) {
            s.setString(1,owner.toString()); try(ResultSet r=s.executeQuery()) {
                while(r.next()) {
                    LinkedHashMap<String,Integer> pp=new LinkedHashMap<>();
                    try(PreparedStatement m=db.prepareStatement("SELECT move,pp FROM moves WHERE creature=? ORDER BY position")) {
                        m.setString(1,r.getString("id")); try(ResultSet mr=m.executeQuery()) { while(mr.next()) pp.put(mr.getString(1),mr.getInt(2)); }
                    }
                    Creature c=new Creature(UUID.fromString(r.getString("id")),r.getString("species"),r.getInt("level"),r.getInt("xp"),r.getInt("hp"),r.getInt("slot"),pp);
                    Species definition=species.get().get(c.species);
                    if(definition!=null){
                        boolean legacy=r.getInt("rules_version")==0;
                        if(legacy&&(c.hp<0||c.hp>20+3*c.level))throw new SQLException("Invalid legacy health");
                        c.bind(definition,legacy);
                        if(legacy&&!definition.moves().containsAll(c.pp.keySet())){
                            List<Integer> previous=new ArrayList<>(c.pp.values());c.pp.clear();int slot=0;
                            for(String move:definition.moves()){
                                int old=slot<previous.size()?Math.clamp(previous.get(slot),0,15):0;
                                c.pp.put(move,old*moves.get().get(move).uses()/15);slot++;
                            }
                        }
                    }
                    p.creatures.add(c);
                }
            }
        }
        try(PreparedStatement s=db.prepareStatement("SELECT species,status FROM dex WHERE owner=?")) { s.setString(1,owner.toString()); try(ResultSet r=s.executeQuery()) { while(r.next()) p.dex.put(r.getString(1),r.getInt(2)); } }
        p.validate();return p;
    }
    public CompletableFuture<Profile> load(UUID owner) { return async(()->read(owner)); }
    private Profile write(Profile p) throws SQLException {
        p.validate();
        try(PreparedStatement s=db.prepareStatement("UPDATE players SET revision=revision+1,starter=?,active=?,healed=? WHERE owner=? AND revision=?")) {
            s.setBoolean(1,p.starter); s.setString(2,p.active==null?null:p.active.toString()); s.setLong(3,p.healedAt); s.setString(4,p.owner.toString()); s.setLong(5,p.revision);
            if(s.executeUpdate()!=1) throw new SQLException("Stale profile revision");
        }
        try(PreparedStatement s=db.prepareStatement("DELETE FROM creatures WHERE owner=?")) { s.setString(1,p.owner.toString()); s.executeUpdate(); }
        for(Creature c:p.creatures) {
            try(PreparedStatement s=db.prepareStatement("INSERT INTO creatures(id,owner,species,level,xp,hp,slot,rules_version) VALUES(?,?,?,?,?,?,?,1)")) {
                s.setString(1,c.id.toString()); s.setString(2,p.owner.toString()); s.setString(3,c.species); s.setInt(4,c.level); s.setInt(5,c.xp); s.setInt(6,c.hp); s.setInt(7,c.slot);
                s.executeUpdate();
            }
            int i=0;
            for(var m:c.pp.entrySet()) try(PreparedStatement s=db.prepareStatement("INSERT INTO moves VALUES(?,?,?,?)")) { s.setString(1,c.id.toString()); s.setInt(2,i++); s.setString(3,m.getKey()); s.setInt(4,m.getValue()); s.executeUpdate(); }
        }
        try(PreparedStatement s=db.prepareStatement("DELETE FROM dex WHERE owner=?")) { s.setString(1,p.owner.toString()); s.executeUpdate(); }
        for(var d:p.dex.entrySet()) try(PreparedStatement s=db.prepareStatement("INSERT INTO dex VALUES(?,?,?)")) { s.setString(1,p.owner.toString()); s.setString(2,d.getKey()); s.setInt(3,d.getValue()); s.executeUpdate(); }
        p.revision++; return p;
    }
    public CompletableFuture<Profile> save(Profile p) { Profile snapshot=p.copy(); return async(()->transaction(()->write(snapshot))); }

    public CompletableFuture<Boolean> prepare(UUID op,UUID owner,String kind) { return async(()->transaction(()->{
        Profile p=read(owner); if(p.freeSlot()<0) return false;
        try(PreparedStatement s=db.prepareStatement("SELECT count(*) FROM operations WHERE owner=? AND status='PREPARED'")) {
            s.setString(1,owner.toString()); try(ResultSet r=s.executeQuery()) { r.next(); if(r.getInt(1)>0) return false; }
        }
        try(PreparedStatement s=db.prepareStatement("INSERT OR IGNORE INTO operations VALUES(?,?,?,'PREPARED',NULL,?)")) { s.setString(1,op.toString()); s.setString(2,owner.toString()); s.setString(3,kind); s.setLong(4,System.currentTimeMillis()); return s.executeUpdate()==1; }
    })); }
    public CompletableFuture<Void> abort(UUID op) { return async(()->{
        try(PreparedStatement s=db.prepareStatement("UPDATE operations SET status='ABORTED' WHERE id=? AND status='PREPARED'")) { s.setString(1,op.toString()); s.executeUpdate(); } return null;
    }); }
    /** Grant and operation completion share the same transaction. Replays return the committed profile. */
    public CompletableFuture<Profile> grant(UUID operation,Profile profile,Creature creature,boolean starter) {
        return grant(operation,profile,creature,starter,0);
    }
    public CompletableFuture<Profile> grant(UUID operation,Profile profile,Creature creature,boolean starter,int starterBalls) {
        Profile p=profile.copy(); Creature copy=creature.copy();
        return async(()->transaction(()->{
            String status=null,kind=null;
            try(PreparedStatement s=db.prepareStatement("SELECT owner,status,kind FROM operations WHERE id=?")) {
                s.setString(1,operation.toString()); try(ResultSet r=s.executeQuery()) { if(r.next()) { if(!r.getString(1).equals(p.owner.toString())) throw new SQLException("Operation owner mismatch"); status=r.getString(2); kind=r.getString(3); } }
            }
            if("COMMITTED".equals(status)) return read(p.owner);
            if(status!=null&&!"PREPARED".equals(status)) throw new SQLException("Operation already aborted");
            if("CAPTURE".equals(kind)&&status==null) throw new SQLException("Capture not prepared");
            if(starter&&read(p.owner).starter) throw new SQLException("Starter already claimed");
            if(p.freeSlot()<0) throw new SQLException("No free slot");
            if(status==null) try(PreparedStatement s=db.prepareStatement("INSERT INTO operations VALUES(?,?,?,'PREPARED',NULL,?)")) { s.setString(1,operation.toString()); s.setString(2,p.owner.toString()); s.setString(3,starter?"STARTER":"GRANT"); s.setLong(4,System.currentTimeMillis()); s.executeUpdate(); }
            copy.slot=p.freeSlot(); p.creatures.add(copy); p.dex.put(copy.species,2); p.starter|=starter; p.normalizeActive();
            write(p);
            if(starter&&starterBalls>0) enqueue(operation,p.owner,starterBalls,"poke");
            try(PreparedStatement s=db.prepareStatement("UPDATE operations SET status='COMMITTED',result=? WHERE id=? AND status='PREPARED'")) { s.setString(1,copy.id.toString()); s.setString(2,operation.toString()); if(s.executeUpdate()!=1) throw new SQLException("Operation conflict"); }
            return p;
        }));
    }
    public record Delivery(UUID id,int amount,String ballType) {public Delivery(UUID id,int amount){this(id,amount,"poke");}}
    private void enqueue(UUID id,UUID owner,int amount,String ballType) throws SQLException {
        if(amount<1||amount>1024)throw new IllegalArgumentException("Invalid ball amount");
        int index=0;
        while(amount>0){
            UUID packet=index==0?id:UUID.nameUUIDFromBytes((id+":"+index).getBytes(StandardCharsets.UTF_8));int n=Math.min(16,amount);
            try(PreparedStatement s=db.prepareStatement("INSERT OR IGNORE INTO deliveries(id,owner,amount,status,created,ball_type) VALUES(?,?,?,'PENDING',?,?)")) {s.setString(1,packet.toString());s.setString(2,owner.toString());s.setInt(3,n);s.setLong(4,System.currentTimeMillis()+index);s.setString(5,ballType);s.executeUpdate();}
            amount-=n;index++;
        }
    }
    public CompletableFuture<Void> queueBalls(UUID id,UUID owner,int amount) {return queueBalls(id,owner,amount,"poke");}
    public CompletableFuture<Void> queueBalls(UUID id,UUID owner,int amount,String ballType) {return async(()->transaction(()->{
        if(amount<1||amount>1024||!"poke".equals(ballType))throw new IllegalArgumentException("Invalid ball delivery");String result="balls:"+ballType+":"+amount;
        try(PreparedStatement s=db.prepareStatement("SELECT owner,kind,result FROM operations WHERE id=?")){s.setString(1,id.toString());try(ResultSet r=s.executeQuery()){if(r.next()){
            if(!r.getString(1).equals(owner.toString())||!r.getString(2).equals("BALLS")||!(result.equals(r.getString(3))||r.getString(3)==null&&ballType.equals("poke")))throw new SQLException("Delivery operation mismatch");return null;
        }}}
        try(PreparedStatement s=db.prepareStatement("INSERT INTO operations VALUES(?,?,'BALLS','COMMITTED',?,?)")){s.setString(1,id.toString());s.setString(2,owner.toString());s.setString(3,result);s.setLong(4,System.currentTimeMillis());s.executeUpdate();}
        enqueue(id,owner,amount,ballType);return null;
    }));}
    public CompletableFuture<Delivery> nextDelivery(UUID owner) {return async(()->transaction(()->{
        Delivery delivery=null;
        try(PreparedStatement s=db.prepareStatement("SELECT id,amount,ball_type FROM deliveries WHERE owner=? AND status!='DONE' ORDER BY created,id LIMIT 1")) {
            s.setString(1,owner.toString());try(ResultSet r=s.executeQuery()){if(r.next())delivery=new Delivery(UUID.fromString(r.getString(1)),r.getInt(2),r.getString(3));}
        }
        if(delivery!=null)try(PreparedStatement s=db.prepareStatement("UPDATE deliveries SET status='CLAIMED' WHERE id=?")){s.setString(1,delivery.id.toString());s.executeUpdate();}
        return delivery;
    }));}
    public CompletableFuture<Void> acknowledgeDelivery(UUID id) {return async(()->{try(PreparedStatement s=db.prepareStatement("UPDATE deliveries SET status='DONE' WHERE id=?")){s.setString(1,id.toString());s.executeUpdate();}return null;});}
    public CompletableFuture<String> operationStatus(UUID op) { return async(()->{ try(PreparedStatement s=db.prepareStatement("SELECT status FROM operations WHERE id=?")) { s.setString(1,op.toString()); try(ResultSet r=s.executeQuery()) { return r.next()?r.getString(1):null; } } }); }
    public CompletableFuture<Profile> saveVictory(Profile profile,UUID op){Profile snapshot=profile.copy();return async(()->transaction(()->{
        try(var q=db.prepareStatement("SELECT owner,kind FROM operations WHERE id=?")){q.setString(1,op.toString());try(var row=q.executeQuery()){if(row.next()){if(!row.getString(1).equals(snapshot.owner.toString())||!row.getString(2).equals("VICTORY"))throw new SQLException("Victory replay mismatch");return read(snapshot.owner);}}}
        write(snapshot);try(var q=db.prepareStatement("INSERT INTO operations VALUES(?,?,'VICTORY','COMMITTED',NULL,?)")){q.setString(1,op.toString());q.setString(2,snapshot.owner.toString());q.setLong(3,System.currentTimeMillis());q.executeUpdate();}return snapshot;
    }));}
    @Override public void close() {
        try { async(()->{ if(db!=null) db.close(); return null; }).get(15,TimeUnit.SECONDS); }
        catch(Exception e) { throw new IllegalStateException("Database shutdown failed",e); }
        finally { worker.shutdown(); }
    }
}
