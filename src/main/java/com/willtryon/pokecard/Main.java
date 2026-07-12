/*
pokecard
by willtryon
version 0.4.0
this build is from july 1st, 2026.
*/

package com.willtryon.pokecard;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Scanner;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class Main {
    public static int size = 0;
    //uses config object to read info from pokeard.properties, so the program can run on different computers without having to mannually
    //code paths to dirs and files manually.
    public static void main(String[] args) throws Exception {
        try (Scanner in = new Scanner(System.in)) {
            Config config = new Config(Path.of("/Users/willtryon/VSCode/PokeImageComp/pokecard/pokecard.properties"));
            Path dbPath    = config.require(Config.DB_PATH,    "Path to data.sqlite",         Files::isRegularFile, in);
            Path imagesDir = config.require(Config.IMAGES_DIR, "Path to images/cards folder", Files::isDirectory,   in);
            Path compareDir = config.require(Config.COMPARE_DIR, "Path to images to compare to", Files::isDirectory, in);
            Path outputDir = config.require(Config.OUTPUT_DIR, "Path to output log files", Files::isDirectory, in);
            Path cacheDir = config.require(Config.CACHE_DIR, "Path to cache directory", Files::isDirectory, in);
            int orbThreads = config.getScanThreads();
            String url = "jdbc:sqlite:" + dbPath;
            try (Connection conn = DriverManager.getConnection(url);
                Statement st = conn.createStatement();
                ResultSet rs = st.executeQuery("SELECT COUNT(*) AS n FROM cards")) {
                System.out.println("Connected to SQL Database.");
                if (rs.next()) {
                    size = rs.getInt("n");
                    System.out.println("Cards in database: " + size);
                    Path cacheFile = cacheDir.resolve("cache.xml");
                    CardIndex cardDB;
                    if(Files.isRegularFile(cacheFile)){
                        System.out.println("Loading cache, please wait...\n");
                        cardDB = new CardIndex(imagesDir, outputDir, cacheDir, orbThreads);
                    }else{
                        System.out.println("Calculating image data, please wait...");
                        cardDB = new CardIndex(size, url, imagesDir, outputDir, cacheDir, orbThreads);
                    }
                    CardImportsIndex importDB = cardDB.newImportsIndex(compareDir, cacheDir);
                    int choice;
                    ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
                    do{
                        System.out.println("\n\nReady. What would you like to do?"+
                                            "\n1. compare images in "+compareDir.toString()+
                                            "\n2. Test pokedata array"+
                                            "\n3. Test hashes (joke)"+
                                            "\n4. Start scheduled scanning for image imports"+
                                            "\n99. Quit");
                        switch(choice = in.nextInt()){
                            case 1 -> scheduler.submit(() -> cardDB.scanImports(importDB));
                            case 2 -> cardDB.test(size);
                            case 3 -> cardDB.testHash();
                            case 4 -> {
                                long minutes = 5;
                                scheduler.scheduleAtFixedRate(() -> {try{cardDB.scanImports(importDB);}catch(Exception e){e.printStackTrace();}}, 0, minutes, TimeUnit.MINUTES);
                                System.out.println("Started auto scan every "+minutes+" minutes");
                            }
                            case 99 ->{ System.out.println("Exiting..."); scheduler.shutdownNow();}
                            default -> System.out.println("Sorry, try again.");
                        }

                    }while (choice != 99);
                    //cardDB.testHash();
                    //cardDB.compareImage(compareDir);
                    
                }
            }
        }
        System.out.println("Done.");
    }

}