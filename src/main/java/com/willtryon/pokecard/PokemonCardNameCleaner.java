package com.willtryon.pokecard;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PokemonCardNameCleaner {

    // --- Regexes used only by the last-resort strip (step 2c) ---

    private static final Pattern PAREN_TAG = Pattern.compile("\\s*\\([^)]*\\)\\s*");

    private static final Pattern CARD_NUMBER_SUFFIX = Pattern.compile("\\s+-\\s+.*$");
    /**
     * A single trailing TCG mechanic suffix token. Applied repeatedly to peel off
     * runs like " GX", " ex", " VMAX". Only reached as a last resort (after both
     * dictionary steps have failed), so trimming these is always safe here.
     */
    private static final Pattern TCG_SUFFIX =
            Pattern.compile("(?i)\\s+(?:ex|GX|V|VMAX|VSTAR|V-?UNION|BREAK|LV\\.?X|Star|Prime)\\b");

    // --- Pokédex data, loaded once from the DB ---

    /** pokedex.id -> pokedex.name (the authoritative lookup for step 1). */
    private final Map<Integer, String> dexById = new HashMap<>();
    /** All species names, for the exact-match test (step 2a). */
    private final Set<String> dexNames = new HashSet<>();
    /** Parallel lists used for the containment match (step 2b). */
    private final List<String> dexNameList = new ArrayList<>();
    private final List<String> dexNameLower = new ArrayList<>();
    private final List<Pattern> dexNamePattern = new ArrayList<>();
    private final boolean dryRun;
    private final Path dbPath;

    /**
     * A pending name change (collected during the read phase, applied afterward).
     */
        private record Update(long rowid, String newName) {
    }

    public PokemonCardNameCleaner(Path dbPath, boolean dryRun) {
        this.dbPath = dbPath;
        this.dryRun = dryRun;
        String url = "jdbc:sqlite:" + dbPath;
        try (Connection conn = DriverManager.getConnection(url)) {
            run(conn, dryRun);
        } catch (SQLException e) {
            System.err.println("Database error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    /** Loads the Pokédex, computes every needed change, then applies them in one transaction. */
    public void run(Connection conn, boolean dryRun) throws SQLException {
        loadPokedex(conn);
        System.out.println("Loaded " + dexById.size() + " Pokédex entries.");

        List<Update> updates = new ArrayList<>();
        int viaFk = 0, viaHeuristic = 0, viaDict = 0, unchanged = 0, skipped = 0, shown = 0;
        final int SHOW_LIMIT = 25; // sample of changes to print

        // ---- Phase 1: read-only pass — decide the new name for every card. ----
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT rowid, name, pokedex, cardType FROM cards")) {
            while (rs.next()) {
                long rowid = rs.getLong("rowid");
                String name = rs.getString("name");
                if (name == null) { skipped++; continue; }

                // pokedex is INTEGER NULL -> read via getObject to detect null cleanly.
                Object dexObj = rs.getObject("pokedex");
                Integer dexId = null;
                if (dexObj instanceof Number) {
                    dexId = ((Number) dexObj).intValue();
                } else if (dexObj != null) {
                    try { dexId = Integer.valueOf(dexObj.toString().trim()); }
                    catch (NumberFormatException ignore) { /* leave null */ }
                }
                String cardType = rs.getString("cardType");
                boolean untyped = (cardType == null || cardType.trim().isEmpty());

                String newName;
                String branch;
                if (dexId != null && dexById.containsKey(dexId)) {
                    newName = dexById.get(dexId);          // step 1: authoritative
                    branch = "FK";
                } else if ("Pokemon".equals(cardType)) {
                    newName = cleanName(name);             // step 2: full heuristic
                    branch = "HEUR";
                } else if (untyped) {
                    newName = dictionaryMatch(name);       // step 3: confident-only
                    if (newName == null) { skipped++; continue; }
                    branch = "DICT";
                } else {
                    skipped++;                             // step 4: explicit non-Pokémon
                    continue;
                }

                if (newName == null || newName.isEmpty() || newName.equals(name)) {
                    unchanged++;                           // already correct (idempotent no-op)
                    continue;
                }

                if (shown < SHOW_LIMIT) {
                    System.out.printf("  [%-4s] %-50s -> %s%n", branch, quote(name), quote(newName));
                    shown++;
                }

                updates.add(new Update(rowid, newName));
                if ("FK".equals(branch)) viaFk++;
                else if ("HEUR".equals(branch)) viaHeuristic++;
                else viaDict++;
            }
        }

        // ---- Phase 2: write the collected changes in a single transaction. ----
        if (!dryRun && !updates.isEmpty()) {
            boolean prevAuto = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try (PreparedStatement up =
                         conn.prepareStatement("UPDATE cards SET name = ? WHERE rowid = ?")) {
                for (Update u : updates) {
                    up.setString(1, u.newName);
                    up.setLong(2, u.rowid);
                    up.addBatch();
                }
                up.executeBatch();
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(prevAuto);
            }
        }

        System.out.println();
        System.out.println(dryRun ? "DRY RUN (no changes written):" : "Done. Committed changes:");
        System.out.println("  changed:   " + updates.size()
                + "  (Pokédex id: " + viaFk
                + ", Pokémon-card name parse: " + viaHeuristic
                + ", untyped confident match: " + viaDict + ")");
        System.out.println("  unchanged: " + unchanged + "  (already just the species name)");
        System.out.println("  skipped:   " + skipped + "  (non-Pokémon cards, left untouched)");
    }

    /** Populates the Pokédex lookup structures from the `pokedex` table. */
    private void loadPokedex(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT id, name FROM pokedex")) {
            while (rs.next()) {
                int id = rs.getInt("id");
                String name = rs.getString("name");
                if (name == null || name.isEmpty()) continue;
                dexById.put(id, name);
                if (dexNames.add(name)) {            // avoid duplicate patterns
                    dexNameList.add(name);
                    dexNameLower.add(name.toLowerCase(Locale.ROOT));
                    // Whole-"word" boundary: not preceded/followed by a letter or digit,
                    // so internal spaces/hyphens/apostrophes/'.'/':' in species names are fine.
                    dexNamePattern.add(Pattern.compile(
                            "(?<![A-Za-z0-9])" + Pattern.quote(name) + "(?![A-Za-z0-9])",
                            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE));
                }
            }
        }
    }

    /**
     * Confident species match: exact species name (2a), else the leftmost
     * (tie-break: longest) known species name appearing as a whole word inside
     * the string (2b). Returns null if no known species name is found.
     */
    String dictionaryMatch(String cardName) {
        if (dexNames.contains(cardName)) {
            return cardName;
        }
        String lowerCard = cardName.toLowerCase(Locale.ROOT);
        int bestStart = Integer.MAX_VALUE;
        int bestLen = -1;
        String best = null;
        for (int i = 0; i < dexNameList.size(); i++) {
            // Fast pre-filter: containment is necessary for the boundary regex to match.
            if (!lowerCard.contains(dexNameLower.get(i))) continue;
            Matcher m = dexNamePattern.get(i).matcher(cardName);
            if (m.find()) {
                int start = m.start();
                int len = dexNameList.get(i).length();
                if (start < bestStart || (start == bestStart && len > bestLen)) {
                    bestStart = start;
                    bestLen = len;
                    best = dexNameList.get(i);
                }
            }
        }
        return best;
    }

    /**
     * Reduces a raw card name to a species name (step 2). Tries the confident
     * dictionary match first, then falls back to stripping variant/parenthetical
     * tags, a trailing card number, and trailing TCG suffix tokens. Returns the
     * original name if nothing better can be found.
     */
    String cleanName(String cardName) {
        String match = dictionaryMatch(cardName);   // 2a + 2b
        if (match != null) {
            return match;
        }
        // 2c. last resort strip.
        String s = PAREN_TAG.matcher(cardName).replaceAll(" ");
        s = CARD_NUMBER_SUFFIX.matcher(s).replaceAll("");
        String prev;
        do {
            prev = s;
            s = TCG_SUFFIX.matcher(s).replaceAll("").trim();
        } while (!s.equals(prev));
        s = s.replaceAll("\\s+", " ").trim();
        return s.isEmpty() ? cardName : s;
    }

    private static String quote(String s) {
        return "'" + s + "'";
    }
}