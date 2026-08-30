package com.willtryon.pokecard;

import org.sqlite.SQLiteConfig;

import java.nio.file.Path;
import java.sql.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
public final class CardSearchRepo implements AutoCloseable {

    public record CardHit(String cardId, String name, String expName,
                          String expCardNumber, String rarity, Path img) {

        //Text for the "Set" column.
        public String setLabel() {
            if (expName == null) return "";
            return expCardNumber == null || expCardNumber.isBlank()
                    ? expName
                    : expName + " #" + expCardNumber;
        }
    }

    //without this I am begging for oom
    private static final int LIMIT = 100;

    private static final String COLS =
            "c.cardId AS cardId, c.name AS name, c.expName AS expName, " +
                    "c.expCardNumber AS expCardNumber, c.rarity AS rarity";

    private final Connection conn;
    private final boolean ftsAvailable;
    private final Map<String, CardSignature> byId;

    public CardSearchRepo(Path dbPath, CardIndex index) throws SQLException {
        // read only so the db can't get corrupted by an opp which is read only by design.
        SQLiteConfig cfg = new SQLiteConfig();
        cfg.setReadOnly(true);
        this.conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath, cfg.toProperties());

        this.ftsAvailable = detectFts();

        //indexes every child cardDB object when this searchDB object is initialized.
        this.byId = new HashMap<>();
        for (int i = 0; i < index.getCardIndexSize(); i++) {
            CardSignature sig = index.getCardSignature(i);
            if (sig != null) byId.put(sig.getCardID(), sig);
        }
    }

    //this code logic starts a search, non javafx thread ONLY
    public List<CardHit> search(String raw) throws SQLException {
        String q = raw == null ? "" : raw.trim();
        if (q.isEmpty()) return List.of();
        return ftsAvailable ? searchFts(q) : searchLike(q);
    }

    private List<CardHit> searchFts(String q) throws SQLException {
        String sql = """
                SELECT %s
                FROM cards_fts f
                JOIN cards c ON c.rowid = f.rowid
                WHERE cards_fts MATCH ?
                ORDER BY bm25(cards_fts)
                LIMIT ?
                """.formatted(COLS);
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, toMatchExpr(q));
            ps.setInt(2, LIMIT);
            return read(ps);
        }
    }

    //if for some reason the prepareForSearch method doesn't execute when the application is initializing, this method serves to not break this feature.
    private List<CardHit> searchLike(String q) throws SQLException {
        String sql = """
                SELECT %s
                FROM cards c
                WHERE c.name LIKE ? OR c.expName LIKE ? OR c.cardId LIKE ?
                ORDER BY c.name, c.expName
                LIMIT ?
                """.formatted(COLS);
        String like = "%" + q + "%";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, like);
            ps.setString(2, like);
            ps.setString(3, like);
            ps.setInt(4, LIMIT);
            return read(ps);
        }
    }
    //reads results from sqlite
    private List<CardHit> read(PreparedStatement ps) throws SQLException {
        List<CardHit> out = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String cardId = rs.getString("cardId");
                CardSignature sig = byId.get(cardId);
                out.add(new CardHit(
                        cardId,
                        rs.getString("name"),
                        rs.getString("expName"),
                        rs.getString("expCardNumber"),
                        rs.getString("rarity"),
                        sig == null ? null : sig.getImgPath()));
            }
        }
        return out;
    }

    /** The already-hashed signature for a hit, so callers can build a {@code CardImports.Match}. */
    public CardSignature signature(String cardId) {
        return byId.get(cardId);
    }

    public boolean isFtsAvailable() {
        return ftsAvailable;
    }

    //FTS5 shenanigans

    //converts user input into strings that can be used with fts
    static String toMatchExpr(String q) {
        StringBuilder sb = new StringBuilder();
        for (String token : q.split("\\s+")) {
            String clean = token.replace("\"", "").replace("*", "");
            if (clean.isBlank()) continue;
            if (!sb.isEmpty()) sb.append(' ');
            sb.append('"').append(clean).append("\"*");
        }
        return sb.toString();
    }

    private boolean detectFts() throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT 1 FROM sqlite_master WHERE type='table' AND name='cards_fts'")) {
            return rs.next();
        }
    }

    @Override
    public void close() throws SQLException {
        conn.close();
    }
}