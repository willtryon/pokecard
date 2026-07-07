package com.willtryon.pokecard;
import java.util.*;
import java.util.stream.Stream;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import com.opencsv.*;
import dev.brachtendorf.jimagehash.hash.Hash;
import dev.brachtendorf.jimagehash.hashAlgorithms.HashingAlgorithm;
import dev.brachtendorf.jimagehash.hashAlgorithms.PerceptiveHash;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Point2f;
import org.bytedeco.opencv.opencv_core.KeyPointVector;
import org.bytedeco.opencv.opencv_core.DMatchVector;
import org.bytedeco.opencv.opencv_core.DMatchVectorVector;
import org.bytedeco.opencv.opencv_core.FileNode;
import org.bytedeco.opencv.opencv_core.FileStorage;
import org.bytedeco.javacpp.indexer.FloatIndexer;
import org.bytedeco.javacpp.indexer.UByteIndexer;
import org.bytedeco.opencv.opencv_core.DMatch;
import org.bytedeco.opencv.opencv_features2d.ORB;
import org.bytedeco.opencv.opencv_features2d.BFMatcher;
import static org.bytedeco.opencv.global.opencv_imgcodecs.imread;
import static org.bytedeco.opencv.global.opencv_imgcodecs.IMREAD_GRAYSCALE;
import static org.bytedeco.opencv.global.opencv_core.CV_32FC2;
import static org.bytedeco.opencv.global.opencv_core.NORM_HAMMING;
import static org.bytedeco.opencv.global.opencv_core.write;
import static org.bytedeco.opencv.global.opencv_calib3d.findHomography;
import static org.bytedeco.opencv.global.opencv_calib3d.RANSAC;
import java.math.BigInteger;
import org.bytedeco.javacpp.BytePointer;

public class CardIndex{
    private final CardSignature [] cardDB;
    private final Path imagesDir;
    private final Path outputDir;
    private final Path cacheDir;

    /*Approach so far is the query sql db and dump its contents for every hit to a new Card obj, which is stored
    in an array of cards...*/
    public CardIndex(int size, String url, Path imagesDir, Path outputDir, Path cacheDir) throws SQLException, FileNotFoundException {
        int line = 0;
        int failed = 0;
        int passed = 0;
        int corrupt = 0;
        this.imagesDir = imagesDir;
        this.outputDir = outputDir;
        this.cacheDir = cacheDir;
        List<String[]> data = new ArrayList<>();
        HashingAlgorithm hasher = new PerceptiveHash(64);
        Scanner scan = new Scanner(System.in);
        cardDB = new CardSignature[size];
        try (Connection conn = DriverManager.getConnection(url);
             Statement st = conn.createStatement();
            ResultSet rs = st.executeQuery("SELECT cardId, name, expName, expCardNumber, rarity FROM cards")) {
            long startTime = System.currentTimeMillis();
            System.out.println("Now generating hashes for the database...");
            System.out.println("\n\n");
            while (rs.next()) {
                String cardId  = rs.getString("cardId");
                String expName = rs.getString("expName");
                String expCardNumber = rs.getString("expCardNumber");
                Path img = resolveImage(imagesDir, expName, cardId, expCardNumber);
                String percent = String.format("%.0f", ((double) line / size) * 100);
                System.out.print("\033[3A\033[J");
                System.out.println("Hashing:  " + cardId + "...");
                System.out.println("ORB map:  generating...");
                System.out.printf("Passed: %d\tFailed: %d\tCorrupt: %d\t%s%%\t%s\t(%d/%d)%n",
                    passed, failed, corrupt, percent, timer(startTime), line, size);
                ORB orb = ORB.create();
                if (img != null && Files.exists(img)) {
                    String address = img.toString();
                    try {
                        File victim = new File(address);
                        try {
                            Features f = describe(address, orb);
                            cardDB[line] = new CardSignature(cardId, img, hasher.hash(victim), f.descriptors, f.keypoints);
                            System.out.print("\033[3A\033[J");
                            System.out.println("Hashing:  " + cardId + " \u2713");
                            System.out.println("ORB map:  " + cardId + " \u2713");
                            System.out.printf("Passed: %d\tFailed: %d\tCorrupt: %d\t%s%%\t%s\t(%d/%d)%n",
                                    passed, failed, corrupt, percent, timer(startTime), line, size);
                            passed++;
                        } catch (IllegalArgumentException e) {
                            data.add(new String[]{"File "+cardId+" appears to be corrupt (found at "+address+" but could not be decoded)."});
                            corrupt++;
                        }
                    } catch (IOException e){
                        data.add(new String[]{"An unknown exception occurred when hashing " + cardId});
                        failed++;
                    }
                } else{
                    // NOTE: larp for the log. The real lookup happens in resolveImage();
                    // the file may actually exist under a different number format. 
                    Path expected = imagesDir
                            .resolve(expName == null ? "" : expName.replace(" ", "-"))
                            .resolve(cardId.replace("/", "-") + ".jpg");
                    data.add(new String[]{"File " + cardId + " cannot be found by the program.\nLocation searched (folder): " + expected.getParent()});
                    cardDB[line] = new CardSignature(cardId, img == null ? expected : img, null, null, null);
                    failed++;
                }
                line++;
                }
            }
        System.out.println("\n\nPassed: " + passed + "\nFailed: " + failed + "\nCorrupt: " + corrupt + "\nOut of: " + line + "");
        String result = String.format("%.0f", ((double) line / size) * 100);
        System.out.println(result + "% passed.\n\n");
        writeToTxt("log.txt", data);
        System.out.println("Done calculating image data. Writing the data to the disk will take about 620MB. Do you want save the data?(y/n)");
        String check = scan.nextLine();
        if(check.matches("[yY]")){
            System.out.println("Writing cache to the disk...");
            writeToDisk(cacheDir);
        }
    }

