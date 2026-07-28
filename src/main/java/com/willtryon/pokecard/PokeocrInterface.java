package com.willtryon.pokecard;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;


public final class PokeocrInterface implements AutoCloseable{

    public record Card(int index, boolean ok, int rotation, String top, String bottom, String path, String error){}

    private final Process proc;
    private final BufferedWriter bw;
    private final BufferedReader br;
    private final Thread stderrPump;

    public PokeocrInterface(PokeocrEnv env, PokeocrEnv.EnvHandle handle) throws IOException, InterruptedException {

        List<String> args = new ArrayList<>(env.recommendedArgs(handle.accel()));
        args.add("--mode"); args.add("split"); args.add("-o");
        args.add(handle.baseDir().resolve("out").toString()); args.add("--serve");
        List<String> cmd = new ArrayList<>(List.of(handle.python().toString(), "-m", "pokeocr"));
        cmd.addAll(args);
        System.out.println("Performing "+ cmd);
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(handle.appDir().toFile());
        Map<String, String> e = pb.environment();
        if (handle.platform() == PokeocrEnv.Platform.MAC_ARM) {
            e.put("PYTORCH_ENABLE_MPS_FALLBACK", "1");
            e.put("PYTORCH_MPS_LOW_WATERMARK_RATIO", "0.7");
            e.put("PYTORCH_MPS_HIGH_WATERMARK_RATIO", "0.8");
        }
        //System.out.println(args);
        this.proc = pb.start();
        this.bw = new BufferedWriter(new OutputStreamWriter(proc.getOutputStream(), StandardCharsets.UTF_8));
        this.br = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8));

        this.stderrPump = new Thread(() -> {
            try(BufferedReader err = new BufferedReader(new InputStreamReader(proc.getErrorStream(), StandardCharsets.UTF_8))){
                for(String line; (line = err.readLine()) != null;){
                    System.err.println("[pokeocr] " + line);
                }
            }catch(IOException ignore){ }
        }, "pokeocr-stderr");
        stderrPump.setDaemon(true);
        stderrPump.start();

        awaitReady();
    }

    private void awaitReady()throws IOException, InterruptedException {
        for(String line; (line = br.readLine()) != null; ){
            if(line.equals("READY")) return;
        }
        throw new IOException("pokeocr exited before READY (exit " + proc.waitFor() + ")");
    }

    public List<Card> process(List<Path> imagePaths, ScanProgress progress) throws IOException{
        Path manifest = Files.createTempFile("pokeocr-candidates", ".txt");
        try{
            int total = imagePaths.size();

            List<String> lines = new ArrayList<>();
            for(Path p : imagePaths){
                lines.add(p.toAbsolutePath().toString());
            }
            Files.write(manifest, lines, StandardCharsets.UTF_8);
            bw.write(manifest.toAbsolutePath().toString());
            bw.newLine();
            bw.flush();
            if(progress != null){
                progress.report("Reading cards (0/"+total+")...", 0.0);
            }

            List<Card> cards = new ArrayList<>();
            for(String line; (line = br.readLine()) != null; ){
                // Protocol is TAB-delimited (CARD\ti\tOK\t...); -1 keeps trailing
                // empty fields so an empty band can't shift the path column.
                String[] split = line.split("\t", -1);
                switch(split[0]){
                    case "CARD" -> {
                        cards.add(parseCard(split));
                        // One CARD line == one card finished (OK or ERR both count).
                        if(progress != null && total > 0){
                            int done = cards.size();
                            progress.report("Reading cards ("+done+"/"+total+")...",
                                    (double) done / total);
                        }
                    }
                    case "BATCH" -> {return cards;}
                    case "MANIFEST_ERR" -> throw new IOException("manifest: " + b64(split[1]));
                    default -> {}
                }
            }
            throw new IOException("deamon closed stdout while pokeocr was working...");
        }finally {
            Files.delete(manifest);
        }
    }

    private static Card parseCard(String[] split){
        int i = Integer.parseInt(split[1]);
        if(split[2].equals("OK")){
            return new Card(i, true, Integer.parseInt(split[3]), b64(split[4]), b64(split[5]), split[6], null);
        }
        return new Card(i, false, 0, null, null, split[4], b64(split[3]));
    }

    private static String b64(String s){
        return new String(Base64.getDecoder().decode(s),  StandardCharsets.UTF_8);
    }

    @Override
    public void close()throws IOException, InterruptedException {
        try{
            bw.write("quit");
            bw.newLine();
            bw.flush();
        }catch(IOException ignore){ }
        bw.close();
        if(!proc.waitFor(30,  TimeUnit.SECONDS)){
            proc.destroyForcibly();
        }
        stderrPump.join(1000);
    }
}