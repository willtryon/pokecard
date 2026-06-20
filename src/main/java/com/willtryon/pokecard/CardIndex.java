package com.willtryon.pokecard;
import java.util.*;
import dev.brachtendorf.jimagehash.hash.Hash;
import dev.brachtendorf.jimagehash.hashAlgorithms.HashingAlgorithm;
import dev.brachtendorf.jimagehash.hashAlgorithms.PerceptiveHash;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
public class CardIndex{
    private Card [] cardDB;
    public CardIndex(int size, String url) throws SQLException{
        int line = 0;
        int chud = 0;
        int mog = 0;
        int fucked = 0;
        HashingAlgorithm hasher = new PerceptiveHash(32);
        cardDB = new Card[size];
        try(Connection conn = DriverManager.getConnection(url);
            Statement st = conn.createStatement(); 
            ResultSet rs = st.executeQuery("SELECT cardId, name, expName, expCardNumber, rarity FROM cards");){
            while (rs.next()){
                String cardId = rs.getString("cardId");
                String folder = rs.getString("expName").replace(" ", "-");
                Path img = Path.of("/config","projects","pokedata","images", "cards", folder, cardId.replace("/", "-")+".jpg");
                if (Files.exists(img)){
                    String address = img.toString();
                    try{
                        File victim = new File(address);
                        try{
                            cardDB[line] = new Card(cardId, img, hasher.hash(victim));
                        }catch(IllegalArgumentException e){
                            System.out.println("twin would have gotten FUCKED"); fucked++;
                        }
                        System.out.println("HIT: "+line); mog++;
                    }catch(IOException e){
                        System.out.println("An exception has occured.");
                        chud++;
                    }
                }
                else{
                    System.out.println("LOSER * "+line+" XD");
                    cardDB[line] = new Card(cardId, img, null);
                    chud++;
                }
                line++;
            }
        }
        System.out.println("Mogs: " +mog+"\nChuds: "+chud+"\nFucks: "+fucked+"\nOut of: "+line);
        double result = ((double) mog / (chud+fucked))*10;
        System.out.println(result+"% passed.");
    }

    public void randomBullshit(int args){
        int factor = args/10;
        System.out.println(factor);
        for(int i = 0; i < 10; i ++){
            System.out.println("Entry "+i);
            System.out.println(cardDB[i].toString());
            //double comp = (cardDB[i].getBinaryHash()).normalizedHammingDistance(cardDB[i+1].getBinaryHash());
            //System.out.println(comp);
        }
        //System.out.println(cardDB[2870].toString());
        //System.out.println(cardDB[17979].toString());
        //System.out.println(cardDB[18038].toString());
    }

}