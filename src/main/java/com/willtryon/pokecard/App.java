/*
pokecard
by willtryon
version 0.1.0
this build is from june 20th, 2026.
*/

package com.willtryon.pokecard;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Scanner;

import org.sqlite.core.DB;

public class App {
    public static int size = 0;
    //will make config or .env files later, project still in proof of concept phase.
    public static void main(String[] args) throws Exception {
        try (Scanner in = new Scanner(System.in)) {
            Config config = new Config(Path.of("/config/projects/pokecard/pokecard.properties"));

            Path dbPath    = config.require(Config.DB_PATH,    "Path to data.sqlite",         Files::isRegularFile, in);
            Path imagesDir = config.require(Config.IMAGES_DIR, "Path to images/cards folder", Files::isDirectory,   in);
            Path compareDir = config.require(Config.COMPARE_DIR, "Path to images to compare to", Files::isDirectory, in);
            String url = "jdbc:sqlite:" + dbPath;
            try (Connection conn = DriverManager.getConnection(url);
                Statement st = conn.createStatement();
                ResultSet rs = st.executeQuery("SELECT COUNT(*) AS n FROM cards")) {
                System.out.println("Connected to Database.");
                if (rs.next()) {
                    size = rs.getInt("n");
                    System.out.println("Cards in database: " + size);
                    CardIndex cardDB = new CardIndex(size, url, imagesDir);
                    cardDB.test(size);
                    cardDB.testHash(compareDir);
                    cardDB.compareHash(compareDir);
                }
            }
        }
        System.out.println("Done.");
    }
}