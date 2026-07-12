package com.willtryon.pokecard;
import java.io.*;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import dev.brachtendorf.jimagehash.hash.Hash;
import dev.brachtendorf.jimagehash.hashAlgorithms.HashingAlgorithm;
import dev.brachtendorf.jimagehash.hashAlgorithms.PerceptiveHash;
import org.bytedeco.opencv.opencv_features2d.ORB;

public class CardImportsIndex {
    private String guess;
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

    public List<CardImports> scan(){
        return scan(null);
    }

    public synchronized List<CardImports> scan(ScanProgress progress){
        guess = "";
        long beginOrbJob = System.currentTimeMillis();
        List<CardImports> fresh = new ArrayList<>();
        System.out.println("Scanning "+ compareDir+" for new images...");
        try (Stream<Path> stream = Files.walk(compareDir)){
            List<Path> imgList = stream
                .filter(path -> {
                    String s = path.toString().toLowerCase();
                    return s.endsWith(".jpg") || s.endsWith(".png");
                })
                .toList();
            long count = imgList.size();
            int loc = 0;
            long [] times = new long[(int)count];
            for(int i = 0; i < count; i++){ times[i] = 0; }
            for(Path path : imgList){
                if(!(loc < 1)){
                    guess = niceTimer(beginOrbJob,loc, imgList, times);
                    beginOrbJob = System.currentTimeMillis();
                }
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
                CardImports result = compareOne(path, qHash, loc, count, progress, guess);
                if (result != null){
                    fresh.add(result);
                    seenHashes.add(qHash);
                    imports.add(result);
                    //System.out.println(result.getHashedRecordHistory());
                    //System.out.println(result.getORBRecordHistory());

                }
            }
        }catch(IOException e){
            e.printStackTrace();
        }
        for(CardImports f : fresh){
            if(f.howLowIsHash() == -1){
                System.out.println("No match found for "+f.getQueryImage().getFileName().toString());
                continue;
            }
            System.out.println("\n"+f.getQueryImage().getFileName().toString());
            System.out.println("The hash result that matches the orb winner " +f.getOrbWinner().cardID());
            System.out.print(" is "+f.getARecordRecord(f.howLowIsHash(), "hash").getCardID() +"at position "+f.howLowIsHash());
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
        CardImports result = compareOne(image, qHash, 1, 1, progress, guess);
        if (result != null){ seenHashes.add(qHash); imports.add(result); }
        if (progress != null) progress.report("Scan complete", 1.0);
        return result;
    }

    private CardImports compareOne(Path path, Hash test, int loc, long count, ScanProgress progress, String guess) {
        File victim = new File(path.toString());
        PriorityQueue<Scored> topHash = new PriorityQueue<>(Comparator.comparingDouble((Scored s) -> s.score()).reversed());
        for (CardSignature cardSignature : hashed) {
            double comp = test.normalizedHammingDistance(cardSignature.getBinaryHash());
            topHash.offer(new Scored(cardSignature, comp));
            if (topHash.size() > 1000) topHash.poll();
        }
		List <Scored> hashSorted = new ArrayList<>(topHash);
		hashSorted.sort(Comparator.comparingDouble(Scored::score));
		Scored bestHash = hashSorted.get(0);
		System.out.println("\nUploaded image " + victim + " appears to be closest to " + bestHash.sig().getStringImgPath() + ". (pHash)");
		System.out.println(bestHash.score());
		CardImports.Match hashMatch = new CardImports.Match(bestHash.sig.getCardID(), bestHash.sig.getStringImgPath(), bestHash.score());
		List<CardSignature> recordRecord = new ArrayList<>();
		List<Double> recordScore = new ArrayList<>();
        for (Scored value : hashSorted) {
            recordRecord.add(value.sig());
            recordScore.add(value.score());
        }

		// ---- ORB pass (higher is closer) ----
		ORB orb = ORB.create();
		CardIndex.Features test2 = cardDB.describe(path.toString(), orb);
		PriorityQueue<Scored> bottomOrb = new PriorityQueue<>(Comparator.comparingDouble((Scored s)->s.score()));
		long startTime = System.currentTimeMillis();
        int lastPct = -1;
        int K = 1000;
        List<CardSignature> shortlist = hashSorted.stream().limit(K).map(Scored::sig).toList();
        double[] orbScores = cardDB.scoreOrbParallel(test2, shortlist);
        //double[] orbScores = cardDB.scoreOrbParallel(test2, hashed);
        for (int i = 0; i < shortlist.size(); i++) {
            bottomOrb.offer(new Scored(shortlist.get(i), orbScores[i]));
            if (bottomOrb.size() > 1000) bottomOrb.poll();
        }
        if (progress != null) {
            double overall = (double) loc / count;
            progress.report("Scanning " + victim.getName() + "  (" + loc + "/" + count + ") " + guess + ".", overall);
        }
		List<Scored> orbSorted = new ArrayList<>(bottomOrb);
		orbSorted.sort(Comparator.comparingDouble(Scored::score).reversed());
		Scored bestOrb = orbSorted.getFirst();
		System.out.println("\nUploaded image " + victim + " appears to be closest to " + bestOrb.sig.getStringImgPath() + ". (ORB)");
		System.out.println(bestOrb.score());
		CardImports.Match orbMatch = new CardImports.Match(bestOrb.sig.getCardID(), bestOrb.sig.getStringImgPath(), bestOrb.score());
		List<CardSignature> recordRecord2 = new ArrayList<>();
		List<Double> recordScore2 = new ArrayList<>();
        for (Scored scored : orbSorted) {
            recordRecord2.add(scored.sig());
            recordScore2.add(scored.score());
        }
		return new CardImports(path, test, hashMatch, orbMatch, recordScore, recordRecord, recordScore2, recordRecord2);
    }

    public List<CardImports> getImports() { return imports; }

    public CardImports getLastImports() {
        return imports.isEmpty() ? null : imports.getLast();
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


    //I write session information to the disk
    private static final int IMPORTS_FORMAT_VERSION = 2;

    public void writeImportsToDisk(Path cacheDir) {
        Path path = cacheDir.resolve("imports.dat");
        try (DataOutputStream dos = new DataOutputStream(
                new BufferedOutputStream(new FileOutputStream(path.toFile())))) {

            // query-hash params are identical for every import (one hasher)
            int bits = 0, algo = 0;
            for (CardImports ci : imports) {
                Hash h = ci.getQueryHash();
                if (h != null) { bits = h.getBitResolution(); algo = h.getAlgorithmId(); break; }
            }
            dos.writeInt(IMPORTS_FORMAT_VERSION);
            dos.writeInt(bits);
            dos.writeInt(algo);
            dos.writeInt(imports.size());

            for (CardImports ci : imports) {
                Path q = ci.getQueryImage();
                dos.writeUTF(q != null ? q.toString() : "");

                Hash qh = ci.getQueryHash();
                dos.writeUTF(qh != null ? qh.getHashValue().toString(16) : "");

                writeMatch(dos, ci.getHashWinner());
                writeMatch(dos, ci.getOrbWinner());

                writeRanking(dos, ci, "hash");
                writeRanking(dos, ci, "orb");   // same length as hash side by construction
            }
        } catch (IOException e) {
            System.out.println("Failed to write imports cache: " + e.getMessage());
        }
    }

    private void writeMatch(DataOutputStream dos, CardImports.Match m) throws IOException {
        dos.writeUTF(m != null && m.cardID() != null ? m.cardID() : "");
        dos.writeUTF(m != null && m.img()    != null ? m.img()    : "");
        dos.writeDouble(m != null ? m.winner() : 0.0);
    }

    private void writeRanking(DataOutputStream dos, CardImports ci, String side) throws IOException {
        int n = ci.getRecordSize();
        dos.writeInt(n);
        for (int i = 0; i < n; i++) {
            CardSignature sig = ci.getARecordRecord(i, side);
            dos.writeUTF(sig != null && sig.getCardID() != null ? sig.getCardID() : "");
            dos.writeDouble(ci.getARecordScore(i, side));
        }
    }

    public void readImportsFromDisk(Path cacheDir) {
        Path path = cacheDir.resolve("imports.dat");
        if (!Files.exists(path)) {
            System.out.println("No imports cache found at " + path);
            return;
        }

        Map<String, CardSignature> byId = new HashMap<>();
        for (CardSignature c : hashed) {
            if (c != null && c.getCardID() != null) byId.put(c.getCardID(), c);
        }

        try (DataInputStream dis = new DataInputStream(
                new BufferedInputStream(new FileInputStream(path.toFile())))) {

            int version = dis.readInt();
            if (version != IMPORTS_FORMAT_VERSION) {
                System.out.println("Imports cache version mismatch (found " + version +
                        ", expected " + IMPORTS_FORMAT_VERSION + "); skipping load.");
                return;
            }
            int bits = dis.readInt();
            int algo = dis.readInt();
            int importCount = dis.readInt();

            List<CardImports> loaded = new ArrayList<>(importCount);
            List<Hash> loadedHashes  = new ArrayList<>(importCount);

            for (int j = 0; j < importCount; j++) {
                String qStr = dis.readUTF();
                Path q = qStr.isEmpty() ? null : Path.of(qStr);

                String qHex = dis.readUTF();
                Hash qHash = qHex.isEmpty() ? null : new Hash(new BigInteger(qHex, 16), bits, algo);

                CardImports.Match hashMatch = readMatch(dis);
                CardImports.Match orbMatch  = readMatch(dis);

                List<CardSignature> recordRecord  = new ArrayList<>();
                List<Double>        recordScore   = new ArrayList<>();
                readRanking(dis, byId, recordRecord, recordScore);

                List<CardSignature> recordRecord2 = new ArrayList<>();
                List<Double>        recordScore2  = new ArrayList<>();
                readRanking(dis, byId, recordRecord2, recordScore2);

                loaded.add(new CardImports(q, qHash, hashMatch, orbMatch,
                        recordScore, recordRecord, recordScore2, recordRecord2));
                if (qHash != null) loadedHashes.add(qHash);
            }

            // overwrite in place: the results AND the dedup set that makes re-scans skip them
            imports.clear();    imports.addAll(loaded);
            seenHashes.clear(); seenHashes.addAll(loadedHashes);
            System.out.println("Loaded " + loaded.size() + " imports (" +
                    loadedHashes.size() + " will be skipped on re-scan).");
        } catch (IOException e) {
            System.out.println("Failed to read imports cache: " + e.getMessage());
        }
    }

    private CardImports.Match readMatch(DataInputStream dis) throws IOException {
        String id  = dis.readUTF();
        String img = dis.readUTF();
        double win = dis.readDouble();
        return id.isEmpty() ? null : new CardImports.Match(id, img, win);
    }

    private void readRanking(DataInputStream dis, Map<String, CardSignature> byId,
                             List<CardSignature> outSigs, List<Double> outScores) throws IOException {
        int n = dis.readInt();
        for (int i = 0; i < n; i++) {
            String id = dis.readUTF();
            double score = dis.readDouble();
            CardSignature sig = byId.get(id);
            if (sig == null) continue;   // card no longer in DB
            outSigs.add(sig);
            outScores.add(score);
        }
    }

    private String niceTimer(long startTime, int loc, List<Path>imgList, long[] times) {
        long elapsed = System.currentTimeMillis() - startTime;
        times[loc] = elapsed;

        // Weighted average: recent items count more, but use all completed samples
        int completed = loc + 1;
        long sum = 0;
        for (int i = 0; i < completed; i++) {
            sum += times[i];
        }
        long avgPerItem = sum / completed;

        long remainingItems = imgList.size() - completed;
        long guess = avgPerItem * remainingItems;

        long hours   = guess / 3600000;
        long minutes = (guess % 3600000) / 60000;
        long seconds = (guess % 60000) / 1000;

        StringBuilder sb = new StringBuilder("about ");
        if (hours > 0) {
            sb.append(hours).append(hours == 1 ? " hour " : " hours ");
            //sb.append(minutes).append(minutes == 1 ? " minute" : " minutes");
        } else if (minutes > 0) {
            sb.append(minutes).append(minutes == 1 ? " minute " : " minutes ");
            //sb.append(seconds).append(seconds == 1 ? " second" : " seconds");
        } else {
            sb.append(seconds).append(seconds == 1 ? " second " : " seconds ");
        }
        sb.append("remaining");
        return sb.toString();
    }
}