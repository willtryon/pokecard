package com.willtryon.pokecard;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import dev.brachtendorf.jimagehash.hash.Hash;
import dev.brachtendorf.jimagehash.hashAlgorithms.HashingAlgorithm;
import dev.brachtendorf.jimagehash.hashAlgorithms.PerceptiveHash;
import org.bytedeco.opencv.opencv_features2d.ORB;

public class CardImportsIndex {
    private final List<CardImports> imports = new ArrayList<>();

    // hashed = DB entries with a non-null hash; index = source of the shared helpers
    public CardImportsIndex(Path compareDir, List<CardSignature> hashed, CardIndex cardDB) {
        System.out.println("Now looking through " + compareDir.toString() + " for images to compare...\n");
        try (Stream<Path> stream = Files.walk(compareDir);) {
            stream
            .filter(path -> {
                String s = path.toString().toLowerCase();
                return s.endsWith(".jpg") || s.endsWith(".png");
            })
            .forEach(path -> {
                CardImports result = compareOne(path, hashed, cardDB);
                System.out.println(result.getRecordSize());
                System.out.println(result.getHashedRecordHistory());
                System.out.println(result.getORBRecordHistory());
                if (result != null) imports.add(result);
            });
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private CardImports compareOne(Path path, List<CardSignature> hashed, CardIndex cardDB) {
        HashingAlgorithm hasher = new PerceptiveHash(64);
        File victim = new File(path.toString());
        try{
            Hash test = hasher.hash(victim);
            double record = Double.MAX_VALUE;
            CardSignature recordHolder = null;
            List<CardSignature> recordRecord = new ArrayList<>();
            List<Double> recordScore = new ArrayList<>();
            for (int i = 0; i < hashed.size(); i++) {
                double comp = test.normalizedHammingDistance(hashed.get(i).getBinaryHash());
                if (comp < record) {
                    recordRecord.add(hashed.get(i));
                    recordScore.add(comp);
                    record = comp;
                    recordHolder = hashed.get(i);
                }
            }
            System.out.println("\nUploaded image " + victim.toString() + " appears to be closest to " + recordHolder.getStringImgPath() + ". (pHash)");
            System.out.println(record);
            CardImports.Match hashMatch = new CardImports.Match(recordHolder.getCardID(), recordHolder.getStringImgPath(), record);

            // ---- ORB pass (higher is closer) ----
            ORB orb = ORB.create();
            CardIndex.Features test2 = cardDB.describe(path.toString(), orb);
            int record2 = 0;
            CardSignature recordHolder2 = null;
            long startTime = System.currentTimeMillis();
            List<CardSignature> recordRecord2 = new ArrayList<>();
            List<Double> recordScore2 = new ArrayList<>();
            for (int i = 0; i < hashed.size(); i++) {
                int comp = cardDB.geometricMatches(test2.desciptors, test2.keypoints, hashed.get(i).getMatData(), hashed.get(i).getKeypoints());
                String percent = String.format("%.0f", ((double) i / hashed.size()) * 100);
                System.out.println("\033[0F\033[K" + percent + "% " + cardDB.timer(startTime));
                if (comp > record2) {
                    record2 = comp;
                    recordHolder2 = hashed.get(i);
                    recordScore2.add((double)comp);
                    recordRecord2.add(hashed.get(i));
                }
            }
            System.out.println("\nUploaded image " + victim.toString() + " appears to be closest to " + recordHolder2.getImgPath() + ". (ORB)");
            System.out.println(record2);
            CardImports.Match orbMatch = new CardImports.Match(recordHolder2.getCardID(), recordHolder2.getStringImgPath(), record2);
            return new CardImports(path, hashMatch, orbMatch, recordScore, recordRecord, recordScore2, recordRecord2);
        } catch (IOException e) {
            System.out.println("File no worky :(");
            return null;
        }
    }

    public List<CardImports> getImports() { return imports; }

    public List<String[]> toCsvData() {
        List<String[]> data = new ArrayList<>();
        for (CardImports ci : imports) {
            for (String[] row : ci.toCsvRows()) {
                data.add(row);
            }
        }
        return data;
    }
}