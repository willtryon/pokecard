package com.willtryon.pokecard;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Tests for {@link CardVersion} and {@link Category}, the two enums that translate between the
 * database's spelling of a value and the app's.
 *
 * <p>Both have a {@code fromXxx(String)} factory that falls back to a default instead of throwing.
 * That is good for robustness — a surprise value in the database will not crash a scan — but it
 * also means a mapping bug is completely silent. Every card just quietly becomes NORMAL or
 * UNREMARKABLE. Tests are the only way to notice.
 *
 * <p>Some of the assertions below describe behaviour that is arguably wrong. They are written
 * against what the code actually does today, so the build stays green and so that the day someone
 * fixes the mapping, the failing test tells them exactly which callers were depending on the old
 * behaviour. A test that asserts a bug is a bookmark, not an endorsement.
 */
class EnumMappingTest {

    // ---------------------------------------------------------------- CardVersion

    @ParameterizedTest
    @EnumSource(CardVersion.class)
    @DisplayName("Every CardVersion round trips through its own dbValue()")
    void cardVersionRoundTripsThroughDbValue(CardVersion version) {
        // @ParameterizedTest + @EnumSource runs this method once per enum constant, and each run
        // is reported separately. Add a fourth version later and it is covered automatically --
        // no test edit needed.
        assertSame(version, CardVersion.fromDb(version.dbValue()),
                () -> version + " does not survive a round trip through the database value '"
                        + version.dbValue() + "'");
    }

    @Test
    @DisplayName("A null or unknown database value falls back to NORMAL")
    void cardVersionFallsBackForBadInput() {
        // rs.getString() returns null for a NULL column, and this method is called on data
        // scraped from the web, so both cases happen in practice.
        assertAll(
                () -> assertSame(CardVersion.NORMAL, CardVersion.fromDb(null)),
                () -> assertSame(CardVersion.NORMAL, CardVersion.fromDb("")),
                () -> assertSame(CardVersion.NORMAL, CardVersion.fromDb("Etched Foil")));
    }

    @Test
    @DisplayName("fromDb is case-sensitive and does not trim, despite appearances")
    void cardVersionMatchingIsStricterThanItLooks() {
        // fromDb contains a nested check: an outer `dbValue.equals(s)` and an inner
        // `dbValue.equalsIgnoreCase(s.trim())`. The outer test is the strict one, so the inner
        // one can never add a match -- it is unreachable in the sense that it never changes the
        // answer. Anything differing in case or padding therefore silently becomes NORMAL.
        assertAll(
                () -> assertSame(CardVersion.HOLOFOIL, CardVersion.fromDb("Holofoil")),
                () -> assertSame(CardVersion.NORMAL, CardVersion.fromDb("holofoil")),
                () -> assertSame(CardVersion.NORMAL, CardVersion.fromDb("HOLOFOIL")),
                () -> assertSame(CardVersion.NORMAL, CardVersion.fromDb("  Holofoil  ")));
    }

    @Test
    @DisplayName("toString() gives the display label, dbValue() gives the database spelling")
    void cardVersionLabelAndDbValueAreDifferentThings() {
        // The constructor parameters are named (name, dbValue) but assigned label = name, so the
        // first argument in the enum declaration is the label and the second is the database
        // value. Easy to read backwards; this test makes the actual pairing explicit.
        assertAll(
                () -> assertEquals("REVERSE HOLOFOIL", CardVersion.REVERSE_HOLOFOIL.toString()),
                () -> assertEquals("Reverse holofoil", CardVersion.REVERSE_HOLOFOIL.dbValue()),
                () -> assertEquals("NORMAL", CardVersion.NORMAL.toString()),
                () -> assertEquals("Normal", CardVersion.NORMAL.dbValue()));
    }

    // ---------------------------------------------------------------- Category

    @ParameterizedTest
    @EnumSource(Category.class)
    @DisplayName("Every Category round trips through its own dbValue()")
    void categoryRoundTripsThroughDbValue(Category category) {
        assertSame(category, Category.fromCatDb(category.dbValue()));
    }

    @Test
    @DisplayName("A null or unknown category falls back to UNREMARK")
    void categoryFallsBackForBadInput() {
        assertAll(
                () -> assertSame(Category.UNREMARK, Category.fromCatDb(null)),
                () -> assertSame(Category.UNREMARK, Category.fromCatDb("")),
                () -> assertSame(Category.UNREMARK, Category.fromCatDb("Legendary")));
    }

    @Test
    @DisplayName("The category strings the app itself writes do NOT map back to their enum")
    void categoryStringsWrittenByTheAppDoNotRoundTrip() {
        // This is the mapping bug worth knowing about.
        //
        // CardImportsIndex.setBestMatches assigns categories with the uppercase label:
        //     i.setCat("ULTRA") / "HIGH" / "MID" / "UNREMARK"
        // but fromCatDb matches on dbValue, which is title case: "Ultra" / "High" / "Mid" /
        // "Unremarkable". So every value the app writes reads back as UNREMARK, and because
        // fromCatDb falls back silently rather than throwing, nothing ever complains.
        //
        // Only the last of these four assertions looks "correct", and it is correct by accident:
        // "UNREMARK" does not match either, it just happens to fall back to UNREMARK.
        assertAll(
                () -> assertSame(Category.UNREMARK, Category.fromCatDb("ULTRA"),
                        "written by setBestMatches, but only 'Ultra' maps to ULTRA"),
                () -> assertSame(Category.UNREMARK, Category.fromCatDb("HIGH")),
                () -> assertSame(Category.UNREMARK, Category.fromCatDb("MID")),
                () -> assertSame(Category.UNREMARK, Category.fromCatDb("UNREMARK")));

        // And for contrast, the spellings that do work:
        assertAll(
                () -> assertSame(Category.ULTRA, Category.fromCatDb("Ultra")),
                () -> assertSame(Category.HIGH, Category.fromCatDb("High")),
                () -> assertSame(Category.MID, Category.fromCatDb("Mid")),
                () -> assertSame(Category.UNREMARK, Category.fromCatDb("Unremarkable")));
    }

    @Test
    @DisplayName("Category has no toString() override, so it prints the constant name")
    void categoryToStringIsTheConstantName() {
        // CardVersion overrides toString() to return the label; Category does not. If a Category
        // ever reaches a UI label or a CSV cell directly, this is what shows up.
        assertEquals("ULTRA", Category.ULTRA.toString());
        assertEquals("Ultra", Category.ULTRA.dbValue());
        assertNotNull(Category.valueOf("HIGH"));
    }
}
