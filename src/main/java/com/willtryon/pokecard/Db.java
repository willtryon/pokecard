package com.willtryon.pokecard;

import org.sqlite.SQLiteConfig;
import java.nio.file.Path;
import java.sql.*;

public final class Db implements AutoCloseable{

    private final Connection catalog;
    private final Connection prices;
    private final Connection user;

    public Db(Path catalogPath, Path pricesPath, Path userPath)throws SQLException {
        this.catalog = openReadOnly(catalogPath);
        this.prices = openReadOnly(pricesPath);
        this.user = openWriteOnly(userPath);
    }

    private static Connection openReadOnly(Path path) throws SQLException {
        SQLiteConfig config = new SQLiteConfig();
        config.setReadOnly(true);
        // No journal_mode on a read-only handle: applying PRAGMA journal_mode=WAL tries to
        // write the DB header and throws SQLITE_READONLY. A read-only connection reads fine
        // regardless of the file's journal mode, so WAL here buys nothing.
        config.setBusyTimeout(5000);
        return DriverManager.getConnection("jdbc:sqlite:" + path, config.toProperties());
    }

    private static Connection openWriteOnly(Path path) throws SQLException {
        SQLiteConfig config = new SQLiteConfig();
        config.setJournalMode(SQLiteConfig.JournalMode.WAL);
        config.setSynchronous(SQLiteConfig.SynchronousMode.NORMAL);
        config.enforceForeignKeys(true);
        config.setBusyTimeout(5000);
        return DriverManager.getConnection("jdbc:sqlite:" + path, config.toProperties());
    }

    public Connection getCatalog() {
        return catalog;
    }

    public Connection getPrices() {
        return prices;
    }

    public Connection getUser() {
        return user;
    }



    @Override
    public void close() throws Exception {
        try{
            catalog.close();
        }finally{
            try {
                prices.close();
            }finally{
                user.close();
            }
        }
    }
}