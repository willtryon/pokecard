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
 * <p>The {@code fromXxx} methods normalize their input — trimming and matching case-insensitively
 * against every stored form (label, dbValue, and enum name) — so both the uppercase labels the app
 * writes and the title-case dbValues the editor writes resolve to the same constant. The fallback
 * applies only to genuinely unrecognized input.
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
    @DisplayName("fromDb ignores case and surrounding whitespace")
    void cardVersionMatchingIgnoresCaseAndWhitespace() {
        // fromDb now trims and compares case-insensitively against every stored form, so casing
        // and stray padding all resolve to the same constant instead of falling back to NORMAL.
        assertAll(
                () -> assertSame(CardVersion.HOLOFOIL, CardVersion.fromDb("Holofoil")),
                () -> assertSame(CardVersion.HOLOFOIL, CardVersion.fromDb("holofoil")),
                () -> assertSame(CardVersion.HOLOFOIL, CardVersion.fromDb("HOLOFOIL")),
                () -> assertSame(CardVersion.HOLOFOIL, CardVersion.fromDb("  Holofoil  ")));
    }

    @Test
    @DisplayName("fromDb resolves both the label and dbValue forms")
    void cardVersionMatchingHandlesLabelAndDbForms() {
        // App stores the uppercase label ("HOLOFOIL"); the editor stores the dbValue ("Holofoil").
        // Both — and stray casing/whitespace — must resolve to the same constant.
        assertAll(
                () -> assertEquals(CardVersion.HOLOFOIL, CardVersion.fromDb("HOLOFOIL")),
                () -> assertEquals(CardVersion.HOLOFOIL, CardVersion.fromDb("Holofoil")),
                () -> assertEquals(CardVersion.HOLOFOIL, CardVersion.fromDb("holofoil"))
        );
    }

    @Test
    void categoryStringsWrittenByTheAppRoundTrip() {
        // setBestMatches writes the label form; fromCatDb must map it back.
        assertAll(
                () -> assertEquals(Category.ULTRA, Category.fromCatDb("ULTRA"),
                        "label written by setBestMatches must map back to ULTRA"),
                () -> assertEquals(Category.HIGH,  Category.fromCatDb("HIGH")),
                () -> assertEquals(Category.MID,   Category.fromCatDb("MID"))
        );
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
    @DisplayName("Category has no toString() override, so it prints the constant name")
    void categoryToStringIsTheConstantName() {
        // CardVersion overrides toString() to return the label; Category does not. If a Category
        // ever reaches a UI label or a CSV cell directly, this is what shows up.
        assertEquals("ULTRA", Category.ULTRA.toString());
        assertEquals("Ultra", Category.ULTRA.dbValue());
        assertNotNull(Category.valueOf("HIGH"));
    }
}
