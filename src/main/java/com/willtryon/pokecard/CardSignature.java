package com.willtryon.pokecard;

import java.nio.file.Path;

import org.bytedeco.opencv.opencv_core.Mat;

import dev.brachtendorf.jimagehash.hash.Hash;

public class CardSignature{
    private String cardID;
    private Path img;
    private Hash hash;
    private Mat mat;
    
    public CardSignature(String cardID, Path img, Hash hash, Mat mat){
        this.cardID = cardID;
        this.img = img;
        this.hash = hash;
        this.mat = mat;
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

    public Mat getMatData(){
        return mat;
    }

    public String toString(){
        return "CardID = " + cardID + "\t\t\tPath to image = " + img.toString();
    }


}