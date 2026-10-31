package ru.vanillamon.domain;
public record Move(String id, Element type, double power, int uses, int speedBonus) {
    public Move(String id,Element type,int power,int uses) {this(id,type,power,uses,0);}
}
