package ru.vanillamon.domain;
public enum Element {
    NORMAL, FIRE, WATER, GRASS, ELECTRIC, ICE, FIGHTING, POISON, GROUND, FLYING, PSYCHIC, BUG, ROCK, GHOST, DRAGON;
    /** Red/Blue split: the move's type determines which offensive and defensive stat it uses. */
    public boolean special() { return switch(this) {case FIRE,WATER,GRASS,ELECTRIC,ICE,PSYCHIC,DRAGON -> true; default -> false;}; }
}
