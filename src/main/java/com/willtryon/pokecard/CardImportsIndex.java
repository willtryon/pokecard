package com.willtryon.pokecard;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.stream.Stream;

import dev.brachtendorf.jimagehash.hash.Hash;
import dev.brachtendorf.jimagehash.hashAlgorithms.HashingAlgorithm;
import dev.brachtendorf.jimagehash.hashAlgorithms.PerceptiveHash;
import org.bytedeco.opencv.opencv_features2d.ORB;

public class CardImportsIndex {
    private final List<CardImports> imports = new ArrayList<>();
    private record Scored(CardSignature sig, double score){}

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
            PriorityQueue<Scored> topHash = new PriorityQueue<>(Comparator.comparingDouble((Scored s) -> s.score()).reversed());
            double record = Double.MAX_VALUE;
            for (int i = 0; i < hashed.size(); i++) {
                double comp = test.normalizedHammingDistance(hashed.get(i).getBinaryHash());
                topHash.offer(new Scored(hashed.get(i), comp));
                if (topHash.size()>10) topHash.poll();
            }
            List <Scored> hashSorted = new ArrayList<>(topHash);
            hashSorted.sort(Comparator.comparingDouble(Scored::score));
            Scored bestHash = hashSorted.get(0);
            System.out.println("\nUploaded image " + victim + " appears to be closest to " + bestHash.sig().getStringImgPath() + ". (pHash)");
            System.out.println(bestHash.score());
            CardImports.Match hashMatch = new CardImports.Match(bestHash.sig.getCardID(), bestHash.sig.getStringImgPath(), bestHash.score());
            List<CardSignature> recordRecord = new ArrayList<>();
            List<Double> recordScore = new ArrayList<>();
            for(int s = 0; s<hashSorted.size();s++){
                recordRecord.add(hashSorted.get(s).sig());
                recordScore.add(hashSorted.get(s).score());
            }

            // ---- ORB pass (higher is closer) ----
            ORB orb = ORB.create();
            CardIndex.Features test2 = cardDB.describe(path.toString(), orb);
            PriorityQueue<Scored> bottomOrb = new PriorityQueue<>(Comparator.comparingDouble((Scored s)->s.score()));
            int record2 = 0;
            long startTime = System.currentTimeMillis();
            for (int i = 0; i < hashed.size(); i++) {
                int comp = cardDB.geometricMatches(test2.desciptors, test2.keypoints, hashed.get(i).getMatData(), hashed.get(i).getKeypoints());
                bottomOrb.offer(new Scored(hashed.get(i), comp));
                if(bottomOrb.size()>10) bottomOrb.poll();
                String percent = String.format("%.0f", ((double) i / hashed.size()) * 100);
                System.out.println("\033[0F\033[K" + percent + "% " + cardDB.timer(startTime));
            }
            List<Scored> orbSorted = new ArrayList<>(bottomOrb);
            orbSorted.sort(Comparator.comparingDouble(Scored::score));
            Scored bestOrb = orbSorted.get(0);
            System.out.println("\nUploaded image " + victim + " appears to be closest to " + bestOrb.sig.getStringImgPath() + ". (ORB)");
            System.out.println(bestOrb.score());
            CardImports.Match orbMatch = new CardImports.Match(bestOrb.sig.getCardID(), bestOrb.sig.getStringImgPath(), record2);
            List<CardSignature> recordRecord2 = new ArrayList<>();
            List<Double> recordScore2 = new ArrayList<>();
            for(int s = 0; s<orbSorted.size(); s++){
                recordRecord2.add(orbSorted.get(s).sig());
                recordScore2.add(orbSorted.get(s).score());
            }
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