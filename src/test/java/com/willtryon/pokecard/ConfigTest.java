package com.willtryon.pokecard;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.NoSuchElementException;
import java.util.Scanner;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link Config}, the class that decides where every path in the app points.
 *
 * <p>This is pure Java: no OpenCV, no JavaFX, no display. It runs anywhere, so it needs no
 * {@code Natives} guard.
 *
 * <p>Two JUnit features do the heavy lifting here:
 * <ul>
 *   <li>{@code @TempDir} — JUnit creates a fresh empty directory before the test and deletes it
 *       afterwards. Tests never touch your real {@code ~/.pokecard/pokecard.properties}, and they
 *       cannot interfere with each other or leave rubbish behind on the CI runner.</li>
 *   <li>{@code Scanner} over a plain string — {@code require()} reads answers from a
 *       {@code Scanner}, which in production wraps {@code System.in}. Handing it a
 *       {@code new Scanner("line one\nline two\n")} instead lets us drive the interactive prompt
 *       loop from a test with no human and no stdin involved.</li>
 * </ul>
 */
class ConfigTest {

    @Test
    @DisplayName("A config file that does not exist yet loads as empty rather than throwing")
    void missingFileLoadsAsEmpty(@TempDir Path tmp) throws IOException {
        Config config = new Config(tmp.resolve("does-not-exist.properties"));

        // First-run behaviour. If this threw, the app could never bootstrap itself.
        assertEquals("", config.get(Config.DB_PATH));
        assertFalse(config.isValid(Config.DB_PATH, Files::isRegularFile));
    }

    @Test
    @DisplayName("get() returns an empty string for unknown keys, never null")
    void getNeverReturnsNull(@TempDir Path tmp) throws IOException {
        Config config = new Config(tmp.resolve("cfg.properties"));

        // This is load-bearing. Settings.from() feeds every one of these straight into
        // Path.of(...), and Path.of(null) throws NullPointerException. Returning "" instead is
        // what stops a half-filled config from crashing at startup.
        assertAll(
                () -> assertEquals("", config.get("no.such.key")),
                () -> assertEquals("", config.get(Config.OCR_MODEL)),
                () -> assertEquals("", config.get(Config.EBAY_API_KEY)));
    }

    @Test
    @DisplayName("Values survive a save/reload round trip")
    void valuesRoundTripThroughDisk(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("cfg.properties");
        Path images = Files.createDirectory(tmp.resolve("images"));

        Config first = new Config(file);
        first.set(Config.IMAGES_DIR, images.toString());
        first.set(Config.EBAY_API_KEY, "abc-123");
        first.save();

        Config reloaded = new Config(file);

        // Deliberately going through Config.set/save rather than hand-writing the file: the
        // .properties format treats backslash as an escape character, so a hand-written Windows
        // path would be mangled. Properties.store() escapes correctly, and this test has to pass
        // on the windows-latest runner too.
        assertAll(
                () -> assertEquals(images.toString(), reloaded.get(Config.IMAGES_DIR)),
                () -> assertEquals("abc-123", reloaded.get(Config.EBAY_API_KEY)),
                () -> assertTrue(reloaded.isValid(Config.IMAGES_DIR, Files::isDirectory)));
    }

    @Test
    @DisplayName("get() trims surrounding whitespace")
    void getTrimsWhitespace(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("cfg.properties");
        // A non-path value on purpose, so this stays safe from backslash escaping on Windows.
        Files.writeString(file, "ebay.apiKey=   padded-key   \n");

        Config config = new Config(file);

        assertEquals("padded-key", config.get(Config.EBAY_API_KEY));
    }

    @Test
    @DisplayName("set(key, null) stores an empty string instead of blowing up later")
    void setTolueratesNull(@TempDir Path tmp) throws IOException {
        Config config = new Config(tmp.resolve("cfg.properties"));

        config.set(Config.OCR_MODEL, null);

        // Properties throws NullPointerException on a null value, so Config guards it. Without
        // that guard, any UI control that can be cleared would crash the save.
        assertEquals("", config.get(Config.OCR_MODEL));
    }