   public CardIndex(Path imagesDir, Path outputDir, Path cacheDir) {
        this.imagesDir = imagesDir;
        this.outputDir = outputDir;
        this.cacheDir = cacheDir;
        this.cardDB = readFromDisk(cacheDir);
    }

    private Path resolveImage(Path imagesDir, String expName, String cardId, String expCardNumber){
        String folder = (expName == null ? "" : expName.replace(" ", "-"));
        Path dir = imagesDir.resolve(folder);
       //bail out if normal file path is correct (about 77% chance it is)
        Path exact = dir.resolve(cardId.replace("/", "-") + ".jpg");
        if (Files.exists(exact)){
            return exact;
        }
        if (!Files.isDirectory(dir)){
            return null;
        }
        final String wantName = nameKey(cardId);
        final Integer wantNum = collectorNumber(expCardNumber, cardId); 
        try (Stream<Path> stream = Files.list(dir)){
            List<Path> candidates = stream
                    .filter(p -> {
                        String s = p.getFileName().toString().toLowerCase(Locale.ROOT);
                        return s.endsWith(".jpg") || s.endsWith(".jpeg") || s.endsWith(".png");
                    })
                    .collect(java.util.stream.Collectors.toList());
            // 1) Exact normalized stem
            String wantStemFull = normalizeForMatch(cardId.replace("/", "-"));
            for (Path p : candidates){
                if (normalizeForMatch(stripExt(p)).equals(wantStemFull)) {
                    return p;
                }
            }
            // 2)compares int collector key
            if (wantNum != null && !wantName.isEmpty()){
                List<Path> hits = new ArrayList<>();
                for (Path p : candidates){
                    String stem = stripExt(p);
                    Integer fileNum = trailingNumber(stem);
                    if (fileNum != null && fileNum.intValue() == wantNum.intValue()
                            && nameKey(stem).equals(wantName)){
                        hits.add(p);
                    }
                }
                if (hits.size() == 1){
                    return hits.get(0);
                }

                if (hits.isEmpty()){
                    for (Path p : candidates){
                        String stem = stripExt(p);
                        Integer fileNum = trailingNumber(stem);
                        if (fileNum != null && fileNum.intValue() == wantNum.intValue()
                                && !nameKey(stem).isEmpty()
                                && (nameKey(stem).contains(wantName) || wantName.contains(nameKey(stem)))){
                            hits.add(p);
                        }
                    }
                    if (hits.size() == 1){
                        return hits.get(0);
                    }
                }
            }
            // 3) Last resort: normalized prefix match (kept from the original behavior).
            for (Path p : candidates){
                if (normalizeForMatch(stripExt(p)).startsWith(wantStemFull)) {
                    return p;
                }
            }
        } catch (IOException e){
            return null;
        }
        return null;
    }

