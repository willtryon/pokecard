/*
pokecard
by willtryon
version 0.4.0
this build is from june 28th, 2026.
*/

package com.willtryon.pokecard;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Scanner;

public class App {
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
                        cardDB = new CardIndex(imagesDir, outputDir, cacheDir);
                    }else{
                        System.out.println("Calculating image data, please wait...");
                        cardDB = new CardIndex(size, url, imagesDir, outputDir, cacheDir);
                    }
                    int choice;
                    //1
                    // 1CardImportsIndex importDB = new CardImportsIndex(compareDir, imagesDir, outputDir,cardDB);
                    do{
                        System.out.println("\n\nReady. What would you like to do?"+
                                            "\n1. compare images in "+compareDir.toString()+
                                            "\n2. Test pokedata array"+
                                            "\n3. Test hashes (joke)"+
                                            "\n99. Quit");
                        switch(choice = in.nextInt()){
                            case 1 -> cardDB.compareImage(compareDir);
                            case 2 -> cardDB.test(size);
                            case 3 -> cardDB.testHash();
                            case 99 -> System.out.println("Exiting...");
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