    // ---------------------------------------------------------------- getScanThreads
    //
    // This value is handed straight to Executors.newFixedThreadPool(), which throws
    // IllegalArgumentException on anything below 1. So the clamping here is the only thing
    // standing between a typo in a properties file and a crash at startup.

    @Test
    @DisplayName("A blank thread count falls back to a sane value")
    void blankScanThreadsFallsBack(@TempDir Path tmp) throws IOException {
        Config config = new Config(tmp.resolve("cfg.properties"));

        int threads = config.getScanThreads();

        int cores = Runtime.getRuntime().availableProcessors();
        assertTrue(threads >= 1, "Thread count must never be below 1");
        assertTrue(threads <= cores, "Thread count must never exceed the core count");
    }

    @Test
    @DisplayName("Garbage, zero and negative thread counts are all clamped to at least 1")
    void badScanThreadsAreClamped(@TempDir Path tmp) throws IOException {
        Config config = new Config(tmp.resolve("cfg.properties"));

        assertAll(
                () -> {
                    config.set(Config.SCAN_THREADS, "0");
                    assertEquals(1, config.getScanThreads());
                },
                () -> {
                    config.set(Config.SCAN_THREADS, "-8");
                    assertEquals(1, config.getScanThreads());
                },
                () -> {
                    config.set(Config.SCAN_THREADS, "four");
                    assertTrue(config.getScanThreads() >= 1,
                            "Unparseable text should fall back, not throw NumberFormatException");
                });
    }

    @Test
    @DisplayName("An absurdly high thread count is capped at the core count")
    void hugeScanThreadsAreCapped(@TempDir Path tmp) throws IOException {
        Config config = new Config(tmp.resolve("cfg.properties"));
        config.set(Config.SCAN_THREADS, "9999");

        assertEquals(Runtime.getRuntime().availableProcessors(), config.getScanThreads());
    }

    // ---------------------------------------------------------------- require

    @Test
    @DisplayName("require() keeps prompting until it gets a valid path, then persists it")
    void requireLoopsUntilTheAnswerIsValid(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("cfg.properties");
        Path realDir = Files.createDirectory(tmp.resolve("images"));
        Config config = new Config(file);

        // Two scripted answers: the first is a path that does not exist, so require() should
        // reject it and ask again; the second is valid.
        Scanner scriptedAnswers = new Scanner("/no/such/directory\n" + realDir + "\n");

        Path result = config.require(Config.IMAGES_DIR, "Path to images", Files::isDirectory,
                scriptedAnswers);

        assertEquals(realDir, result);
        // require() calls save() itself, so the answer should already be on disk.
        assertEquals(realDir.toString(), new Config(file).get(Config.IMAGES_DIR));
    }

    @Test
    @DisplayName("require() accepts an already-valid stored value without prompting")
    void requireDoesNotPromptWhenTheStoredValueIsGood(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("cfg.properties");
        Path realDir = Files.createDirectory(tmp.resolve("images"));
        Config config = new Config(file);
        config.set(Config.IMAGES_DIR, realDir.toString());

        // An empty Scanner has no lines at all. If require() tried to read one it would throw
        // NoSuchElementException, so this test passing proves it never prompted.
        Path result = config.require(Config.IMAGES_DIR, "Path to images", Files::isDirectory,
                new Scanner(""));

        assertEquals(realDir, result);
    }

