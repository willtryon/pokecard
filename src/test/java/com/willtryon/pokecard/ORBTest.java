package com.willtryon.pokecard;

import org.bytedeco.javacpp.indexer.UByteIndexer;
import org.bytedeco.opencv.opencv_core.KeyPointVector;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_features2d.ORB;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.bytedeco.opencv.global.opencv_core.CV_8UC1;
import static org.bytedeco.opencv.global.opencv_core.NORM_HAMMING;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks that the ORB feature detector really works on this machine.
 *
 * <p>Every image the app fingerprints goes through {@code ORB.create()} followed by
 * {@code detectAndCompute}. If OpenCV's native library is present but subtly broken, the old
 * version of this test still passed, because it created an ORB and then asserted nothing. These
 * tests exercise the detector end to end instead.
 *
 * <p>Note what is deliberately absent: no file is read from disk. The test image is built in
 * memory, pixel by pixel, so nothing here depends on {@code imread}, on JPEG/PNG codecs, or on
 * any image file existing in the repo. That keeps the test fast and keeps a codec problem from
 * masquerading as an ORB problem.
 */
class ORBTest {

    /**
     * A 256x256 grayscale image made of 8x8 blocks of pseudo-random brightness.
     *
     * <p>The seed is fixed, so the image is identical on every run and every OS — a test that
     * uses real randomness can pass 99 times and fail on the 100th, which is far worse than a
     * test that never passes. Blocks (rather than per-pixel noise) give ORB crisp corners to
     * latch onto, and random block values make each corner's neighbourhood distinctive, which
     * matters for the ratio test in {@link CardIndex#geometricMatches}.
     */
    static Mat syntheticCardImage(long seed) {
        final int size = 256;
        final int block = 8;
        Mat image = new Mat(size, size, CV_8UC1);
        UByteIndexer pixels = image.createIndexer();
        Random rng = new Random(seed);
        for (int blockY = 0; blockY < size / block; blockY++) {
            for (int blockX = 0; blockX < size / block; blockX++) {
                int value = rng.nextInt(256);
                for (int y = 0; y < block; y++) {
                    for (int x = 0; x < block; x++) {
                        pixels.put(blockY * block + y, blockX * block + x, value);
                    }
                }
            }
        }
        pixels.release();
        return image;
    }

    @Test
    @DisplayName("ORB.create() returns a live detector, not a null pointer")
    void createReturnsUsableDetector() {
        Natives.requireOpenCv();

        ORB orb = ORB.create();

        // ORB is a JavaCPP wrapper around a C++ object. The Java reference can be non-null while
        // the pointer underneath is null, which is why isNull() is worth checking separately.
        assertNotNull(orb, "ORB.create() returned null");
        assertFalse(orb.isNull(), "ORB.create() returned a wrapper around a null native pointer");
        assertEquals(32, orb.descriptorSize(), "ORB descriptors should be 32 bytes (256 bits)");
        assertEquals(NORM_HAMMING, orb.defaultNorm(),
                "ORB is a binary descriptor, so its natural distance is Hamming. "
                        + "CardIndex.geometricMatches builds its BFMatcher with NORM_HAMMING and "
                        + "relies on this.");
    }

    @Test
    @DisplayName("ORB.create() uses OpenCV's documented default parameters")
    void defaultParametersAreAsDocumented() {
        Natives.requireOpenCv();

        ORB orb = ORB.create();

        // assertAll runs every check even if an earlier one fails, so a version bump that changes
        // three defaults reports all three at once instead of hiding two behind the first.
        assertAll("ORB defaults",
                () -> assertEquals(500, orb.getMaxFeatures()),
                () -> assertEquals(8, orb.getNLevels()),
                () -> assertEquals(31, orb.getEdgeThreshold()),
                () -> assertEquals(0, orb.getFirstLevel()),
                () -> assertEquals(2, orb.getWTA_K()),
                () -> assertEquals(ORB.HARRIS_SCORE, orb.getScoreType()),
                () -> assertEquals(31, orb.getPatchSize()),
                () -> assertEquals(20, orb.getFastThreshold()),
                // scaleFactor is a C++ float widened to a Java double, so 1.2 is not exact.
                // Comparing floating point with a tolerance is almost always what you want.
                () -> assertEquals(1.2, orb.getScaleFactor(), 1e-6));
    }

    @Test
    @DisplayName("setMaxFeatures is honoured by the native object")
    void settersReachTheNativeObject() {
        Natives.requireOpenCv();

        ORB orb = ORB.create();
        orb.setMaxFeatures(120);

        assertEquals(120, orb.getMaxFeatures(),
                "The setter did not reach the C++ object, which would mean the JavaCPP binding "
                        + "is loaded but not wired up correctly");
    }

    @Test
    @DisplayName("detectAndCompute produces one 32-byte descriptor per keypoint")
    void detectAndComputeProducesMatchingKeypointsAndDescriptors() {
        Natives.requireOpenCv();

        Mat image = syntheticCardImage(20260827L);
        ORB orb = ORB.create();
        KeyPointVector keypoints = new KeyPointVector();
        Mat descriptors = new Mat();

        orb.detectAndCompute(image, new Mat(), keypoints, descriptors);

        long found = keypoints.size();
        assertTrue(found > 0, "ORB found no keypoints at all in a high-contrast textured image");
        assertFalse(descriptors.empty(), "Keypoints were found but no descriptors were computed");
        // The invariant that matters downstream: rows line up with keypoints. geometricMatches
        // indexes kpA.get(match.queryIdx()), so a mismatch here would be an out-of-bounds crash
        // in production, not a wrong answer.
        assertEquals(found, descriptors.rows(),
                "Descriptor rows must line up 1:1 with keypoints");
        assertEquals(orb.descriptorSize(), descriptors.cols(),
                "Each descriptor row should be descriptorSize() bytes wide");
        assertTrue(found <= orb.getMaxFeatures(),
                "ORB returned more keypoints than its own maxFeatures cap");

        image.release();
        descriptors.release();
    }

    @Test
    @DisplayName("The same image produces byte-identical descriptors twice in a row")
    void descriptorsAreDeterministic() {
        Natives.requireOpenCv();

        Mat first = syntheticCardImage(7L);
        Mat second = syntheticCardImage(7L);
        ORB orb = ORB.create();

        KeyPointVector kpA = new KeyPointVector();
        Mat descA = new Mat();
        orb.detectAndCompute(first, new Mat(), kpA, descA);

        KeyPointVector kpB = new KeyPointVector();
        Mat descB = new Mat();
        orb.detectAndCompute(second, new Mat(), kpB, descB);

        assertEquals(kpA.size(), kpB.size(), "Same pixels, different keypoint count");
        assertEquals(descA.rows(), descB.rows());

        UByteIndexer a = descA.createIndexer();
        UByteIndexer b = descB.createIndexer();
        int differingBytes = 0;
        for (int row = 0; row < descA.rows(); row++) {
            for (int col = 0; col < descA.cols(); col++) {
                if (a.get(row, col) != b.get(row, col)) {
                    differingBytes++;
                }
            }
        }
        a.release();
        b.release();
        // If this ever fails, the cache written by CardIndex.writeToDisk is not reproducible and
        // cached scores will silently disagree with freshly computed ones.
        assertEquals(0, differingBytes, "Descriptors are not reproducible for identical input");

        first.release();
        second.release();
        descA.release();
        descB.release();
    }
}
