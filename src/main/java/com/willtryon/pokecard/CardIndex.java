package com.willtryon.pokecard;
import java.util.*;
import dev.brachtendorf.jimagehash.*;
import dev.brachtendorf.jimagehash.hash.Hash;
import dev.brachtendorf.jimagehash.hashAlgorithms.AverageColorHash;
import dev.brachtendorf.jimagehash.hashAlgorithms.AverageHash;
import dev.brachtendorf.jimagehash.hashAlgorithms.HashingAlgorithm;
import dev.brachtendorf.jimagehash.hashAlgorithms.PerceptiveHash;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
public class CardIndex{
    private Card [] cardDB;
    public CardIndex(int size, String url) throws SQLException, FileNotFoundException{
        int line = 0;
        int failed = 0;
        int passed = 0;
        int corrupt = 0;
        File file = new File("log.txt"); 
        PrintWriter pw = new PrintWriter(file);
        try{
            if(file.createNewFile()){
                System.out.println("log.txt created.\n\n");
            }
            else{
                System.out.println("File exists, skipping...\n\n");
            }
        }catch(IOException e){
            System.out.println("IO exception. Try again.");
        }
        HashingAlgorithm hasher = new AverageColorHash(32);
        cardDB = new Card[size];
        try(Connection conn = DriverManager.getConnection(url);
            Statement st = conn.createStatement(); 
            ResultSet rs = st.executeQuery("SELECT cardId, name, expName, expCardNumber, rarity FROM cards");){
            System.out.println("Now generating hashes for the database...");
            while (rs.next()){
                String cardId = rs.getString("cardId");
                String folder = rs.getString("expName").replace(" ", "-");
                Path img = Path.of("/config","projects","pokedata","images", "cards", folder, cardId.replace("/", "-")+".jpg");
                //System.out.print("\033[F\r\033[F\r");
                System.out.print("\033[2A\033[K");
                System.out.println("Now generating hash for "+cardId+"...");
                System.out.println("\033[KPassed: " +passed+"\tFailed: "+failed+"\tCorrupt: "+corrupt+"\t\t\t\t("+line+"/"+size+")");
                if (Files.exists(img)){
                    String address = img.toString();
                    try{
                        File victim = new File(address);
                        try{
                            cardDB[line] = new Card(cardId, img, hasher.hash(victim));
                        }catch(IllegalArgumentException e){
							//System.out.println("this file is corrupt."); corrupt++;
                            pw.println("File "+cardId+" apeears to he corrupt.");
                            corrupt++;
                        }
                        passed++;
                    }catch(IOException e){
                        //System.out.println("An exception has occured.");
                        pw.println("An unknown exception occured when hashing "+cardId);
                        failed++;
                    }
                }
                else{
                    //System.out.println("LOSER * "+line+" XD");
                    pw.println("File "+cardId+" cannot be found by the program.");
                    cardDB[line] = new Card(cardId, img, null);
                    failed++;
                }
                line++;
            }
        }
        System.out.println("Passed: " +passed+"\nFailed: "+failed+"\nCorrupt: "+corrupt+"\nOut of: "+line);
        double result = ((double) passed / (size))*100;
        System.out.println(result+"% passed.");
        pw.close();
    }

    public void test(int args)throws NullPointerException{
        int factor = args/10;
        System.out.println(factor);
        for(int i = 0; i < 10; i ++){
            System.out.println("Entry "+i);
            System.out.println(cardDB[i].toString());
            double comp = (cardDB[i].getBinaryHash()).normalizedHammingDistance(cardDB[i+5].getBinaryHash());
            System.out.println(comp);
            //double comp = (cardDB[i].getBinaryHash()).normalizedHammingDistance(cardDB[i+1].getBinaryHash());
            //System.out.println(comp);
        }
        //System.out.println(cardDB[2870].toString());
        //System.out.println(cardDB[17979].toString());
        //System.out.println(cardDB[18038].toString());
    }

    public void compareHash()throws NullPointerException, IOException{
        long pairCount = 0;
        long startTime = System.currentTimeMillis();
        double record = Double.MAX_VALUE;
        String recordHolderA = "";
        String recordHolderB = "";
        File file = new File("hashes.txt");
        PrintWriter pw = new PrintWriter(file);
         try{
            if(file.createNewFile()){
                System.out.println("hashes.txt created.\n\n");
            }
            else{
                System.out.println("File exists, skipping...\n\n");
            }
        }catch(IOException e){
            System.out.println("IO exception. Try again.");
        }
        System.out.println("Starting hash analysis...\n");
        for (int i = 0; i < cardDB.length; i++) {
            for (int j = i + 1; j < cardDB.length; j++) {
                System.out.print("\033[2A");
                if(cardDB[i].getBinaryHash() == null || cardDB[j].getBinaryHash() == null){
                    continue;
                }
                System.out.println("\033[KComparing"+cardDB[i].getCardID()+" c;and "+cardDB[j].getCardID()+"...");
                double comp = (cardDB[i].getBinaryHash()).normalizedHammingDistance(cardDB[j].getBinaryHash());
                if(comp < record){
                    pw.println("New record is "+comp+ ", set by "+recordHolderA+" and "+recordHolderB+". The previous record was "+record+".");
                    record = comp;
                    recordHolderA = cardDB[i].getCardID();
                    recordHolderB = cardDB[j].getCardID();
                }
                System.out.println("\033[KCurrent record: "+ record +"Held by: "+recordHolderA+" and "+recordHolderB+"Performed "+pairCount+"operations...");
                pairCount++;
            }
        }

        long endTime = System.currentTimeMillis();
    }

}