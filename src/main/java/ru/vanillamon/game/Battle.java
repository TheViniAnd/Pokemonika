package ru.vanillamon.game;
import ru.vanillamon.world.Actor;
import java.util.UUID;

public final class Battle {
    public final UUID id=UUID.randomUUID(), player;
    public final Actor wild;
    public Actor ally;
    public boolean resolving, ended;
    public int turns;
    public long due;
    public Runnable next;
    public Battle(UUID player,Actor wild,Actor ally) { this.player=player;this.wild=wild;this.ally=ally; }
}
