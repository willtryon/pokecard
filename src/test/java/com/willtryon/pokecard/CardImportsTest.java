package com.willtryon.pokecard;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link CardImports}, with an emphasis on the ways it can throw.
 *
 * <p>{@code CardImports} is the record of one scanned image: the pHash winner, the ORB winner, the
 * optional OCR winner, and the full ranked lists behind each. Several of its methods reach into
 * those lists at hard-coded positions or dereference fields that are legitimately null, so a scan
 * against a small or unusual database can crash in here. The point of these tests is to make the
 * boundaries explicit — the ones that are safe stay safe, and the ones that throw are recorded so
 * nobody has to rediscover them from a stack trace in production.
 *
 * <p>Almost everything here is plain Java. The one test that needs real {@link CardSignature}
 * objects calls {@link Natives#requireOpenCv()} first, because {@code CardSignature} holds OpenCV
 * types and touching it loads the native library.
 */
class CardImportsTest {

    private static final CardImports.Match HASH_WINNER =
            new CardImports.Match("base1-4", "/images/cards/Base-Set/base1-4.jpg", 0.05);
    private static final CardImports.Match ORB_WINNER =
            new CardImports.Match("base1-4", "/images/cards/Base-Set/base1-4.jpg", 148.0);
    private static final CardImports.Match OCR_WINNER =
            new CardImports.Match("base1-4", "/images/cards/Base-Set/base1-4.jpg", 100.0);

    /**
     * Builds a CardImports with empty ranking lists.
     *
     * <p>The real constructor takes sixteen arguments, which is unreadable at every call site, so
     * the tests go through helpers instead. Empty lists are not a contrived edge case: the ORB
     * shortlist is capped at 1000 entries but has however many the database actually contains, so
     * a small or freshly built database produces exactly this shape.
     */
    private static CardImports withEmptyRankings(Path queryImage,
                                                 CardImports.Match hash,
                                                 CardImports.Match orb,
                                                 CardImports.Match ocr,
                                                 CardImports.Match best,
                                                 boolean matchOverride) {
        return new CardImports(queryImage, "NORMAL", false, false, matchOverride, -1f, "",
                null, hash, orb, ocr, best,
                List.of(), List.of(), List.of(), List.of());
    }

    private static CardImports plainImport() {
        return withEmptyRankings(Path.of("/scans/card0.png"),
                HASH_WINNER, ORB_WINNER, null, null, false);
    }

    // ---------------------------------------------------------------- the safe parts

    @Test
    @DisplayName("Getters return exactly what was handed to the constructor")
    void gettersReflectConstructorArguments() {
        CardImports imported = plainImport();

        assertAll(
                () -> assertEquals(Path.of("/scans/card0.png"), imported.getQueryImage()),
                () -> assertEquals("NORMAL", imported.getCardVersion()),
                () -> assertFalse(imported.getFirstEdition()),
                () -> assertFalse(imported.getFinal()),
                () -> assertFalse(imported.getMatchOverride()),
                () -> assertEquals(-1f, imported.getPrice()),
                () -> assertSame(HASH_WINNER, imported.getHashWinner()),
                () -> assertSame(ORB_WINNER, imported.getOrbWinner()));
    }

    @Test
    @DisplayName("Empty ranking lists report size zero rather than throwing")
    void recordSizesAreSafeOnEmptyLists() {
        CardImports imported = plainImport();

        assertAll(
                () -> assertEquals(0, imported.getRecordSize()),
                () -> assertEquals(0, imported.getRecordSize2()));
    }

    @Test
    @DisplayName("hasOcr() only reports true for a Match with a real card id")
    void hasOcrDistinguishesNullFromPlaceholder() {
        // compareOne() seeds every import with a placeholder `new Match(null, null, 100.0)`
        // rather than leaving the field null, so "is there an OCR result" cannot be a plain null
        // check. Getting this wrong would make setBestMatches pick a card id of null for
        // every single import.
        CardImports noOcrField = withEmptyRankings(Path.of("a.png"),
                HASH_WINNER, ORB_WINNER, null, null, false);
        CardImports placeholderOcr = withEmptyRankings(Path.of("a.png"),
                HASH_WINNER, ORB_WINNER, new CardImports.Match(null, null, 100.0), null, false);
        CardImports realOcr = withEmptyRankings(Path.of("a.png"),
                HASH_WINNER, ORB_WINNER, OCR_WINNER, null, false);

        assertAll(
                () -> assertFalse(noOcrField.hasOcr(), "null field"),
                () -> assertFalse(placeholderOcr.hasOcr(), "placeholder Match with a null id"),
                () -> assertTrue(realOcr.hasOcr(), "Match carrying a real card id"));
    }

    @Test
    @DisplayName("bestMatch() prefers an override, then OCR, then ORB")
    void bestMatchFollowsItsPrecedenceOrder() {
        CardImports.Match manualPick = new CardImports.Match("swsh4-25", "/images/x.jpg", 1.0);

        CardImports overridden = withEmptyRankings(Path.of("a.png"),
                HASH_WINNER, ORB_WINNER, OCR_WINNER, manualPick, true);
        CardImports withOcr = withEmptyRankings(Path.of("a.png"),
                HASH_WINNER, ORB_WINNER, OCR_WINNER, null, false);
        CardImports orbOnly = withEmptyRankings(Path.of("a.png"),
                HASH_WINNER, ORB_WINNER, null, null, false);

        assertAll(
                () -> assertSame(manualPick, overridden.bestMatch(),
                        "A user override should beat everything else"),
                () -> assertSame(OCR_WINNER, withOcr.bestMatch(),
                        "OCR is more trustworthy than image matching when present"),
                () -> assertSame(ORB_WINNER, orbOnly.bestMatch(),
                        "ORB is the fallback"));
    }

    @Test
    @DisplayName("An override flag with no override value falls through to ORB")
    void overrideFlagWithoutAValueFallsThrough() {
        CardImports imported = withEmptyRankings(Path.of("a.png"),
                HASH_WINNER, ORB_WINNER, null, null, true);

        // bestMatch() guards with `matchOverride && bestMatch != null`, so this is safe.
        //
        // Note that CardImportsIndex.setBestMatches is NOT as careful: it skips assigning a best
        // match when matchOverride is true, then immediately calls getBestMatch().cardID(). With
        // this exact object -- override on, best match never set -- that line throws
        // NullPointerException. Marking an import as overridden in the UI before a best match has
        // been computed would reproduce it.
        assertSame(ORB_WINNER, imported.bestMatch());
    }

    @Test
    @DisplayName("howLowIsHash() returns -1 when the ORB winner is absent from the hash ranking")
    void howLowIsHashReturnsMinusOneWhenNotFound() {
        CardImports imported = plainImport();

        // -1 is the documented "not found" answer, and scan() checks for it. Returning 0 here
        // instead would make the caller print a bogus match.
        assertEquals(-1, imported.howLowIsHash());
    }

    @Test
    @DisplayName("setters mutate the fields they claim to")
    void settersWork() {
        CardImports imported = plainImport();

        imported.setCardVersion("HOLOFOIL");
        imported.setFirstEdition(true);
        imported.setFinal(true);
        imported.setMatchOverride(true);
        imported.setPrice(12.5f);
        imported.setCat("ULTRA");
        imported.setOcrWinner(OCR_WINNER);

        assertAll(
                () -> assertEquals("HOLOFOIL", imported.getCardVersion()),
                () -> assertTrue(imported.getFirstEdition()),
                () -> assertTrue(imported.getFinal()),
                () -> assertTrue(imported.getMatchOverride()),
                () -> assertEquals(12.5f, imported.getPrice()),
                () -> assertEquals("ULTRA", imported.getCat()),
                () -> assertSame(OCR_WINNER, imported.getOcrWinner()));
    }

    @Test
    @DisplayName("selectedProperty() is a live JavaFX property and needs no display")
    void selectedPropertyWorksHeadless() {
        CardImports imported = plainImport();

        imported.selectedProperty().set(true);

        // Worth one test on its own. CardImports imports javafx.beans.property, which makes it
        // look like a class that needs a GUI. It does not: properties live in javafx.base, which
        // is plain Java with no toolkit and no window. Only javafx.graphics and javafx.controls
        // need a display, so this class is safe to unit test on a headless CI runner.
        assertTrue(imported.selectedProperty().get());
    }

    // ---------------------------------------------------------------- the crashes

    @Test
    @DisplayName("getARecordScore throws on an empty ranking instead of returning null")
    void getARecordScoreThrowsWhenTheRankingIsEmpty() {
        CardImports imported = plainImport();

        // scan() calls getARecordScore(0, "orb") and getARecordScore(1, "orb") to decide whether
        // an image is worth sending to OCR. Against a database with fewer than two cached cards,
        // that line throws before the null check on the next line ever matters.
        assertAll(
                () -> assertThrows(IndexOutOfBoundsException.class,
                        () -> imported.getARecordScore(0, "hash")),
                () -> assertThrows(IndexOutOfBoundsException.class,
                        () -> imported.getARecordScore(1, "orb")),
                () -> assertThrows(IndexOutOfBoundsException.class,
                        () -> imported.getARecordRecord(0, "orb")));
    }

    @Test
    @DisplayName("An unrecognised ranking name silently reads the ORB list")
    void unknownRankingNameFallsThroughToOrb() {
        CardImports imported = plainImport();

        // getARecordScore compares the argument to "hash" and treats literally anything else as
        // the ORB list -- including a typo like "orbb", "Hash", or "". A misspelling here reads
        // the wrong ranking rather than failing, which is the kind of bug that survives for
        // months. An enum parameter would make it impossible.
        assertAll(
                () -> assertThrows(IndexOutOfBoundsException.class,
                        () -> imported.getARecordScore(0, "Hash")),
                () -> assertThrows(IndexOutOfBoundsException.class,
                        () -> imported.getARecordScore(0, "nonsense")),
                // And a null name throws NPE from equals(), not IndexOutOfBounds.
                () -> assertThrows(NullPointerException.class,
                        () -> imported.getARecordScore(0, null)));
    }

    @Test
    @DisplayName("howLowIsHash() throws when there is no ORB winner")
    void howLowIsHashThrowsWithoutAnOrbWinner() {
        CardImports imported = withEmptyRankings(Path.of("a.png"),
                HASH_WINNER, null, null, null, false);

        // The method starts with `orbMatch.cardID()` and never null-checks. compareOne always
        // supplies an ORB winner, so this cannot happen on the scan path today -- but an import
        // restored from a session file can have a null ORB match, because readMatch() returns
        // null for an empty card id.
        assertThrows(NullPointerException.class, imported::howLowIsHash);
    }

    @Test
    @DisplayName("toCsvRows() throws when the ORB ranking has fewer than three entries")
    void toCsvRowsNeedsAtLeastThreeOrbResults() {
        CardImports imported = plainImport();

        // toCsvRows hard-codes recordRecord2.get(1) and .get(2). Anything shorter throws, and
        // this runs inside scanImports right after a scan completes -- so the scan work is done
        // and then thrown away at the CSV step. Worth a bounds check in the production code.
        assertThrows(IndexOutOfBoundsException.class, imported::toCsvRows);
    }

    @Test
    @DisplayName("toCsvRows() throws on a null query image path")
    void toCsvRowsThrowsOnNullQueryImage() {
        CardImports imported = withEmptyRankings(null, HASH_WINNER, ORB_WINNER, null, null, false);

        // The very first line is `img.toString()`. A session file written with an empty query path
        // reloads as null (see readImportsFromDisk), so this is reachable after a restart.
        assertThrows(NullPointerException.class, imported::toCsvRows);
    }

    @Test
    @DisplayName("With three ORB results, toCsvRows() produces the expected six-row shape")
    void toCsvRowsHappyPath() {
        // This is the one test that needs real CardSignature objects, and CardSignature carries
        // OpenCV types, so the native library has to be loadable.
        Natives.requireOpenCv();

        List<CardSignature> orbRanking = List.of(
                new CardSignature("base1-4", Path.of("/images/base1-4.jpg"), null, null, null),
                new CardSignature("base1-5", Path.of("/images/base1-5.jpg"), null, null, null),
                new CardSignature("base1-6", Path.of("/images/base1-6.jpg"), null, null, null));
        List<Double> orbScores = List.of(148.0, 96.0, 71.0);

        CardImports imported = new CardImports(Path.of("/scans/card0.png"), "NORMAL",
                false, false, false, -1f, "", null,
                HASH_WINNER, ORB_WINNER, null, null,
                List.of(0.05), List.of(orbRanking.get(0)), orbScores, orbRanking);

        String[][] rows = imported.toCsvRows();

        assertAll(
                () -> assertEquals(6, rows.length, "Six rows: hash, blank, orb, rank2, rank3, blank"),
                () -> assertEquals(0, rows[1].length, "Row 1 is a deliberate blank separator"),
                () -> assertEquals(0, rows[5].length, "Row 5 is a deliberate blank separator"),
                () -> assertEquals("/scans/card0.png", rows[0][0]),
                () -> assertEquals("base1-4", rows[0][1]),
                // Rows 3 and 4 report ORB ranks 2 and 3. Rank 1 is already in row 2 as the winner,
                // which is why the code starts at index 1 rather than 0.
                () -> assertEquals("base1-5", rows[3][0]),
                () -> assertEquals("96.0", rows[3][2]),
                () -> assertEquals("base1-6", rows[4][0]),
                () -> assertEquals("71.0", rows[4][2]),
                // Row 0 has four columns and rows 3-4 have three, while csvOutput writes an
                // eight-column header. The CSV is therefore ragged by design; asserting the widths
                // here means a future reader can see that was known, not accidental.
                () -> assertEquals(4, rows[0].length),
                () -> assertEquals(3, rows[3].length));
    }
}
