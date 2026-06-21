/*
pokecard
by willtryon
version 0.1.0
this build is from june 20th, 2026.
*/

package com.willtryon.pokecard;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

public class App {
    public static int size = 0;
    //will make config or .env files later, project still in proof of concept phase.
    public static void main(String[] args) throws Exception {
        String dbPath = (args.length > 0)
                ? args[0]
                : "/config/projects/pokedata/databases/data.sqlite";

        final String url = "jdbc:sqlite:" + dbPath;
        System.out.println("Opening " + url);

        try (Connection conn = DriverManager.getConnection(url);
            Statement st = conn.createStatement();
            ResultSet rs = st.executeQuery("SELECT COUNT(*) AS n FROM cards")) {
            System.out.println("Connected to Database.");
            if (rs.next()) {
                size = rs.getInt("n");
                System.out.println("Cards in database: " + size);
                CardIndex cardDB = new CardIndex(size, url);
                cardDB.test(size);
                cardDB.compareHash();
            }
        }
        System.out.println("Done.");
    }
}