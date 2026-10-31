package ru.vanillamon;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.bukkit.configuration.file.YamlConfiguration;
import ru.vanillamon.config.*;
import ru.vanillamon.domain.*;
import ru.vanillamon.storage.Repository;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ExecutionException;
import static org.junit.jupiter.api.Assertions.*;

class FreeEditionTest {
    static Settings s;@TempDir Path dir;
    @BeforeAll static void settings() throws Exception{s=Settings.load(Path.of("src/main/resources").toFile(),m->true,m->true,b->true);}
    Repository db(Path path) throws Exception {var r=new Repository(path,s::species,s::moves);r.open().get();return r;}
    Creature mon(int slot){return Creature.create(s.species().get("pikachu"),20,slot,s.moves());}
    @Test void catalogueContainsTenCompletePokemonAndOneBall(){assertEquals(10,s.species().size());assertEquals(40,s.moves().size());assertEquals(Set.of("poke"),s.balls().keySet());assertEquals(10,s.models().size());assertEquals(10,s.spawns().size());for(String id:Settings.IDS)assertEquals(4,s.skills().get(id).size());assertEquals("Ready. /pixelmonika",s.text("ready"));}
    @Test void russianPackHasSameKeysAndCanBeSelected() throws Exception {var ru=Localization.load(Path.of("src/main/resources"),"ru");assertTrue(ru.getString("button.heal").contains("Вылечить"));assertThrows(IllegalArgumentException.class,()->Localization.load(Path.of("src/main/resources"),"de"));}
    @Test void storageCapacityIsSixPartyPlusFortyFivePc(){var p=new Profile(UUID.randomUUID());for(int n=0;n<51;n++)p.creatures.add(mon(n));p.normalizeActive();p.validate();assertEquals(-1,p.freeSlot());p.creatures.add(mon(51));assertThrows(IllegalArgumentException.class,p::validate);}
    @Test void regularBallChanceUsesHealthLevelAndImmunities(){var c=mon(0);double full=CatchRules.calculate(s.species().get(c.species),c,s.balls().get("poke"),s.catchPolicy()).chance();c.hp=1;assertTrue(CatchRules.calculate(s.species().get(c.species),c,s.balls().get("poke"),s.catchPolicy()).chance()>full);c.hp=0;assertEquals(0,CatchRules.calculate(s.species().get(c.species),c,s.balls().get("poke"),s.catchPolicy()).chance());}
    @Test void captureReplayCannotDuplicateOwnershipAndKeepsHpPp() throws Exception {try(var db=db(dir.resolve("capture.db"))){UUID owner=UUID.randomUUID(),op=UUID.randomUUID();var p=db.load(owner).get();var c=mon(0);c.hp=7;c.pp.replaceAll((k,v)->2);assertTrue(db.prepare(op,owner,"CAPTURE").get());db.grant(op,p,c,false).get();db.grant(op,p,c,false).get();var saved=db.load(owner).get();assertEquals(1,saved.creatures.size());assertEquals(7,saved.at(0).hp);assertEquals(2,saved.at(0).pp.values().iterator().next());assertEquals(2,saved.dex.get("pikachu"));}}
    @Test void starterKitAndClaimedDeliverySurviveRestart() throws Exception {Path path=dir.resolve("starter.db");UUID owner=UUID.randomUUID(),op=UUID.randomUUID();try(var db=db(path)){var p=db.load(owner).get();db.grant(op,p,mon(0),true,16).get();db.grant(op,p,mon(0),true,16).get();assertNotNull(db.nextDelivery(owner).get());}try(var db=db(path)){assertEquals(1,db.load(owner).get().creatures.size());var delivery=db.nextDelivery(owner).get();assertEquals(16,delivery.amount());assertEquals("poke",delivery.ballType());db.acknowledgeDelivery(delivery.id()).get();assertNull(db.nextDelivery(owner).get());}}
    @Test void victorySaveIsIdempotentAndDoesNotMutateLiveProfile() throws Exception {try(var db=db(dir.resolve("victory.db"))){var p=db.load(UUID.randomUUID()).get();p.creatures.add(mon(0));p.normalizeActive();p=db.save(p).get();var rewarded=p.copy();Rules.experience(rewarded.at(0),10,10);UUID receipt=UUID.randomUUID();db.saveVictory(rewarded,receipt).get();assertEquals(0,p.at(0).xp);var replay=db.saveVictory(rewarded,receipt).get();assertEquals(10,replay.at(0).xp);assertEquals(p.revision+1,replay.revision);}}
    @Test void staleVictoryDoesNotPartiallyCommit() throws Exception {try(var db=db(dir.resolve("stale.db"))){var p=db.load(UUID.randomUUID()).get();p.creatures.add(mon(0));p.normalizeActive();p=db.save(p).get();var stale=p.copy();db.save(p).get();stale.at(0).xp=7;UUID receipt=UUID.randomUUID();assertThrows(ExecutionException.class,()->db.saveVictory(stale,receipt).get());assertEquals(0,db.load(p.owner).get().at(0).xp);assertNull(db.operationStatus(receipt).get());}}
    @Test void premiumDatabaseIsRejectedBeforeSchemaIsChanged() throws Exception {Path path=dir.resolve("premium.db");Class.forName("org.sqlite.JDBC");try(var c=DriverManager.getConnection("jdbc:sqlite:"+path);var q=c.createStatement()){q.execute("PRAGMA user_version=4");q.execute("CREATE TABLE sentinel(value TEXT)");q.execute("INSERT INTO sentinel VALUES('keep')");}try(var db=new Repository(path,s::species,s::moves)){assertThrows(ExecutionException.class,()->db.open().get());}try(var c=DriverManager.getConnection("jdbc:sqlite:"+path);var q=c.createStatement();var r=q.executeQuery("SELECT value FROM sentinel")){assertEquals("keep",r.getString(1));}}
    @Test void writesAnActualUpgradeFixture() throws Exception {Path file=Path.of("build/free-upgrade.db");Files.deleteIfExists(file);UUID owner=UUID.fromString("10000000-0000-0000-0000-000000000001");try(var db=db(file)){var p=db.load(owner).get();var c=mon(50);c.hp=7;c.xp=13;c.pp.replaceAll((k,v)->2);var party=mon(0);party.hp=0;p.creatures.addAll(List.of(party,c));p.active=party.id;p.starter=true;p.healedAt=1234;p.dex.put("pikachu",2);p=db.save(p).get();db.queueBalls(UUID.fromString("20000000-0000-0000-0000-000000000001"),owner,8).get();db.nextDelivery(owner).get();assertEquals(50,db.load(owner).get().creatures.getLast().slot);}}
}
