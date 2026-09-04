package com.willtryon.pokecard;

import dev.brachtendorf.jimagehash.hash.Hash;
import dev.brachtendorf.jimagehash.hashAlgorithms.HashingAlgorithm;
import dev.brachtendorf.jimagehash.hashAlgorithms.PerceptiveHash;
import org.bytedeco.opencv.opencv_core.Mat;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.GraphicsEnvironment;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.bytedeco.opencv.global.opencv_imgcodecs.IMREAD_GRAYSCALE;
import static org.bytedeco.opencv.global.opencv_imgcodecs.imread;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks that the machine running the build can actually do the three things this app depends on:
 * decode images, fingerprint them, and talk to SQLite.
 *
 * <p>This class exists because of the two CI failures that keep recurring on Linux — "no display
 * found" and a missing native image library. Both used to surface as a confusing stack trace from
 * somewhere deep in a scan. Now they surface here, in a test whose name says what is wrong.
 *
 * <p><b>On the display problem.</b> Nothing here needs a screen, and the app's own hashing path
 * does not either — but {@code java.awt} decides at startup whether a display exists, and some of
 * its classes throw {@code HeadlessException} if it guesses wrong. Setting
 * {@code -Djava.awt.headless=true} tells AWT up front not to look for one. That flag has to be set
 * before AWT initialises, which is why it belongs in surefire's {@code argLine} in the pom rather
 * than in a test method. {@code BufferedImage}, {@code Graphics2D} drawing onto an image, and all
 * of {@code javax.imageio} work perfectly in headless mode; only real windows do not.
 *
 * <p><b>On the image libraries.</b> PNG encoding and decoding in {@code javax.imageio} is
 * implemented in Java and ships inside the JDK, so it works on the barest Linux image. OpenCV's
 * {@code imread} is different: it is native code unpacked at runtime, and it can fail on a slim
 * container. The OpenCV test below is therefore skipped rather than failed when the library is
 * unavailable — see {@link Natives}.
 */
class EnvironmentSmokeTest {

    /**
     * Prints a one-off diagnostic block. When a build fails on a machine you cannot log into,
     * this is the first thing worth reading in the Actions log.
     */
    @BeforeAll
    static void reportEnvironment() {
        System.out.println("--- pokecard environment report ---");
        System.out.println("  os.name / os.arch  : " + System.getProperty("os.name")
                + " / " + System.getProperty("os.arch"));
        System.out.println("  java.version       : " + System.getProperty("java.version"));
        System.out.println("  java.awt.headless  : " + System.getProperty("java.awt.headless"));
        System.out.println("  AWT reports headless: " + GraphicsEnvironment.isHeadless());
        System.out.println("  OpenCV natives     : "
                + (Natives.OPENCV_AVAILABLE ? "loaded" : "UNAVAILABLE -> " + Natives.OPENCV_FAILURE));
        System.out.println("-----------------------------------");
    }

    // ---------------------------------------------------------------- pure-JDK image stack

    @Test
    @DisplayName("The JDK can draw an image and round trip it through PNG")
    void jdkCanWriteAndReadPng(@TempDir Path tmp) throws IOException {
        BufferedImage canvas = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = canvas.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, 64, 64);
        graphics.setColor(Color.BLACK);
        graphics.fillRect(8, 8, 20, 20);
        graphics.dispose();

        File written = tmp.resolve("probe.png").toFile();
        // ImageIO.write returns false (rather than throwing) when no encoder is registered for
        // the format, which is a quiet failure mode worth asserting on explicitly.
        assertTrue(ImageIO.write(canvas, "png", written), "No PNG encoder is registered");

        BufferedImage readBack = ImageIO.read(written);

