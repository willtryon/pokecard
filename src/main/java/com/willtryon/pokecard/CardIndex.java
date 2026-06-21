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
    /*Approach so far is the query sql db and dump it's contents for every hit to a new Card obj, wiich is stored
    in an array of cards...*/
    public CardIndex(int size, String url) throws SQLException, FileNotFoundException{
        int line = 0;
        double order = 0;
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
        HashingAlgorithm hasher = new PerceptiveHash(64);
        cardDB = new Card[size];
        try(Connection conn = DriverManager.getConnection(url);
            Statement st = conn.createStatement(); 
            ResultSet rs = st.executeQuery("SELECT cardId, name, expName, expCardNumber, rarity FROM cards");){
            long startTime = System.currentTimeMillis();
            System.out.println("Now generating hashes for the database...");
            while (rs.next()){
                String cardId = rs.getString("cardId");
                String folder = rs.getString("expName").replace(" ", "-");
                Path img = Path.of("/config","projects","pokedata","images", "cards", folder, cardId.replace("/", "-")+".jpg");
                System.out.print("\033[2A\033[K");
                System.out.println("Now generating hash for "+cardId+"...");
                long elapsed = System.currentTimeMillis()- startTime;
                long hours = elapsed/3600000;
                long minutes = (elapsed%3600000)/60000;
                long seconds = (elapsed%60000)/1000;
                String clock = String.format("%02d:%02d:%02d", hours, minutes, seconds);
                String percent = String.format("%.0f", order = ((double)line/size)*100);
                System.out.println("\033[KPassed: " +passed+"\tFailed: "+failed+"\tCorrupt: "+corrupt+"\t"+percent+"%\t"+clock+"\t("+line+"/"+size+")");
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
                    pw.println("File "+cardId+" cannot be found by the program.\nLocation: "+img);
                    cardDB[line] = new Card(cardId, img, null);
                    failed++;
                }
                line++;
            }
        }
        System.out.println("\n\nPassed: " +passed+"\nFailed: "+failed+"\nCorrupt: "+corrupt+"\nOut of: "+line+"");
        double result = ((double) passed / size)*100;
        System.out.println(result+"% passed.\n\n");
        pw.close();
    }
    //shallow test of Card & cardIndex init..
    public void test(int args)throws NullPointerException{
        int factor = args/10;
        System.out.println(factor);
        for(int i = 0; i < cardDB.length; i += 3000){
            if (cardDB[i] != null) {
                System.out.println("Entry "+i);
                System.out.println(cardDB[i].toString());
            }
        }
    }

    public void compareHash() throws IOException {
        /*Some card objects will have null hash vars if a file is not found or not accepted by the program,
        or will not even exist in the cardDB at all if the image is corrupt, which leads to a null value
        @ cardDB[index]. This for block checks for null values and adds them to an array list that only parses
        hashes so the program doesn't crash when it finds a null Card obj.*/
        List<Card> hashed = new ArrayList<>();
        for (int c = 0; c < cardDB.length; c++){
            if (cardDB[c] != null && cardDB[c].getBinaryHash() != null) {
                hashed.add(cardDB[c]);
            }
        }
        System.out.println("\n\nComparing " + hashed.size() + " hashed cards...\n");

        long pairCount = 0;
        double record = Double.MAX_VALUE;
        String recordHolderA = "", recordHolderB = "";
        long startTime = System.currentTimeMillis();
        //Won't be much use for this porgram's purpose, but nice way to test all valid hashes work.
        try (PrintWriter pw = new PrintWriter("hashes.txt")) {
            for (int i = 0; i < hashed.size(); i++) {
                Hash first = hashed.get(i).getBinaryHash();
                for (int j = i + 1; j < hashed.size(); j++) {
                    double comp = first.normalizedHammingDistance(hashed.get(j).getBinaryHash());
                    if (comp < record) {
                        record = comp;
                        recordHolderA = hashed.get(i).getCardID();
                        recordHolderB = hashed.get(j).getCardID();
                        pw.println(record + "\t" + recordHolderA + " vs " + recordHolderB);
                    }
                    pairCount++;
                }
                if (i % 500 == 0) {
                    System.out.println(i + "/" + hashed.size() + "  (" + pairCount + " comparisons...)");
                }   
            }
        }
    long ms = System.currentTimeMillis() - startTime;
    System.out.println("\nDone: " + pairCount + " comparisons in " + ms + " ms");
    System.out.println("\nClosest pair: " + recordHolderA + " vs " + recordHolderB + " @ " + record);
    //VERY basic hash comp test for image outside of db...
    record = Double.MAX_VALUE;
    File image = new File("/config/projects/pokecard/src/main/resources/image.jpg");
    HashingAlgorithm hasher = new PerceptiveHash(64);
    Hash test = hasher.hash(image);
    for(int i = 0; i < hashed.size(); i++){
        double comp = test.normalizedHammingDistance(hashed.get(i).getBinaryHash());
        if (comp < record) {
            record = comp;
            recordHolderA = hashed.get(i).toString();
        }
    }
    System.out.println("\nUploaded image appears to be closest to "+recordHolderA+".");
    }
}