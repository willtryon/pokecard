package com.willtryon.pokecard;

public class InvalidVersionException extends RuntimeException{
    public InvalidVersionException(String message){
        super(message);
    }
}