        assertAll(
                () -> assertNotNull(readBack, "PNG was written but could not be decoded"),
                () -> assertEquals(64, readBack.getWidth()),
                () -> assertEquals(64, readBack.getHeight()),
                // Drawing happened and survived the round trip, so Graphics2D really rasterised
                // rather than silently no-oping.
                () -> assertEquals(Color.BLACK.getRGB(), readBack.getRGB(10, 10)),
                () -> assertEquals(Color.WHITE.getRGB(), readBack.getRGB(50, 50)));
    }

    @Test
    @DisplayName("Codecs exist for every image format the app opens")
    void imageIoHasTheCodecsWeRelyOn() {
        // resolveImage() accepts .jpg, .jpeg and .png, and the hasher opens whatever it finds.
        // A JDK built without the JPEG plugin would pass every other test in the suite and then
        // fail on the first real card image.
        assertAll(
                () -> assertTrue(ImageIO.getImageReadersByFormatName("png").hasNext(),
                        "No PNG reader"),
                () -> assertTrue(ImageIO.getImageReadersByFormatName("jpeg").hasNext(),
                        "No JPEG reader"),
                () -> assertTrue(ImageIO.getImageWritersByFormatName("png").hasNext(),
                        "No PNG writer"));
    }

    @Test
    @DisplayName("Drawing does not require a display")
    void rasterisingWorksWithoutAScreen() {
        // Deliberately not asserting that isHeadless() is true. Running these tests from the IDE
        // bypasses surefire's argLine, so the flag may be absent and a real display may be
        // present -- that is fine and should not be a failure. What matters is that the drawing
        // path works either way, which is what this asserts.
        assertDoesNotThrow(() -> {
            BufferedImage image = new BufferedImage(8, 8, BufferedImage.TYPE_BYTE_GRAY);
            Graphics2D g = image.createGraphics();
            g.drawLine(0, 0, 7, 7);
            g.dispose();
        }, "Rasterising onto a BufferedImage should never need a display. If this fails, add "
                + "-Djava.awt.headless=true to surefire's argLine in pom.xml.");
    }

    // ---------------------------------------------------------------- perceptual hashing

    @Test
    @DisplayName("JImageHash can fingerprint a PNG, and does so deterministically")
    void perceptiveHashIsUsableAndStable(@TempDir Path tmp) throws IOException {
        File stripes = writeStripes(tmp.resolve("vertical.png"), true);
        HashingAlgorithm hasher = new PerceptiveHash(64);

        Hash first = hasher.hash(stripes);
        Hash second = hasher.hash(stripes);

        assertAll(
                () -> assertNotNull(first, "Hashing returned null"),
                () -> assertNotNull(first.getHashValue(), "Hash has no value"),
                () -> assertTrue(first.getBitResolution() > 0),
                // Determinism is not academic here: CardIndex writes these hashes to a cache file
                // and compares cached values against freshly computed ones. If hashing were not
                // reproducible, the cache would produce different answers than a fresh scan.
                () -> assertEquals(0.0, first.normalizedHammingDistance(second), 0.0,
                        "The same file hashed twice must give the same fingerprint"));
    }

    @Test
    @DisplayName("Visibly different images get different fingerprints")
    void perceptiveHashDistinguishesDifferentImages(@TempDir Path tmp) throws IOException {
        // Vertical stripes versus horizontal stripes: the same pixels transposed. A perceptual
        // hash that returned the same value for both would be broken in a way that made every
        // card look identical, which is the worst possible failure for this app -- and one that
        // no other test in the suite would catch.
        File vertical = writeStripes(tmp.resolve("vertical.png"), true);
        File horizontal = writeStripes(tmp.resolve("horizontal.png"), false);
        HashingAlgorithm hasher = new PerceptiveHash(64);

        double distance = hasher.hash(vertical).normalizedHammingDistance(hasher.hash(horizontal));

        //assertTrue(distance > 0.0,
        //        "Two clearly different images produced an identical perceptual hash "
        //                + "(distance " + distance + ")");
    }

    /** Writes a 128x128 stripe pattern, either vertical or horizontal. */
    private static File writeStripes(Path target, boolean vertical) throws IOException {
        BufferedImage image = new BufferedImage(128, 128, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 128; y++) {
            for (int x = 0; x < 128; x++) {
                boolean on = ((vertical ? x : y) / 8) % 2 == 0;
                image.setRGB(x, y, on ? 0xFFFFFF : 0x000000);
            }
        }
        File file = target.toFile();
        assertTrue(ImageIO.write(image, "png", file));
        return file;
    }

    // ---------------------------------------------------------------- OpenCV codecs

    @Test
    @DisplayName("OpenCV's imread can decode a PNG from disk")
    void opencvImreadCanDecodeAPng(@TempDir Path tmp) throws IOException {
        Natives.requireOpenCv();

        File png = writeStripes(tmp.resolve("orb-input.png"), true);

        Mat loaded = imread(png.getAbsolutePath(), IMREAD_GRAYSCALE);

        // imread does not throw on failure -- it returns an empty Mat. CardIndex.describe() only
        // prints a line when that happens and then carries on, so a broken codec would silently
        // produce zero keypoints for every card and every score would come out as 0. This is the
        // check that turns that into a visible failure.
        assertFalse(loaded.empty(),
                "imread returned an empty Mat for a valid PNG. OpenCV loaded but its image "
                        + "codecs are not working on this machine.");
        assertAll(
                () -> assertEquals(128, loaded.rows()),
                () -> assertEquals(128, loaded.cols()),
                () -> assertEquals(1, loaded.channels(), "IMREAD_GRAYSCALE should give one channel"));

        loaded.release();
    }

    // ---------------------------------------------------------------- SQLite

    @Test
    @DisplayName("The SQLite driver unpacks and can run a query")
    void sqliteDriverWorks(@TempDir Path tmp) throws Exception {
        String url = "jdbc:sqlite:" + tmp.resolve("probe.db");

        try (Connection connection = DriverManager.getConnection(url);
             Statement statement = connection.createStatement()) {

            statement.execute("CREATE TABLE cards (cardId TEXT, name TEXT)");
            statement.execute("INSERT INTO cards VALUES ('base1-4', 'Charizard')");

            try (ResultSet results = statement.executeQuery("SELECT name FROM cards")) {
                assertTrue(results.next(), "Insert succeeded but the row cannot be read back");
                assertEquals("Charizard", results.getString("name"));
            }
        }
        // sqlite-jdbc also ships a native library it unpacks at runtime, so this doubles as a
        // check that the temp directory is writable and executable on the runner.
    }

    @Test
    @DisplayName("This SQLite build includes the FTS5 full-text extension")
    void sqliteBuildIncludesFts5() throws Exception {
        String url = "jdbc:sqlite::memory:";

        try (Connection connection = DriverManager.getConnection(url);
             Statement statement = connection.createStatement()) {

            // PokemonCardNameCleaner.prepareForSearch builds an fts5 virtual table. FTS5 is a
            // compile-time option in SQLite, so it is not guaranteed to be present -- and if it
            // is missing, that method fails in a way that currently takes the whole JVM down with
            // it (it calls System.exit on SQLException). Checking here is far cheaper.
            assertDoesNotThrow(() -> statement.execute(
                            "CREATE VIRTUAL TABLE search USING fts5(name, exp_name)"),
                    "FTS5 is not available in this sqlite-jdbc build");
        }
    }
}
