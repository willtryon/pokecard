package com.willtryon.pokecard;
import java.nio.file.Path;
import dev.brachtendorf.jimagehash.hash.Hash;

public class Card{
    //Simple card obj constructor, getters, setters ,etc. Might make this a record, i dunno.
    private String cardID;
    private Path img;
    private Hash hash;

    public Card(String cardID, Path img, Hash hash){
        this.cardID = cardID;
        this.img = img;
        this.hash = hash;
    }

    public String getCardID(){
        return cardID;
    }

    public Path getImgPath(){
        return img;
    }

    public String getStringImgPath(){
        return img.toString();
    }

    public Hash getBinaryHash(){
        return hash;
    }

    public String toString(){
        return "CardID = " + cardID + "\t\t\tPath to image = " + img.toString();
    }
}