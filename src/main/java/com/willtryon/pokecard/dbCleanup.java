package com.willtryon.pokecard;

import java.io.IOException;
import java.nio.file.Files;
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

public final class dbCleanup {

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

    // --- Pokédex data, loaded once from the DB and used to set correct values in the 'name' row ---

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

    public dbCleanup(ScanProgress progress, Path dbPath, Path tcgDb, boolean dryRun) {
        this.dbPath = dbPath;
        this.dryRun = dryRun;
        String url = "jdbc:sqlite:" + dbPath;
        try (Connection conn = DriverManager.getConnection(url)) {
            run(progress, conn, dryRun);
            reconcileIdTcgp(progress, dbPath, tcgDb, dryRun);
            prepareForSearch(progress, conn);
        } catch (SQLException e) {
            System.err.println("Database error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    /** Loads the Pokédex, computes every needed change, then applies them in one transaction. */
    public void run(ScanProgress progress, Connection conn, boolean dryRun) throws SQLException {
        progress.report("Running database tasks... (1/4)", 0.25);
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

    // =====================================================================================
    // idTCGP reconciliation against the TCGCSV catalog (tcg.db)
    // -------------------------------------------------------------------------------------
    // The pokedata scraper assigns each card's idTCGP by fuzzy-matching the expansion name
    // against TCGplayer set names. That match is too loose: sets that share a word
    // ("XY Evolutions" vs "SV: Prismatic Evolutions") get merged, so cards from a sibling
    // set leak in and overwrite idTCGP by collector number. tcg.db (built by tcgdb.py from
    // TCGCSV) groups every product by its real set, so it is the source of truth.
    //
    // For each card this finds the card's TRUE set (via the crosswalk below), matches within
    // that group by collector number, and rewrites idTCGP -- but ONLY when the matched
    // product's name also agrees with the card's name, so a number-only match to a
    // differently-named card is never blindly applied. Everything else is left unchanged and
    // reported. Same read-decide-then-write-in-one-transaction shape as run().
    //
    // Example:
    //   PokemonCardNameCleaner.reconcileIdTcgp(
    //       dataSqlitePath,          // data.sqlite (written)
    //       tcgDbPath,               // tcg.db      (read only)
    //       false,                   // dryRun
    //       false,                   // writeSetFields (also fix expCodeTCGP/expIdTCGP)
    //       Path.of("idtcgp_fix_report.csv"));   // or null for no CSV
    // =====================================================================================

    /** Expansions the auto-matcher mis-picks -> the correct tcg.db group id(s). */
    private static final Map<String, List<Integer>> XWALK_OVERRIDES = Map.ofEntries(
            Map.entry("Evolutions", List.of(1842)),                     // XY - Evolutions (NOT Prismatic)
            Map.entry("Sun & Moon", List.of(1863)),                     // SM Base Set
            Map.entry("Platinum - Arceus", List.of(1391)),              // Arceus (NOT the Platinum base set)
            Map.entry("Base Set", List.of(604)),                        // Unlimited (NOT Shadowless: same #s+names)
            Map.entry("Wizards of the Coast Promos", List.of(1418)),    // WoTC Promo
            Map.entry("HeartGold SoulSilver Promos", List.of(1453)),    // HGSS Promos (not the HS base set)
            Map.entry("Pokemon Rumble", List.of(1433)));                // Rumble

    /** Expansions that legitimately span a main set + a subset with its own numbering. */
    private static final Map<String, List<Integer>> XWALK_MULTIGROUP = Map.ofEntries(
            Map.entry("Lost Origin", List.of(3118, 3172)),
            Map.entry("Astral Radiance", List.of(3040, 3068)),
            Map.entry("Silver Tempest", List.of(3170, 17674)),
            Map.entry("Crown Zenith", List.of(17688, 17689)),
            Map.entry("Brilliant Stars", List.of(2948, 3020)),
            Map.entry("Shining Fates", List.of(2754, 2781)),
            Map.entry("Hidden Fates", List.of(2480, 2594)),
            Map.entry("Celebrations", List.of(2867, 2931)),
            Map.entry("Generations", List.of(1728, 1729)),
            Map.entry("Legendary Treasures", List.of(1409, 1465)));

    /** Expansions with no US TCGplayer set (custom / JP-only) -> leave unmatched. */
    private static final Set<String> XWALK_NONE = Set.of("Pokemon Futsal Promos", "P25 Music");

    private static final Pattern NON_ALNUM = Pattern.compile("[^a-z0-9]+");
    private static final Pattern LEADING_ZEROS = Pattern.compile("(?<![0-9])0+(?=[0-9])");

    /** A catalog product: its TCGplayer id and display name. */
    private record Product(int id, String name) {}

    /** A pending idTCGP change (+ optional set-field values), applied after the read pass. */
    private record IdUpdate(long rowid, int newId, String setAbbr, String setName) {}

    /** Summary counts returned to the caller. */
    public record ReconcileStats(int confirmed, int corrected, int review, int unmatched, int noXwalk) {}

    /** Convenience: idTCGP-only fix, no CSV report. */
    public static ReconcileStats reconcileIdTcgp(ScanProgress progress, Path dataDb, Path tcgDb, boolean dryRun) {
        return reconcileIdTcgp(progress, dataDb, tcgDb, dryRun, false, null);
    }

    /**
     * Repairs data.sqlite's idTCGP against tcg.db.
     *
     * @param progress
     * @param dataDb         path to data.sqlite (updated in place unless dryRun)
     * @param tcgDb          path to tcg.db (opened read-only)
     * @param dryRun         compute + report but write nothing
     * @param writeSetFields also normalize expCodeTCGP/expIdTCGP on corrected rows to the
     *                       matched set (rewrites those two columns to tcg.db's vocabulary,
     *                       e.g. EVO / SVI); idTCGP-only when false
     * @param reportCsv      if non-null, write a CSV of corrected/review/unmatched rows here
     */
    public static ReconcileStats reconcileIdTcgp(ScanProgress progress, Path dataDb, Path tcgDb, boolean dryRun,
                                                 boolean writeSetFields, Path reportCsv) {
        progress.report("Running database tasks... (2/4)", 0.5);
        try (Connection data = DriverManager.getConnection("jdbc:sqlite:" + dataDb);
             Connection tcg = DriverManager.getConnection("jdbc:sqlite:" + tcgDb)) {

            Map<String, List<Integer>> cross = buildCrosswalk(data, tcg);
            Map<String, List<Product>> idx = buildProductIndex(tcg);
            Map<Integer, String[]> setInfo = new HashMap<>();   // group_id -> {abbreviation, name}
            try (Statement st = tcg.createStatement();
                 ResultSet rs = st.executeQuery("SELECT group_id, abbreviation, name FROM sets")) {
                while (rs.next()) {
                    setInfo.put(rs.getInt(1), new String[]{rs.getString(2), rs.getString(3)});
                }
            }

            List<IdUpdate> updates = new ArrayList<>();
            List<String[]> report = new ArrayList<>();
            int confirmed = 0, corrected = 0, review = 0, unmatched = 0, noXwalk = 0, shown = 0;
            final int SHOW_LIMIT = 25;

            // ---- Phase 1: read-only pass — decide the correct idTCGP for every card. ----
            try (Statement st = data.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT rowid, cardId, idTCGP, name, expName, expCardNumber FROM cards")) {
                while (rs.next()) {
                    long rowid = rs.getLong("rowid");
                    String cardId = rs.getString("cardId");
                    String name = rs.getString("name");
                    String expName = rs.getString("expName");
                    String rawNumber = rs.getString("expCardNumber");
                    String cn = cnum(rawNumber);

                    Object idObj = rs.getObject("idTCGP");
                    Integer curId = (idObj instanceof Number) ? ((Number) idObj).intValue() : null;

                    List<Integer> groups = cross.getOrDefault(expName, List.of());
                    if (groups.isEmpty()) { noXwalk++; continue; }

                    // Best name-agreeing product at this collector number, across mapped groups.
                    Product best = null;   double bestDice = -1;   int bestGroup = -1;
                    Product any = null;    double anyDice = -1;     boolean hadCandidate = false;
                    for (int g : groups) {
                        List<Product> ps = idx.get(key(g, cn));
                        if (ps == null) continue;
                        for (Product p : ps) {
                            hadCandidate = true;
                            double dd = dice(name, p.name());
                            if (dd > anyDice) { anyDice = dd; any = p; }
                            if (nameAgree(name, p.name()) && dd > bestDice) {
                                bestDice = dd; best = p; bestGroup = g;
                            }
                        }
                    }

                    if (!hadCandidate) {
                        unmatched++;
                        report.add(new String[]{"unmatched", expName, cardId, name, rawNumber,
                                String.valueOf(curId), "", "", "no product at this number in set"});
                        continue;
                    }
                    if (best == null) {                    // number matched, but no name agrees
                        review++;
                        String other = (any != null) ? any.name() : "";
                        report.add(new String[]{"review", expName, cardId, name, rawNumber,
                                String.valueOf(curId), "", other,
                                "number matches '" + other + "' but name differs"});
                        continue;
                    }
                    if (curId != null && best.id() == curId) { confirmed++; continue; }

                    corrected++;
                    String[] si = setInfo.get(bestGroup);
                    updates.add(new IdUpdate(rowid, best.id(),
                            si != null ? si[0] : null, si != null ? si[1] : null));
                    report.add(new String[]{"corrected", expName, cardId, name, rawNumber,
                            String.valueOf(curId), String.valueOf(best.id()), best.name(), ""});
                    if (shown < SHOW_LIMIT) {
                        System.out.printf("  %-40s #%-6s idTCGP %s -> %d (%s)%n",
                                trunc(name, 40), rawNumber, curId, best.id(), best.name());
                        shown++;
                    }
                }
            }

            // ---- Phase 2: write the collected changes in a single transaction. ----
            if (!dryRun && !updates.isEmpty()) {
                boolean prevAuto = data.getAutoCommit();
                data.setAutoCommit(false);
                String sql = writeSetFields
                        ? "UPDATE cards SET idTCGP = ?, expCodeTCGP = ?, expIdTCGP = ? WHERE rowid = ?"
                        : "UPDATE cards SET idTCGP = ? WHERE rowid = ?";
                try (PreparedStatement up = data.prepareStatement(sql)) {
                    for (IdUpdate u : updates) {
                        if (writeSetFields) {
                            up.setInt(1, u.newId());
                            up.setString(2, u.setAbbr());
                            up.setString(3, u.setName());
                            up.setLong(4, u.rowid());
                        } else {
                            up.setInt(1, u.newId());
                            up.setLong(2, u.rowid());
                        }
                        up.addBatch();
                    }
                    up.executeBatch();
                    data.commit();
                } catch (SQLException e) {
                    data.rollback();
                    throw e;
                } finally {
                    data.setAutoCommit(prevAuto);
                }
            }

            if (reportCsv != null) writeReport(reportCsv, report);

            System.out.println();
            System.out.println(dryRun
                    ? "DRY RUN (no idTCGP changes written):"
                    : "Done. Committed idTCGP changes" + (writeSetFields ? " (+ set fields):" : ":"));
            System.out.println("  confirmed: " + confirmed + "  (already correct)");
            System.out.println("  corrected: " + corrected + "  (idTCGP rewritten, name-verified)");
            System.out.println("  review:    " + review + "  (number matched, name differs — left unchanged)");
            System.out.println("  unmatched: " + unmatched + "  (no product at that number — left unchanged)");
            System.out.println("  no_xwalk:  " + noXwalk + "  (expansion has no TCGplayer set)");
            if (reportCsv != null) {
                System.out.println("  report:    " + reportCsv + "  (" + report.size() + " rows)");
            }
            return new ReconcileStats(confirmed, corrected, review, unmatched, noXwalk);

        } catch (SQLException e) {
            System.err.println("idTCGP reconcile database error: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Builds {expName -> [group_id,...]}. Auto-matches each expansion to the tcg.db group with
     * the best (name similarity, collector-number overlap) score, then applies the manual-review
     * exception maps above.
     */
    private static Map<String, List<Integer>> buildCrosswalk(Connection data, Connection tcg)
            throws SQLException {
        Map<Integer, String> groupName = new HashMap<>();
        Map<Integer, Set<String>> groupNums = new HashMap<>();
        try (Statement st = tcg.createStatement();
             ResultSet rs = st.executeQuery("SELECT group_id, name FROM sets")) {
            while (rs.next()) {
                groupName.put(rs.getInt(1), rs.getString(2));
                groupNums.put(rs.getInt(1), new HashSet<>());
            }
        }
        try (Statement st = tcg.createStatement();
             ResultSet rs = st.executeQuery("SELECT group_id, number FROM products WHERE number IS NOT NULL")) {
            while (rs.next()) {
                Set<String> s = groupNums.get(rs.getInt(1));
                if (s != null) s.add(cnum(rs.getString(2)));
            }
        }

        Map<String, Set<String>> expNums = new HashMap<>();
        try (Statement st = data.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT expName, expCardNumber FROM cards WHERE expCardNumber IS NOT NULL")) {
            while (rs.next()) {
                expNums.computeIfAbsent(rs.getString(1), k -> new HashSet<>()).add(cnum(rs.getString(2)));
            }
        }
        Set<String> expNames = new HashSet<>();
        try (Statement st = data.createStatement();
             ResultSet rs = st.executeQuery("SELECT DISTINCT expName FROM cards")) {
            while (rs.next()) if (rs.getString(1) != null) expNames.add(rs.getString(1));
        }

        Map<String, List<Integer>> cross = new HashMap<>();
        for (String e : expNames) {
            Set<String> en = expNums.getOrDefault(e, Set.of());
            String ea = alnum(e);
            int best = -1;
            double bestScore = -1;
            for (Map.Entry<Integer, String> g : groupName.entrySet()) {
                double ns = dice(e, g.getValue());
                if (!ea.isEmpty() && alnum(g.getValue()).contains(ea)) ns = Math.max(ns, 0.75);
                Set<String> gn = groupNums.get(g.getKey());
                int inter = 0;
                for (String c : en) if (gn.contains(c)) inter++;
                double ov = en.isEmpty() ? 0.0 : (double) inter / en.size();
                double score = ns * 0.7 + ov * 0.3;
                if (score > bestScore) { bestScore = score; best = g.getKey(); }
            }
            cross.put(e, new ArrayList<>(List.of(best)));
        }

        XWALK_OVERRIDES.forEach((e, gs) -> { if (cross.containsKey(e)) cross.put(e, new ArrayList<>(gs)); });
        XWALK_MULTIGROUP.forEach((e, gs) -> { if (cross.containsKey(e)) cross.put(e, new ArrayList<>(gs)); });
        for (String e : XWALK_NONE) if (cross.containsKey(e)) cross.put(e, new ArrayList<>());
        return cross;
    }

    /** Builds {"group_id|cnum" -> [Product,...]} from every catalog product. */
    private static Map<String, List<Product>> buildProductIndex(Connection tcg) throws SQLException {
        Map<String, List<Product>> idx = new HashMap<>();
        try (Statement st = tcg.createStatement();
             ResultSet rs = st.executeQuery("SELECT product_id, group_id, name, number FROM products")) {
            while (rs.next()) {
                String k = key(rs.getInt("group_id"), cnum(rs.getString("number")));
                idx.computeIfAbsent(k, x -> new ArrayList<>())
                        .add(new Product(rs.getInt("product_id"), rs.getString("name")));
            }
        }
        return idx;
    }

    private static void writeReport(Path csv, List<String[]> rows) {
        StringBuilder sb = new StringBuilder(
                "status,expName,cardId,name,number,old_idTCGP,new_idTCGP,matched_name,note\n");
        for (String[] r : rows) {
            for (int i = 0; i < r.length; i++) {
                if (i > 0) sb.append(',');
                sb.append(csvCell(r[i]));
            }
            sb.append('\n');
        }
        try {
            Files.writeString(csv, sb.toString());
        } catch (IOException e) {
            System.err.println("Could not write report " + csv + ": " + e.getMessage());
        }
    }

    // --- small shared string helpers (mirror the Python reconciler) ---

    private static String key(int group, String cn) { return group + "|" + cn; }

    /** Lowercase, keep [a-z0-9] only. */
    private static String alnum(String s) {
        return s == null ? "" : NON_ALNUM.matcher(s.toLowerCase(Locale.ROOT)).replaceAll("");
    }

    /** Sørensen–Dice bigram similarity of two names (0..1). */
    private static double dice(String a, String b) {
        String A = alnum(a), B = alnum(b);
        if (A.isEmpty() || B.isEmpty()) return 0.0;
        if (A.equals(B)) return 1.0;
        Set<String> ba = bigrams(A), bb = bigrams(B);
        if (ba.isEmpty() || bb.isEmpty()) return 0.0;
        int inter = 0;
        for (String g : ba) if (bb.contains(g)) inter++;
        return 2.0 * inter / (ba.size() + bb.size());
    }

    private static Set<String> bigrams(String s) {
        Set<String> b = new HashSet<>();
        for (int i = 0; i + 1 < s.length(); i++) b.add(s.substring(i, i + 2));
        return b;
    }

    /** Normalize a collector number so the two DBs' formats line up:
     *  '035'->'35'  '35/108'->'35'  'TG05'->'tg5'  'SWSH001'->'swsh1'. */
    private static String cnum(String s) {
        if (s == null) return "";
        String x = s.toLowerCase(Locale.ROOT);
        int slash = x.indexOf('/');
        if (slash >= 0) x = x.substring(0, slash);
        x = NON_ALNUM.matcher(x).replaceAll("");
        return LEADING_ZEROS.matcher(x).replaceAll("");
    }

    /** True if a card name and a product name plausibly denote the same card. */
    private static boolean nameAgree(String card, String prod) {
        String A = alnum(card), B = alnum(prod);
        if (A.isEmpty() || B.isEmpty()) return false;
        if (A.contains(B) || B.contains(A)) return true;
        return dice(card, prod) >= 0.55;
    }

    private static String trunc(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n);
    }

    private static String csvCell(String s) {
        if (s == null) return "";
        if (s.indexOf(',') >= 0 || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0) {
            return '"' + s.replace("\"", "\"\"") + '"';
        }
        return s;
    }

    public void prepareForSearch(ScanProgress progress, Connection conn) throws SQLException {
        progress.report("Running database tasks... (3/4)", 0.75);

        try (Statement st = conn.createStatement()) {
            boolean exists;
            try (ResultSet rs = st.executeQuery(
                    "SELECT 1 FROM sqlite_master WHERE type='table' AND name='cards_fts'")) {
                exists = rs.next();
            }

            if (!exists) {
                st.execute("""
                    CREATE VIRTUAL TABLE cards_fts USING fts5(
                        name, expName, expCardNumber, rarity,
                        content='cards',
                        tokenize='unicode61'
                    )""");
            }

            st.executeUpdate("INSERT INTO cards_fts(cards_fts) VALUES('rebuild')");

            try (ResultSet rs = st.executeQuery("SELECT count(*) FROM cards_fts")) {
                System.out.println("cards_fts: indexed " + (rs.next() ? rs.getInt(1) : 0) + " cards.");
            }
        }
    }
}