package com.willtryon.pokecard;

import org.bytedeco.opencv.opencv_core.KeyPointVector;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_features2d.ORB;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link CardIndex}: filename sanitising, what happens when the cache is missing,
 * and the ORB geometric matcher.
 *
 * <p>These live in package {@code com.willtryon.pokecard} on purpose. {@code sanitizeWinPath} is
 * package-private, and a test in the same package can reach it without anything being made
 * public just for the sake of testing. Java requires the directory layout to mirror the package,
 * which is why the file sits under {@code src/test/java/com/willtryon/pokecard}.
 */
class CardIndexTest {

    /** The nine characters Windows refuses in a filename. Control characters are handled too. */
    private static final String WINDOWS_ILLEGAL = "<>:\"/\\|?*";

    // ---------------------------------------------------------------- sanitizeWinPath
    //
    // Card ids like "Type: Null---sv6-1" contain a colon. On Windows, Path.resolve() throws
    // InvalidPathException on a colon, so resolveImage() would blow up before it ever touched
    // the disk. These tests pin down the transform that prevents that.

    @Test
    @DisplayName("A colon collapses to a single dash")
    void colonBecomesDash() {
        assertEquals("Type- Null", CardIndex.sanitizeWinPath("Type: Null", false));
    }

    @Test
    @DisplayName("A run of dashes and illegal characters collapses to one dash")
    void mixedRunCollapsesToOneDash() {
        // "a", then the run "-:-" which contains an illegal char so the whole run becomes "-",
        // then "b".
        assertEquals("a-b", CardIndex.sanitizeWinPath("a-:-b", false));
        assertEquals("a-b", CardIndex.sanitizeWinPath("a///b", false));
    }

    @Test
    @DisplayName("A run of plain dashes is left alone")
    void plainDashRunsSurvive() {
        // This matters: "---" is the app's own separator between card name and set code, so
        // collapsing it would break every filename lookup.
        assertEquals("charizard---base1-4", CardIndex.sanitizeWinPath("charizard---base1-4", false));
    }

    @Test
    @DisplayName("A real card id survives with its separators intact")
    void realCardIdIsSanitisedButStillRecognisable() {
        assertEquals("Type- Null---sv6-1.jpg",
                CardIndex.sanitizeWinPath("Type: Null---sv6-1.jpg", true));
    }

    @Test
    @DisplayName("Trailing dots and spaces are stripped, because Windows silently drops them")
    void trailingDotsAndSpacesAreStripped() {
        assertAll(
                () -> assertEquals("Hello", CardIndex.sanitizeWinPath("Hello. ", false)),
                () -> assertEquals("Hello", CardIndex.sanitizeWinPath("Hello...", false)),
                () -> assertEquals("Hello", CardIndex.sanitizeWinPath("   Hello", false)));
    }

    @Test
    @DisplayName("A name that sanitises away to nothing becomes an underscore, never empty")
    void emptyResultBecomesUnderscore() {
        // An empty filename would make Path.resolve("") return the parent directory, and the app
        // would then try to read a directory as if it were a JPEG.
        assertAll(
                () -> assertEquals("_", CardIndex.sanitizeWinPath("...", false)),
                () -> assertEquals("_", CardIndex.sanitizeWinPath("   ", false)),
                () -> assertEquals("_", CardIndex.sanitizeWinPath("", false)));
    }

    @Test
    @DisplayName("Windows reserved device names get an underscore prefix, case-insensitively")
    void reservedDeviceNamesArePrefixed() {
        assertAll(
                () -> assertEquals("_CON", CardIndex.sanitizeWinPath("CON", false)),
                () -> assertEquals("_con", CardIndex.sanitizeWinPath("con", false)),
                () -> assertEquals("_NUL.txt", CardIndex.sanitizeWinPath("NUL.txt", true)),
                // "CONTACT" merely starts with CON, so it is fine as-is.
                () -> assertEquals("CONTACT", CardIndex.sanitizeWinPath("CONTACT", false)));
    }

