package com.willtryon.pokecard;

import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.KeyPointVector;
import org.bytedeco.opencv.opencv_core.DMatchVector;
import org.bytedeco.opencv.opencv_core.DMatchVectorVector;
import org.bytedeco.opencv.opencv_core.DMatch;
import org.bytedeco.opencv.opencv_features2d.ORB;
import org.bytedeco.opencv.opencv_features2d.BFMatcher;

import static org.bytedeco.opencv.global.opencv_imgcodecs.imread;
import static org.bytedeco.opencv.global.opencv_imgcodecs.IMREAD_GRAYSCALE;
import static org.bytedeco.opencv.global.opencv_core.NORM_HAMMING;

/**
 * Throwaway harness to learn the ORB API. Run it with three image paths:
 *   args[0] = a "scan"   args[1] = the card you think it is   args[2] = a different card
 * For your very first run, pass the SAME file as args[0] and args[1] -- an image
 * matched against itself should score near its own keypoint count.
 */
public class OrbMatchTest {

    // Turn one image file into a set of ORB descriptors (the "fingerprint" of its features).
    static Mat describe(String path, ORB orb) {
        Mat img = imread(path, IMREAD_GRAYSCALE);          // ORB works on a single grey channel
        if (img.empty()) {
            throw new RuntimeException("Could not load image: " + path);
        }
        KeyPointVector keypoints = new KeyPointVector();   // where the interesting corners are
        Mat descriptors = new Mat();                       // a 32-byte binary code per keypoint
        orb.detectAndCompute(img, new Mat(), keypoints, descriptors);  // empty Mat = no mask
        System.out.println(path + "  ->  " + keypoints.size() + " keypoints");
        return descriptors;
    }

    // Count "good" matches between two fingerprints using Lowe's ratio test.
    static int goodMatches(Mat descA, Mat descB) {
        if (descA.empty() || descB.empty()) return 0;

        // ORB codes are binary, so distance is measured with Hamming, not Euclidean.
        BFMatcher matcher = new BFMatcher(NORM_HAMMING, false);

        // For each feature in A, find its 2 nearest features in B.
        DMatchVectorVector knn = new DMatchVectorVector();
        matcher.knnMatch(descA, descB, knn, 2);

        // Keep a match only if the best neighbour is clearly closer than the 2nd best.
        // That filters out ambiguous features that match lots of things equally badly.
        int good = 0;
        for (long i = 0; i < knn.size(); i++) {
            DMatchVector pair = knn.get(i);
            if (pair.size() >= 2) {
                DMatch best   = pair.get(0);
                DMatch second = pair.get(1);
                if (best.distance() < 0.75f * second.distance()) {
                    good++;
                }
            }
        }
        return good;
    }

    public static void main(String[] args) {
        ORB orb = ORB.create();   // defaults (500 features) are fine to start

        Mat scan      = describe(args[0], orb);
        Mat sameCard  = describe(args[1], orb);
        Mat otherCard = describe(args[2], orb);

        System.out.println();
        System.out.println("scan vs same card : " + goodMatches(scan, sameCard));
        System.out.println("scan vs other card: " + goodMatches(scan, otherCard));
    }
}