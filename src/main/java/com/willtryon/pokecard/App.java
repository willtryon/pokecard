package com.willtryon.pokecard;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import dev.brachtendorf.jimagehash.hash.*;
//import dev.brachtendorf.jimagehash.hash.Hash;

public class App {
    public static int size = 0;
    public static void main(String[] args) throws Exception {
        // Pass the DB path as the first argument, or edit this default to point
        // at wherever you cloned the pokedata repo.
        
        String dbPath = (args.length > 0)
                ? args[0]
                : "/config/projects/pokedata/databases/data.sqlite";

        final String url = "jdbc:sqlite:" + dbPath;
        System.out.println("Opening " + url);

        // try-with-resources closes the connection/statement/result set for you.
        try (Connection conn = DriverManager.getConnection(url);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) AS n FROM cards")) {

            if (rs.next()) {
                size = rs.getInt("n");
                System.out.println("Cards in database: " + size);
                CardIndex cardDB = new CardIndex(size, url);
                cardDB.randomBullshit(size);

            }
        }

        System.out.println("Success — JDBC + SQLite are wired up correctly.");
    }
}