    private Integer collectorNumber(String expCardNumber, String cardId){
        Integer n = parseLeadingInt(expCardNumber);
        if (n != null) return n;
        return trailingNumber(cardId);
    }

    private Integer parseLeadingInt(String s){
        if (s == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\d+").matcher(s);
        return m.find() ? Integer.valueOf(Integer.parseInt(m.group())) : null;
    }
    //pulls int at the end of a '-' char
    private Integer trailingNumber(String stem){
        if (stem == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)$").matcher(stem);
        return m.find() ? Integer.valueOf(Integer.parseInt(m.group(1))) : null;
    }
    //bye bye number slop
    private String nameKey(String cardId){
        if (cardId == null) return "";
        String s = cardId;
        int jj = s.indexOf("---");
        if (jj >= 0){
            s = s.substring(0, jj);
        } else {
            int dash = s.lastIndexOf('-');
            if (dash > 0 && s.substring(dash + 1).matches("[A-Za-z]*\\d+")){
                s = s.substring(0, dash);
            }
        }
        return normalizeForMatch(s);
    }

    private String stripExt(Path p){
        String s = p.getFileName().toString();
        int dot = s.lastIndexOf('.');
        if (dot > 0){
            return s.substring(0,dot);
        }
        else{
            return s;
        }
    }
    //gets rid of the slop...
    private String normalizeForMatch(String s){
        if (s == null) return "";
        String n = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        return n.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }


    //shallow test of Card & cardIndex init...
    public void test(int args)throws NullPointerException{
        System.out.println("\n"+cardDB.length);
        int factor = args/10;
        System.out.println(factor);
        for(int i = 0; i < cardDB.length; i += 3000){
            if (cardDB[i] != null) {
                System.out.println("Entry "+i);
                System.out.println(cardDB[i].toString());
            }
        }
    }

    public void testHash() throws IOException {
        /*Some card objects will have null hash vars if a file is not found or not accepted by the program,
        or will not even exist in the cardDB at all if the image is corrupt, which leads to a null value
        @ cardDB[index]. This for block checks for null values and adds them to an array list that only parses
        hashes so the program doesn't crash when it finds a null Card obj.*/
        List<CardSignature> hashed = new ArrayList<>();
        for (int c = 0; c < cardDB.length; c++){
            if (cardDB[c] != null && cardDB[c].getBinaryHash() != null) {
                hashed.add(cardDB[c]);
            }
        }
        System.out.println("\n\nComparing " + hashed.size() + " hashed cards...\n");

        long pairCount = 0;
        double record = Double.MAX_VALUE;
        String recordHolderA = "", recordHolderB = "";
        long startTime = System.currentTimeMillis();
        //Won't be much use for this program's purpose, but nice way to test all valid hashes work.
        try (PrintWriter pw = new PrintWriter("hashes.txt")) {
            for (int i = 0; i < hashed.size(); i++) {
                Hash first = hashed.get(i).getBinaryHash();
                for (int j = i + 1; j < hashed.size(); j++) {
                    double comp = first.normalizedHammingDistance(hashed.get(j).getBinaryHash());
                    if (comp < record) {
                        record = comp;
                        recordHolderA = hashed.get(i).getCardID();
                        recordHolderB = hashed.get(j).getCardID();
                        pw.println(record + "\t" + recordHolderA + " vs " + recordHolderB);
                    }
                    pairCount++;
                }
                if (i % 500 == 0) {
                    System.out.println(i + "/" + hashed.size() + "  (" + pairCount + " comparisons...)");
                }   
            }
        }
    long ms = System.currentTimeMillis() - startTime;
    System.out.println("\nDone: " + pairCount + " comparisons in " + ms + " ms");
    System.out.println("\nClosest pair: " + recordHolderA + " vs " + recordHolderB + " @ " + record);
    }
    
    public static final class Features{
        final KeyPointVector keypoints;
        final Mat descriptors;
        Features(KeyPointVector k, Mat d){
            this.keypoints = k;
            this.descriptors = d;
        }
        public KeyPointVector getKeyPointVector(){
            return keypoints;
        }

        public Mat getDescriptors(){
            return descriptors;
        }

    }

    public Features describe(String path, ORB orb){
        Mat img = imread(path, IMREAD_GRAYSCALE);
        if (img.empty()){
            System.out.println("Something went wrong when trying to load round 2 image: "+path);
        }
        KeyPointVector keypoints = new KeyPointVector();
        Mat descriptors = new Mat();
        //System.out.println("Now generating ORB vectors for "+cardID);
        orb.detectAndCompute(img, new Mat(), keypoints, descriptors);
        img.release();
        return new Features(keypoints, descriptors);
    }

    public int geometricMatches(Mat descA, KeyPointVector kpA, Mat descB, KeyPointVector kpB){
        if (descA == null || descB == null || kpA == null || kpB == null
                || descA.empty() || descB.empty()) return 0;

        BFMatcher matcher = new BFMatcher(NORM_HAMMING, false);
        DMatchVectorVector knn = new DMatchVectorVector();
        matcher.knnMatch(descA, descB, knn, 2);

        List<int[]> good = new ArrayList<>();
        for (long i = 0; i < knn.size(); i++){
            DMatchVector pair = knn.get(i);
            if (pair.size() >= 2){
                DMatch best = pair.get(0);
                DMatch second = pair.get(1);
                if (best.distance() < 0.75f * second.distance()){
                    good.add(new int[]{ best.queryIdx(), best.trainIdx() });
                }
            }
        }
        matcher.close();
        knn.close();

        if (good.size() < 4) return 0;

        int n = good.size();
        Mat srcPoints = new Mat(n, 1, CV_32FC2);
        Mat dstPoints = new Mat(n, 1, CV_32FC2);
        FloatIndexer srcIdx = srcPoints.createIndexer();
        FloatIndexer dstIdx = dstPoints.createIndexer();
        for (int i = 0; i < n; i++){
            int[] m = good.get(i);
            Point2f a = kpA.get(m[0]).pt();
            Point2f b = kpB.get(m[1]).pt();
            srcIdx.put(i, 0, 0, a.x()); srcIdx.put(i, 0, 1, a.y());
            dstIdx.put(i, 0, 0, b.x()); dstIdx.put(i, 0, 1, b.y());
        }
        srcIdx.release();
        dstIdx.release();

        Mat mask = new Mat();
        Mat H = findHomography(srcPoints, dstPoints, mask, RANSAC, 5.0);

        int inliers = 0;
        if (H != null && !H.empty() && !mask.empty()){
            UByteIndexer mi = mask.createIndexer();
            long rows = mask.rows();
            for (long i = 0; i < rows; i++){
                if (mi.get(i, 0) != 0) inliers++;
            }
            mi.release();
        }

        srcPoints.release(); dstPoints.release(); mask.release();
        if (H != null) H.release();
        return inliers;
    }

    public CardImportsIndex newImportsIndex(Path compareDir, Path cacheDir){
        List<CardSignature> hashed = new ArrayList<>();
        for(int c = 0; c < cardDB.length; c++){
            if(cardDB[c] != null && cardDB[c].getBinaryHash() != null) hashed.add(cardDB[c]);
        }
        return new CardImportsIndex(compareDir, hashed, this);
    }   
    
    public void scanImports(CardImportsIndex importDB){
        scanImports(importDB, null);
    }

    public void scanImports(CardImportsIndex importDB, ScanProgress progress){
        List<CardImports> fresh = importDB.scan(progress);
        if (fresh.isEmpty()) return;
        List<String[]> rows = new ArrayList<>();
        for(CardImports ci : fresh){
            for(String[]r : ci.toCsvRows()) rows.add(r); 
        }
        csvOutput("ImageComparisonOutput.csv", outputDir, rows);
    }



    public void writeToDisk(Path cacheDir) {
        Path xmlPath = cacheDir.resolve("cache.xml");
        Path orbPath = cacheDir.resolve("cache_orb.dat");

        // Write ORB binary data first — if this fails, skip the XML
        try (DataOutputStream dos = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(orbPath.toFile())))) {
            dos.writeInt(cardDB.length);
            for (int i = 0; i < cardDB.length; i++) {
                CardSignature c = cardDB[i];
                Mat desc = (c != null) ? c.getMatData() : null;
                KeyPointVector kp = (c != null) ? c.getKeypoints() : null;
                boolean hasData = desc != null && !desc.empty() && kp != null && kp.size() > 0;
                dos.writeBoolean(hasData);
                if (hasData) {
                    dos.writeInt(desc.rows());
                    dos.writeInt(desc.cols());
                    dos.writeInt(desc.type());
                    UByteIndexer idx = desc.createIndexer();
                    for (int r = 0; r < desc.rows(); r++)
                        for (int col = 0; col < desc.cols(); col++)
                            dos.writeByte(idx.get(r, col));
                    idx.release();
                    long n = kp.size();
                    dos.writeLong(n);
                    for (long k = 0; k < n; k++) {
                        dos.writeFloat(kp.get(k).pt().x());
                        dos.writeFloat(kp.get(k).pt().y());
                        dos.writeFloat(kp.get(k).size());
                        dos.writeFloat(kp.get(k).angle());
                        dos.writeFloat(kp.get(k).response());
                        dos.writeInt(kp.get(k).octave());
                        dos.writeInt(kp.get(k).class_id());
                    }
                }
            }
        } catch (IOException e) {
            System.out.println("Failed to write ORB binary cache: " + e.getMessage());
            return;
        }

