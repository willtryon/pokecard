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
        HashingAlgorithm hasher = new PerceptiveHash(32);
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
                            passed++;
                        }catch(IllegalArgumentException e){
							//System.out.println("this file is corrupt."); corrupt++;
                            pw.println("File "+cardId+" apeears to he corrupt.");
                            corrupt++;
                        }
                    }catch(IOException e){
                        //System.out.println("An exception has occured.");
                        pw.println("An unknown exception occured when hashing "+cardId);
                        failed++;
                    }
                }
                else{
                    //System.out.println("LOSER * "+line+" XD");
                    pw.println("File "+cardId+" cannot be found by the program.\nLocation: "+img);
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

    public void compareHash() throws IOException {
    // Keep only cards that actually have a hash — this skips the missing
    // AND corrupt entries in one shot, so no null can reach the inner loop.
        List<Card> hashed = new ArrayList<>();
        for (Card c : cardDB) {
            if (c != null && c.getBinaryHash() != null) {
                hashed.add(c);
            }
        }
        System.out.println("Comparing " + hashed.size() + " hashed cards...");

        long pairCount = 0;
        double record = Double.MAX_VALUE;
        String recordHolderA = "", recordHolderB = "";
        long startTime = System.currentTimeMillis();

        try (PrintWriter pw = new PrintWriter("hashes.txt")) {
            for (int i = 0; i < hashed.size(); i++) {
                Hash hi = hashed.get(i).getBinaryHash();   // hoist out of inner loop
                for (int j = i + 1; j < hashed.size(); j++) {
                    double comp = hi.normalizedHammingDistance(hashed.get(j).getBinaryHash());
                    if (comp < record) {
                        record = comp;
                        recordHolderA = hashed.get(i).getCardID();
                        recordHolderB = hashed.get(j).getCardID();
                        pw.println(record + "\t" + recordHolderA + " vs " + recordHolderB);
                    }
                    pairCount++;
                }
                if (i % 500 == 0) {   // progress per outer card, not per pair
                    System.out.println(i + "/" + hashed.size() + "  (" + pairCount + " pairs)");
                }   
            }
        }

    long ms = System.currentTimeMillis() - startTime;
    System.out.println("Done: " + pairCount + " comparisons in " + ms + " ms");
    System.out.println("Closest pair: " + recordHolderA + " vs " + recordHolderB + " @ " + record);
    record = Double.MAX_VALUE;
    File image = new File("/config/projects/pokecard/src/main/resources/image.jpg");
    HashingAlgorithm hasher = new PerceptiveHash(32);
    Hash test = hasher.hash(image);
    for(int i = 0; i < hashed.size(); i++){
        double comp = test.normalizedHammingDistance(hashed.get(i).getBinaryHash());
        if (comp < record) {
            record = comp;
            recordHolderA = hashed.get(i).toString();
        }
    }
    System.out.println("Uploaded image appears to be closest to "+recordHolderA+".");
    }
}