    @Test
    @DisplayName("require() throws when the input stream runs dry")
    void requireThrowsWhenAnswersRunOut(@TempDir Path tmp) throws IOException {
        Config config = new Config(tmp.resolve("cfg.properties"));

        // A real crash worth knowing about. require() loops "while not valid: read a line", with
        // no end-of-input check. Run the app with stdin closed or redirected from /dev/null (a
        // service, a cron job, a CI step) and it dies here with NoSuchElementException instead of
        // reporting that it needs configuring.
        assertThrows(NoSuchElementException.class,
                () -> config.require(Config.DB_PATH, "Path to data.sqlite", Files::isRegularFile,
                        new Scanner("")));
    }

    @Test
    @DisplayName("save() surfaces an unwritable location as an IOException")
    void saveFailsLoudlyWhenTheDirectoryIsMissing(@TempDir Path tmp) throws IOException {
        // "nested" was never created, so the file cannot be opened for writing.
        Config config = new Config(tmp.resolve("nested").resolve("cfg.properties"));
        config.set(Config.DB_PATH, "/somewhere");

        assertThrows(IOException.class, config::save);
    }

    // ---------------------------------------------------------------- Settings

    @Test
    @DisplayName("Settings.from() on an empty config yields empty paths rather than failing")
    void settingsFromEmptyConfigProducesEmptyPaths(@TempDir Path tmp) throws IOException {
        Config config = new Config(tmp.resolve("cfg.properties"));

        Config.Settings settings = Config.Settings.from(config);

        // Path.of("") is legal: it is the empty relative path. So a config missing every key
        // produces a Settings object that looks fine and then silently resolves everything
        // relative to the working directory -- imagesDir().resolve("cards/base1") becomes
        // "cards/base1" rather than an absolute path.
        //
        // Main avoids this by calling require() for all five paths first, but anything that
        // builds Settings without going through Main inherits the trap. Asserting it here means
        // that if someone later adds validation, this test fails and points them at the callers
        // that were relying on the lenient behaviour.
        assertAll(
                () -> assertEquals("", settings.dbPath().toString()),
                () -> assertEquals("", settings.imagesDir().toString()),
                () -> assertEquals("", settings.cacheDir().toString()),
                () -> assertFalse(settings.imagesDir().isAbsolute(),
                        "An empty path is relative, so every lookup silently changes meaning "
                                + "with the working directory"),
                () -> assertTrue(settings.scanThreads() >= 1,
                        "scanThreads must stay usable by Executors.newFixedThreadPool"));
    }

    @Test
    @DisplayName("Settings.from() copies every configured value across")
    void settingsFromCopiesEveryValue(@TempDir Path tmp) throws IOException {
        Config config = new Config(tmp.resolve("cfg.properties"));
        config.set(Config.DB_PATH, tmp.resolve("data.sqlite").toString());
        config.set(Config.IMAGES_DIR, tmp.resolve("images").toString());
        config.set(Config.COMPARE_DIR, tmp.resolve("compare").toString());
        config.set(Config.OUTPUT_DIR, tmp.resolve("out").toString());
        config.set(Config.CACHE_DIR, tmp.resolve("cache").toString());
        config.set(Config.SCAN_THREADS, "1");
        config.set(Config.EBAY_API_KEY, "key");
        config.set(Config.OCR_MODEL, "qwen2.5-vl");

        Config.Settings settings = Config.Settings.from(config);

        // Guards against a copy/paste slip in Settings.from -- passing IMAGES_DIR twice and
        // forgetting COMPARE_DIR would be invisible without a test like this.
        assertAll(
                () -> assertEquals(tmp.resolve("data.sqlite"), settings.dbPath()),
                () -> assertEquals(tmp.resolve("images"), settings.imagesDir()),
                () -> assertEquals(tmp.resolve("compare"), settings.compareDir()),
                () -> assertEquals(tmp.resolve("out"), settings.outputDir()),
                () -> assertEquals(tmp.resolve("cache"), settings.cacheDir()),
                () -> assertEquals(1, settings.scanThreads()),
                () -> assertEquals("key", settings.eBayApiKey()),
                () -> assertEquals("qwen2.5-vl", settings.ocrModel()));
    }
}