    @Test
    @DisplayName("Illegal characters are scrubbed out of the extension too")
    void extensionIsCleaned() {
        assertAll(
                () -> assertEquals("shot.jpg", CardIndex.sanitizeWinPath("shot.j:pg", true)),
                // Nothing legal left in the extension, so the dot is dropped rather than left
                // dangling as "file." (which Windows would also reject).
                () -> assertEquals("file", CardIndex.sanitizeWinPath("file.::", true)),
                // A leading dot is not an extension separator.
                () -> assertEquals(".gitignore", CardIndex.sanitizeWinPath(".gitignore", true)));
    }

    @Test
    @DisplayName("Control characters are replaced")
    void controlCharactersAreReplaced() {
        assertEquals("a-b", CardIndex.sanitizeWinPath("a\u0001b", false));
    }

    @Test
    @DisplayName("Whatever goes in, the output never contains an illegal character")
    void outputIsAlwaysSafeToResolve() {
        // A property-style test: instead of listing expected outputs one by one, it asserts an
        // invariant that must hold for every input. This is the check that actually protects
        // resolveImage() from InvalidPathException on Windows.
        String[] nastyInputs = {
                "Type: Null", "Farfetch'd", "Ho-Oh", "Mr. Mime", "a<b>c:d\"e/f\\g|h?i*j",
                "NUL", "  ", "...", "Flabebe", "Porygon-Z---xy1-233", "100/108"
        };
        for (String input : nastyInputs) {
            String cleaned = CardIndex.sanitizeWinPath(input + ".jpg", true);
            for (char illegal : WINDOWS_ILLEGAL.toCharArray()) {
                assertEquals(-1, cleaned.indexOf(illegal),
                        () -> "sanitizeWinPath left an illegal character in the result for input '"
                                + input + "' -> '" + cleaned + "'");
            }
            assertFalse(cleaned.isEmpty(), "sanitizeWinPath returned an empty name for " + input);
            // The braces matter: assertDoesNotThrow has both a void-returning and a
            // value-returning overload, and a bare `() -> expression` lambda is ambiguous between
            // the two. Wrapping the call in a block makes it void-compatible.
            assertDoesNotThrow(() -> {
                Path.of("images").resolve(cleaned);
            }, "Result is still not safe to hand to Path.resolve()");
        }
    }

    // ---------------------------------------------------------------- missing cache

    /**
     * Settings pointing entirely at an empty temp directory. Nothing exists yet, which is exactly
     * the state a fresh machine or a CI runner is in.
     */
    private static Config.Settings settingsIn(Path root) {
        return new Config.Settings(
                root.resolve("data.sqlite"),
                root.resolve("images"),
                root.resolve("compare"),
                root.resolve("output"),
                root.resolve("cache"),
                2,
                "",
                "",
                "true");
    }

    @Test
    @DisplayName("A missing cache file degrades to an empty index instead of throwing")
    void missingCacheGivesEmptyIndex(@TempDir Path tmp) {
        Natives.requireOpenCv();

        CardIndex index = new CardIndex(settingsIn(tmp));
        try {
            // readFromDisk() catches IOException and returns a zero-length array. Worth pinning
            // down, because Main only reaches this branch when cache.xml already exists. If that
            // guard is ever relaxed, silently getting zero cards is much harder to debug than a
            // crash would be.
            assertEquals(0, index.getCardIndexSize(),
                    "With no cache on disk the index should be empty");
            assertNull(index.findCardId("base1-4"),
                    "Looking up any id in an empty index should return null, not throw");
        } finally {
            index.shutdown();
        }
    }

    @Test
    @DisplayName("getCardSignature does not bounds-check, so an empty index throws")
    void getCardSignatureThrowsOnEmptyIndex(@TempDir Path tmp) {
        Natives.requireOpenCv();

        CardIndex index = new CardIndex(settingsIn(tmp));
        try {
            // Documenting real behaviour, not endorsing it: getCardSignature(int) indexes the
            // array directly. Any caller that trusts an index without checking
            // getCardIndexSize() first will crash here.
            assertThrows(ArrayIndexOutOfBoundsException.class, () -> index.getCardSignature(0));
        } finally {
            index.shutdown();
        }
    }