        // Write XML — metadata only, no Mats
        FileStorage fs = new FileStorage(xmlPath.toString(), FileStorage.WRITE);
        try {
            int bitRes = 0, algId = 0;
            for (CardSignature c : cardDB) {
                if (c != null && c.getBinaryHash() != null) {
                    bitRes = c.getBinaryHash().getBitResolution();
                    algId  = c.getBinaryHash().getAlgorithmId();
                    break;
                }
            }
            write(fs, "count",     cardDB.length);
            write(fs, "hash_bits", bitRes);
            write(fs, "hash_algo", algId);
            for (int i = 0; i < cardDB.length; i++) {
                CardSignature c = cardDB[i];
                if (c == null) {
                    write(fs, "cardID_" + i, "");
                    write(fs, "path_"   + i, "");
                    write(fs, "hash_"   + i, "");
                    continue;
                }
                String id = c.getCardID();
                write(fs, "cardID_" + i, id != null ? id : "");
                String p = c.getStringImgPath();
                write(fs, "path_" + i, p != null ? p : "");
                Hash h = c.getBinaryHash();
                write(fs, "hash_" + i, h != null ? h.getHashValue().toString(16) : "");
            }
        } finally {
            fs.release();
        }
    }

    private CardSignature[] readFromDisk(Path cacheDir) {
        Path xmlPath = cacheDir.resolve("cache.xml");
        Path orbPath = cacheDir.resolve("cache_orb.dat");

        // Read XML metadata
        FileStorage fs = new FileStorage(xmlPath.toString(), FileStorage.READ);
        if (!fs.isOpened()) {
            System.out.println("Sorry, the program can't open the cache.");
            fs.release();
            return new CardSignature[0];
        }
        CardSignature[] db;
        try {
            FileNode cNode = fs.get("count");
            if (cNode.isNone()){
                System.out.println("Sorry, the program can't open the cache. (invalid index)");
                return new CardSignature[0];
            }
            int count  = (int) cNode.real();
            int bitRes = (int) fs.get("hash_bits").real();
            int algId  = (int) fs.get("hash_algo").real();
            db = new CardSignature[count];
            for (int i = 0; i < count; i++){
                String percent = String.format("%.0f", ((double) i / count) * 100);
                System.out.print("\033[0F\033[J");
                System.out.printf("\nLoading metadata...%s%%",percent);
                String cardID = nodeToString(fs.get("cardID_" + i));
                if (cardID.isEmpty()) { db[i] = null; continue; }
                String pathStr = nodeToString(fs.get("path_" + i));
                String hex = nodeToString(fs.get("hash_" + i));
                Path img= pathStr.isEmpty() ? null : Path.of(pathStr);
                Hash hash  = hex.isEmpty()     ? null : new Hash(new BigInteger(hex, 16), bitRes, algId);
                db[i] = new CardSignature(cardID, img, hash, null, null);
            }
        } finally {
            fs.release();
        }

        // Now read ORB objects...
        if (!Files.exists(orbPath)) {
            System.out.println("Warning: ORB cache not found; ORB matching unavailable.");
            return db;
        }
        try (DataInputStream dis = new DataInputStream(new BufferedInputStream(new FileInputStream(orbPath.toFile())))) {
            int count = dis.readInt();
            if (count != db.length) {
                System.out.println("Warning: ORB cache count mismatch; ORB matching unavailable.");
                return db;
            }
            for (int i = 0; i < count; i++) {
                String percent = String.format("%.0f", ((double) i / count) * 100);
                System.out.print("\033[0F\033[J");
                System.out.printf("\nLoading ORB objects...%s%%",percent);
                boolean hasData = dis.readBoolean();
                if (!hasData) continue;
                int rows = dis.readInt();
                int cols = dis.readInt();
                int type = dis.readInt();
                Mat desc = new Mat(rows, cols, type);
                UByteIndexer idx = desc.createIndexer();
                for (int r = 0; r < rows; r++)
                    for (int c = 0; c < cols; c++)
                        idx.put(r, c, dis.readByte() & 0xFF);
                idx.release();
                long n = dis.readLong();
                KeyPointVector kp = new KeyPointVector(n);
                for (long k = 0; k < n; k++) {
                    kp.get(k).pt().x(dis.readFloat());
                    kp.get(k).pt().y(dis.readFloat());
                    kp.get(k).size(dis.readFloat());
                    kp.get(k).angle(dis.readFloat());
                    kp.get(k).response(dis.readFloat());
                    kp.get(k).octave(dis.readInt());
                    kp.get(k).class_id(dis.readInt());
                }
                if (db[i] != null) {
                    String pStr = db[i].getStringImgPath();
                    Path imgP = (pStr != null && !pStr.isEmpty()) ? Path.of(pStr) : null;
                    db[i] = new CardSignature(db[i].getCardID(), imgP, db[i].getBinaryHash(), desc, kp);
                }
            }
        } catch (IOException e){
            System.out.println("Warning: Failed to load ORB cache: " + e.getMessage());
        }
        return db;
    }

    private String nodeToString(FileNode n){
        if (n == null || n.isNone() || !n.isString()) return "";
        BytePointer bp = n.string();
        return bp != null ? bp.getString() : "";
    }

    private void csvOutput(String fileName, Path outputDir, List<String[]> data){
        Path dir  = outputDir.resolve("csv");
        Path file = dir.resolve(fileName);              // standardized name, no getTime()
        try {
            Files.createDirectories(dir);               // FileWriter won't make the dir for you
            boolean writeHeader = !Files.exists(file) || Files.size(file) == 0;
            try (CSVWriter writer = new CSVWriter(new FileWriter(file.toFile(), true))) { // append
                if (writeHeader) {
                    writer.writeNext(new String[]{
                            "scanId","timestamp","kind","queryImage","cardId","matchImage","score","rank"});
                }
                writer.writeAll(data);
            }
        } catch (IOException e){
            System.out.println("Sorry, couldn't write to the file." + e.getMessage());
        }
    }
    /*
    private void OLDcsvOutput(String args, Path outputDir, List<String[]> data){
        Path dir = outputDir.resolve("csv/"+getTime()+args);
        try(CSVWriter writer = new CSVWriter(new FileWriter(dir.toFile()))){
            writer.writeAll(data);
        }catch(IOException e){
            System.out.println("Sorry, couldn't write to the file."+e.getMessage());
        }
    }
    */
    private void writeToTxt(String args, List<String[]> data){
        Path dir = outputDir.resolve("logs/"+getTime()+args);
        try(PrintWriter pw = new PrintWriter(new FileWriter(dir.toFile()))){
           data.forEach(i -> pw.println(Arrays.toString(i)));
        }catch(IOException e){
            System.out.println("Sorry, this file cannot be written to. Do you have permission?");
        }
    }

    public CardSignature getCardSignature(int args){
        return cardDB[args];
    }

    public int getCardIndexSize(){
        return cardDB.length;
    }

    public String timer(long args){
        long elapsed = System.currentTimeMillis()- args;
        long hours = elapsed/3600000;
        long minutes = (elapsed%3600000)/60000;
        long seconds = (elapsed%60000)/1000;
        return String.format("%02d:%02d:%02d", hours, minutes, seconds);
    }

    private String getTime(){
        LocalDateTime currentDateTime = LocalDateTime.now();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH:mm:ss");
        return currentDateTime.format(formatter);
    }

}