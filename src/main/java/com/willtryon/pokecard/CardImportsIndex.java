package com.willtryon.pokecard;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import dev.brachtendorf.jimagehash.hash.Hash;
import dev.brachtendorf.jimagehash.hashAlgorithms.HashingAlgorithm;
import dev.brachtendorf.jimagehash.hashAlgorithms.PerceptiveHash;
import org.bytedeco.opencv.opencv_features2d.ORB;

public class CardImportsIndex {
    private final Path compareDir;
    private final List<CardSignature> hashed;
    private final CardIndex cardDB;
    private final List<Hash> seenHashes = new ArrayList<>();
    private final HashingAlgorithm hasher = new PerceptiveHash(64);
    private final List<CardImports> imports = new ArrayList<>();
    private static final double DUP_THRESHOLD = 0.0;

    private record Scored(CardSignature sig, double score){}

    public CardImportsIndex(Path compareDir, List<CardSignature> hashed, CardIndex cardDB){
        this.compareDir = compareDir;
        this.hashed = hashed;
        this.cardDB = cardDB;
    }

    public List<CardImports> scan(){ return scan(null); }

    public synchronized List<CardImports> scan(ScanProgress progress){
        List<CardImports> fresh = new ArrayList<>();
        System.out.println("Scanning "+ compareDir+" for new images...");
        try (Stream<Path> stream = Files.walk(compareDir)){
            List<Path> imgList = stream
                .filter(path -> {
                    String s = path.toString().toLowerCase();
                    return s.endsWith(".jpg") || s.endsWith(".png");
                })
                .collect(Collectors.toList());
            long count = imgList.size();
            int loc = 0;
            for(Path path : imgList){
                loc++;
                Hash qHash;
                try{
                    qHash = hasher.hash(new File(path.toString()));
                }catch(IOException e){
                    continue;
                }
                if(isDuplicate(qHash)){
                    continue;
                }
                CardImports result = compareOne(path, qHash, loc, count, progress);
                if (result != null){
                    fresh.add(result);
                    seenHashes.add(qHash);
                    imports.add(result);
                    System.out.println(result.getHashedRecordHistory());
                    System.out.println(result.getORBRecordHistory());

                }
            }
        }catch(IOException e){
            e.printStackTrace();
        }
        return fresh;
    }

    private boolean isDuplicate(Hash qHash){
        for(Hash seen:seenHashes){
            if (qHash.normalizedHammingDistance(seen) <= DUP_THRESHOLD){
                return true;
            }
        }
        return false;
    }

    public synchronized CardImports scanOne(Path image, ScanProgress progress) throws IOException{
        Hash qHash = hasher.hash(new File(image.toString()));
        CardImports result = compareOne(image, qHash, 1, 1, progress);
        if (result != null){ seenHashes.add(qHash); imports.add(result); }
        if (progress != null) progress.report("Scan complete", 1.0);
        return result;
    }

    private CardImports compareOne(Path path, Hash test, int loc, long count, ScanProgress progress) {
        File victim = new File(path.toString());
        PriorityQueue<Scored> topHash = new PriorityQueue<>(Comparator.comparingDouble((Scored s) -> s.score()).reversed());
		for (int i = 0; i < hashed.size(); i++) {
		    double comp = test.normalizedHammingDistance(hashed.get(i).getBinaryHash());
		    topHash.offer(new Scored(hashed.get(i), comp));
		    if (topHash.size()>1000) topHash.poll();
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
		long startTime = System.currentTimeMillis();
        int lastPct = -1;
		for (int i = 0; i < hashed.size(); i++) {
		    int comp = cardDB.geometricMatches(test2.descriptors, test2.keypoints, hashed.get(i).getMatData(), hashed.get(i).getKeypoints());
		    bottomOrb.offer(new Scored(hashed.get(i), comp));
		    if(bottomOrb.size()>1000) bottomOrb.poll();
		    String percent = String.format("%.0f", ((double) i / hashed.size()) * 100);
		    System.out.println("\033[0F\033[K" + percent + "% " + cardDB.timer(startTime) + " ("+loc+"/"+count+")");
            if (progress != null) {                                    // <-- new
                int pct = (int) (((double) i / hashed.size()) * 100);
                if (pct != lastPct) {                                  // throttle: only on % change
                    lastPct = pct;
                    double overall = ((loc - 1) + (double) i / hashed.size()) / count;
                    progress.report("Scanning " + victim.getName() + "  (" + loc + "/" + count + ")", overall);
                }
            }
		}
		List<Scored> orbSorted = new ArrayList<>(bottomOrb);
		orbSorted.sort(Comparator.comparingDouble(Scored::score).reversed());
		Scored bestOrb = orbSorted.get(0);
		System.out.println("\nUploaded image " + victim + " appears to be closest to " + bestOrb.sig.getStringImgPath() + ". (ORB)");
		System.out.println(bestOrb.score());
		CardImports.Match orbMatch = new CardImports.Match(bestOrb.sig.getCardID(), bestOrb.sig.getStringImgPath(), bestOrb.score());
		List<CardSignature> recordRecord2 = new ArrayList<>();
		List<Double> recordScore2 = new ArrayList<>();
		for(int s = 0; s<orbSorted.size(); s++){
		    recordRecord2.add(orbSorted.get(s).sig());
		    recordScore2.add(orbSorted.get(s).score());
		}
		return new CardImports(path, hashMatch, orbMatch, recordScore, recordRecord, recordScore2, recordRecord2);
    }

    public List<CardImports> getImports() { return imports; }

    public CardImports getLastImports() {
        return imports.isEmpty() ? null : imports.get(imports.size() - 1);
    }

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