    // ---------------------------------------------------------------- geometricMatches

    @Test
    @DisplayName("Null or empty descriptors score zero rather than crashing")
    void geometricMatchesHandlesMissingData(@TempDir Path tmp) {
        Natives.requireOpenCv();

        CardIndex index = new CardIndex(settingsIn(tmp));
        try {
            Mat emptyMat = new Mat();
            KeyPointVector emptyKp = new KeyPointVector();

            // Cards whose image was missing or corrupt are stored with null Mat/KeyPointVector,
            // and cache entries loaded without an ORB file have null descriptors too. Both flow
            // straight into this method during a scan, so the guard clause matters.
            assertAll(
                    () -> assertEquals(0, index.geometricMatches(null, null, null, null)),
                    () -> assertEquals(0, index.geometricMatches(emptyMat, emptyKp, null, null)),
                    () -> assertEquals(0, index.geometricMatches(null, null, emptyMat, emptyKp)),
                    () -> assertEquals(0,
                            index.geometricMatches(emptyMat, emptyKp, emptyMat, emptyKp)));
        } finally {
            index.shutdown();
        }
    }

    @Test
    @DisplayName("An image matched against itself yields inliers")
    void geometricMatchesFindsInliersForIdenticalImages(@TempDir Path tmp) {
        Natives.requireOpenCv();

        CardIndex index = new CardIndex(settingsIn(tmp));
        try {
            Mat image = ORBTest.syntheticCardImage(99L);
            ORB orb = ORB.create();
            KeyPointVector keypoints = new KeyPointVector();
            Mat descriptors = new Mat();
            orb.detectAndCompute(image, new Mat(), keypoints, descriptors);

            int inliers = index.geometricMatches(descriptors, keypoints, descriptors, keypoints);

            // Asserting "> 0" rather than an exact count, on purpose. The exact number depends on
            // the OpenCV build and on RANSAC's internal randomness, so pinning it down would make
            // the test fail on a version bump for no real reason. What must hold is that a
            // perfect match is recognised as a match at all.
            assertTrue(inliers > 0,
                    "An image compared against itself produced no geometric inliers, which would "
                            + "mean ORB matching could never identify any card");

            image.release();
            descriptors.release();
        } finally {
            index.shutdown();
        }
    }

    // ---------------------------------------------------------------- timer

    @Test
    @DisplayName("timer() formats elapsed milliseconds as hh:mm:ss")
    void timerFormatsElapsedTime() {
        long now = System.currentTimeMillis();

        // timer() takes a start time and formats "now minus start", so passing a timestamp in the
        // past is how you ask it about a known interval.
        //
        // The odd 500ms offsets are deliberate. A few milliseconds always pass between capturing
        // `now` and the call, so an exact 5000ms offset sits right on the boundary where the
        // seconds field rolls over. Aiming at the middle of the second gives ~500ms of slack in
        // both directions, which is the difference between a reliable test and one that fails
        // once a month during a GC pause.
        assertAll(
                () -> assertTrue(CardIndex.timer(now).matches("\\d{2}:\\d{2}:\\d{2}"),
                        "Result should always be zero-padded hh:mm:ss"),
                () -> assertEquals("00:00:05", CardIndex.timer(now - 5_500)),
                () -> assertEquals("00:02:00", CardIndex.timer(now - 120_500)),
                () -> assertEquals("01:00:00", CardIndex.timer(now - 3_600_500)));
    }

    @Test
    @DisplayName("timer() is not built for intervals past 99 hours")
    void timerOverflowsPastNinetyNineHours() {
        long now = System.currentTimeMillis();

        // "%02d" is a minimum width, not a maximum, so the hours field simply grows. Harmless in
        // itself, but worth knowing before anyone parses this string assuming a fixed width.
        assertEquals("100:00:00", CardIndex.timer(now - (100L * 3_600_000L + 500L)));
    }
}
