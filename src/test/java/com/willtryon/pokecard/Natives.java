package com.willtryon.pokecard;

import org.junit.jupiter.api.Assumptions;

/**
 * Probes for native libraries that may or may not load on a given machine.
 *
 * <p>Why this exists: OpenCV arrives through {@code opencv-platform}, which unpacks a
 * platform-specific {@code .so}/{@code .dylib}/{@code .dll} at runtime. On a slim Linux image
 * (the Fedora container in {@code maven.yml}, for instance) that unpack can fail because a
 * system library it links against is absent. When that happens the JVM throws
 * {@link UnsatisfiedLinkError} or {@link NoClassDefFoundError} — both {@link Error}s, not
 * exceptions — the moment the class is first touched.
 *
 * <p>A hard failure there tells you nothing useful and turns every PR red. So tests that need
 * OpenCV call {@link #requireOpenCv()} first: JUnit then reports them as <em>skipped</em> with
 * the real reason attached, and the rest of the suite still runs. Tests that are pure Java are
 * never guarded, so a genuine logic regression always fails loudly.
 *
 * <p>The probe deliberately uses {@link Class#forName(String)} rather than naming the class
 * directly. A direct reference could be resolved by the JVM while it is linking <em>this</em>
 * class, which would throw before the {@code try} block ever runs. Reflection defers the load
 * to exactly where we can catch it.
 */
final class Natives {

    /** Loading this class runs {@code Loader.load()}, which is what actually unpacks OpenCV. */
    private static final String OPENCV_PROBE_CLASS = "org.bytedeco.opencv.opencv_features2d.ORB";

    static final boolean OPENCV_AVAILABLE;
    static final String OPENCV_FAILURE;

    static {
        String failure = null;
        try {
            Class.forName(OPENCV_PROBE_CLASS);
        } catch (Throwable t) {
            // Throwable, not Exception: link failures are Errors.
            Throwable root = t;
            while (root.getCause() != null) {
                root = root.getCause();
            }
            failure = root.getClass().getName() + ": " + root.getMessage();
        }
        OPENCV_AVAILABLE = (failure == null);
        OPENCV_FAILURE = failure;
    }

    private Natives() {
    }

    /** Skips the calling test (rather than failing it) when OpenCV natives are unusable. */
    static void requireOpenCv() {
        Assumptions.assumeTrue(OPENCV_AVAILABLE,
                () -> "OpenCV native libraries could not be loaded on this machine -> " + OPENCV_FAILURE);
    }
}
