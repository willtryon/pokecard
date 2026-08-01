package com.willtryon.pokecard;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.FileNotFoundException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Stream;

public final class PokeocrEnv{

    private static final String RESOURCE_ROOT = "/python";
    private static final String DEPS_VERSION = "2026-07-26";
    private static final List<String> BASE_DEPS = List.of(
            "easyocr", "reportlab", "pillow", "numpy",
            "transformers>=4.49,<5", "sentencepiece", "accelerate",
            "safetensors", "einops", "tiktoken");
    private static final Set<String> SKIP = Set.of(
            "out", "out2", "scans", "__pycache__", ".venv", ".claude");
    public enum Platform{MAC_ARM, MAC_X86, LINUX, WINDOWS}
    public enum Accel{CUDA, ROCM, MPS, CPU}
    public record EnvHandle(Path baseDir, Path appDir, Path venvDir, Path python, Platform platform, Accel accel){}
    private final Path baseDir;
    private final Consumer<String> log;

    public PokeocrEnv(Path baseDir){
        this(baseDir, System.out::println);
    }

    public PokeocrEnv(Path baseDir, Consumer<String> log){
        this.baseDir = baseDir;
        this.log = log;
    }

    public EnvHandle prepare() throws IOException, InterruptedException, URISyntaxException{
        Platform platform = detectPlatform();
        Accel accel = detectAccel(platform);
        log.accept("\npokeocr Environment");
        log.accept("Detected platform:" + platform + " accelerator:"+accel);

        Path appDir = baseDir.resolve("app");
        Path venvDir = baseDir.resolve("venv");
        Path python = venvPython(venvDir, platform);

        Files.createDirectories(baseDir);
        extractPython(appDir);

        if(envIsReady(venvDir, accel)){
            log.accept("Found python virtual environment.");
            return new EnvHandle(baseDir, appDir, venvDir, python, platform, accel);
        }

        Path basePython = findBasePython();
        log.accept("Creating venv at "+venvDir);
        mustExec(List.of(basePython.toString(), "-m", "venv", venvDir.toString()), baseDir);
        mustExec(List.of(python.toString(), "-m", "pip", "install", "--upgrade", "pip"), baseDir);

        List<String> torchCmd = new ArrayList<>(List.of(python.toString(), "-m", "pip", "install"));
        torchCmd.addAll(torchPipArgs(platform, accel));
        log.accept("Installing torch/torchvision: " + String.join(" ", torchPipArgs(platform, accel)));
        mustExec(torchCmd, baseDir);

        List<String> deepCmd = new ArrayList<>(List.of(python.toString(), "-m", "pip", "install"));
        deepCmd.addAll(BASE_DEPS);
        mustExec(deepCmd, baseDir);

        if(accel == Accel.CPU){
            mustExec(List.of(python.toString(), "-m", "pip", "install", "bitsandbytes"), baseDir);
        }

        markReady(venvDir, accel);
        log.accept("Python virtual environment ready.");
        return new EnvHandle(baseDir, appDir, venvDir, python, platform, accel);
    }

    public int run(EnvHandle env, List<String> pokeocrArgs)throws IOException, InterruptedException{
        List<String> cmd = new ArrayList<>(List.of(env.python().toString(), "-m", "pokeocr"));
        cmd.addAll(pokeocrArgs);
        Map<String, String> extraEnv = new HashMap<>();
        if(env.platform() == Platform.MAC_ARM){
            extraEnv.put("PYTORCH_ENABLE_MPS_FALLBACK", "1");
            // PyTorch's MPS allocator defaults to a 1.7 high-watermark ratio, i.e. it
            // lets a process allocate ~1.7x the GPU's recommended working set and
            // oversubscribe past physical RAM into swap. got-ocr2's 1024x1024 vision
            // forward peaks near ~7GB per image; across the 3 split bands the uncapped
            // allocator climbs to ~20GB and swaps the machine to a halt. Cap it below
            // physical RAM: the allocator then reuses memory between bands (measured
            // flat at ~7GB) and a genuine over-allocation becomes a clean OOM instead
            // of a swap-storm. Ratios are relative to the recommended working set, so
            // this scales with the machine. Low must be <= high.
            extraEnv.put("PYTORCH_MPS_LOW_WATERMARK_RATIO", "0.7");
            extraEnv.put("PYTORCH_MPS_HIGH_WATERMARK_RATIO", "0.8");
        }
        // python.org framework builds ship no OS CA bundle, so EasyOCR's plain
        // urllib model download fails cert verification ("unable to get local
        // issuer certificate"). Point OpenSSL/requests at certifi's bundle (present
        // in the venv) so downloads work without the user running the interpreter's
        // "Install Certificates.command".
        String caBundle = certifiBundle(env.python());
        if(caBundle != null){
            extraEnv.put("SSL_CERT_FILE", caBundle);
            extraEnv.put("REQUESTS_CA_BUNDLE", caBundle);
        }
        return exec(cmd, env.appDir(), extraEnv);
    }

    public List<String> recommendedArgs(Accel accel){
        return switch (accel) {
            // 4-bit NF4 ~1.8GB, negligible OCR accuracy loss -> best memory/accuracy tradeoff.
            case CUDA -> List.of("--engine", "qwen2.5-vl", "--device", "cuda", "--load-4bit");
            // ROCm reports as "cuda" to torch; no bitsandbytes, so fp16 (~7GB VRAM).
            case ROCM -> List.of("--engine", "qwen2.5-vl", "--device", "cuda");
            // Apple GPU: got-ocr2 fp16. Its 1024x1024 vision encoder does O(N^2)
            // attention over 4096 tokens; MPS lacks the memory-efficient SDPA kernel
            // CUDA uses, so that matrix is materialized in full. Force batch=1 to keep
            // the peak to a single image's worth (batch 2 spikes past ~14GB).
            //case MPS  -> List.of("--engine", "trocr", "--device", "mps");
            case MPS  -> List.of("--engine", "qwen2.5-vl", "--device", "mps", "--batch", "1");
            //case MPS  -> List.of("--engine", "got-ocr2", "--device", "mps", "--batch", "1");
            // CPU: serve/split needs a whole-image VLM (easyocr is single-line and argparse
            // rejects it), so fall back to got-ocr2 — the smallest VLM (0.58B, ~2.3GB fp32)
            // rather than qwen (~12-14GB fp32). Functional but slow; real use wants a GPU.
            case CPU  -> List.of("--engine", "got-ocr2", "--device", "cpu");
        };
    }

    public static Path ocrDefaultCacheDir(){
        String home = System.getProperty("user.home");
        String os =  System.getProperty("os.name").toLowerCase(Locale.ROOT);
        Path base;
        if(os.contains("win")){
            String appData = System.getenv("LOCALAPPDATA");
            base = appData != null ? Path.of(appData) : Path.of(home, "AppData", "Local");
        }else if(os.contains("mac")||os.contains("darwin")){
            base = Path.of(home, "Library", "Application Support");
        }else{
            String xdg = System.getenv("XDG_CACHE_HOME");
            base = xdg != null ? Path.of(xdg) : Path.of(home, ".cache");
        }
        return base.resolve("pokecard").resolve("pyenv");
    }

    private static Platform detectPlatform(){
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        boolean arm = arch.contains("aarch64") || arch.contains("arm");
        if(os.contains("mac")||os.contains("darwin")){
            return arm ? Platform.MAC_ARM : Platform.MAC_X86;
        }
        if(os.contains("win")){
            return Platform.WINDOWS;
        }
        return Platform.LINUX;
    }

    private static Accel detectAccel(Platform platform){
        if(platform == Platform.MAC_ARM) return Accel.MPS;
        if(platform == Platform.MAC_X86) return Accel.CPU;
        if(commandSucceeds("nvidia-smi", "-L")) return Accel.CUDA;
        // ROCm probes: /opt/rocm is the Ubuntu/official-installer layout; Fedora/Nobara
        // package ROCm under /usr instead, so also probe the CLI tools. rocminfo returning
        // 0 means the ROCm runtime actually sees a supported GPU (was misspelled "rocm.info").
        if(platform == Platform.LINUX
        && (Files.isDirectory(Path.of("/opt/rocm"))
            || commandSucceeds("rocminfo")
            || commandSucceeds("rocm-smi"))) return Accel.ROCM;
        return Accel.CPU;
    }

    private static List<String> torchPipArgs(Platform platform, Accel accel){
        return switch (accel) {
            case CUDA -> List.of("torch", "torchvision",
                    "--index-url", "https://download.pytorch.org/whl/cu124");
            case ROCM -> List.of("torch", "torchvision",
                    "--index-url", "https://download.pytorch.org/whl/rocm6.4");
            case MPS  -> List.of("torch", "torchvision");
            case CPU  -> (platform == Platform.MAC_ARM || platform == Platform.MAC_X86)
                    ? List.of("torch", "torchvision")
                    : List.of("torch", "torchvision",
                    "--index-url", "https://download.pytorch.org/whl/cpu");
        };
    }

    private static Path venvPython(Path venv, Platform platform){
        return platform == Platform.WINDOWS
                ? venv.resolve("Scripts").resolve("Python.exe")
                : venv.resolve("bin").resolve("python");
    }

    private Path findBasePython()throws IOException{
        List<String> candidates = detectPlatform() == Platform.WINDOWS
                ? List.of("py", "python", "python3")
                : List.of("python3.12", "python3", "python");
        for (String c : candidates) {
            int[] v = pythonVersion(c);
            if (v != null && v[0] == 3 && v[1] >= 9 && v[1] <= 13) {
                log.accept("Using base interpreter '" + c + "' (" + v[0] + "." + v[1] + ")");
                return Path.of(c);
            }
            if (v != null) {
                log.accept("Skipping '" + c + "' " + v[0] + "." + v[1]
                        + " (torch 2.6 needs Python 3.9-3.13)");
            }
        }
        throw new IOException("No compatible Python (3.9-3.13) found on PATH. Install e.g. "
                + "Python 3.12, or bundle the `uv` binary and let it provision an interpreter.");
    }

    private int[] pythonVersion(String exe) {
        try {
            Process proc = new ProcessBuilder(exe, "-c",
                    "import sys;print('%d %d' % sys.version_info[:2])")
                    .redirectErrorStream(true).start();
            String out = new String(proc.getInputStream().readAllBytes()).strip();
            if (proc.waitFor() != 0 || out.isEmpty()) return null;
            String[] parts = out.split("\\s+");
            return new int[]{Integer.parseInt(parts[0]), Integer.parseInt(parts[1])};
        } catch (IOException | InterruptedException | RuntimeException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return null;
        }
    }

    /** Absolute path to certifi's CA bundle inside the venv, or null if unavailable. */
    private String certifiBundle(Path python) {
        try {
            Process proc = new ProcessBuilder(python.toString(), "-c",
                    "import certifi;print(certifi.where())")
                    .redirectErrorStream(true).start();
            String out = new String(proc.getInputStream().readAllBytes()).strip();
            if (proc.waitFor() != 0 || out.isEmpty()) return null;
            // Take the last line in case an import warning is printed first.
            String[] lines = out.split("\\R");
            return lines[lines.length - 1].strip();
        } catch (IOException | InterruptedException | RuntimeException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return null;
        }
    }

    private void extractPython(Path dest) throws IOException, URISyntaxException {
        URL res = PokeocrEnv.class.getResource(RESOURCE_ROOT);
        if (res == null) {
            throw new FileNotFoundException("Bundled python not found on classpath: " + RESOURCE_ROOT);
        }
        URI uri = res.toURI();
        if ("jar".equals(uri.getScheme())) {
            // e.g. jar:file:/path/pokecard.jar!/python  ->  filesystem is the part before "!/"
            String full = uri.toString();
            URI fsUri = URI.create(full.substring(0, full.indexOf("!/")));
            FileSystem fs;
            boolean created = false;
            try {
                fs = FileSystems.newFileSystem(fsUri, Map.of());
                created = true;
            } catch (FileSystemAlreadyExistsException e) {
                fs = FileSystems.getFileSystem(fsUri);
            }
            try {
                copyTree(fs.getPath(RESOURCE_ROOT), dest);
            } finally {
                if (created) fs.close();
            }
        } else {
            // Running from exploded target/classes (IDE / mvn javafx:run).
            copyTree(Paths.get(uri), dest);
        }
    }

    private void copyTree(Path source, Path dest) throws IOException {
        List<Path> entries = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(source)) {
            entries = walk.toList();
        }
        for (Path entry : entries) {
            Path rel = source.relativize(entry);
            if(isSkipped(rel)) continue;
            Path target = dest;
            for(Path seg: rel) target = target.resolve(seg.toString());
            if(Files.isDirectory(entry)){
                Files.createDirectories(target);
            }else{
                Files.createDirectories(target.getParent());
                Files.copy(entry, target,  StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static boolean isSkipped(Path rel){
        for (Path seg : rel) {
            if (SKIP.contains(seg.toString())) return true;
        }
        return false;
    }

    private boolean envIsReady(Path venvDir, Accel accel)throws IOException{
        Path m = markerFile(venvDir);
        return Files.isReadable(m) && Files.readString(m).strip().equals(stamp(accel));
    }

    private void markReady(Path venvDir, Accel accel)throws IOException{
        Files.writeString(markerFile(venvDir), stamp(accel));
    }

    private static Path markerFile(Path venvDir) {
        return venvDir.resolve(".pokeocr-ready");
    }

    private static String stamp(Accel accel) {
        return DEPS_VERSION + ":" + accel;
    }

    private void mustExec(List<String> cmd, Path cwd)throws IOException, InterruptedException {
        int code = exec(cmd, cwd, null);
        if(code != 0){
            throw new IOException("Command failed (exit " + code + "): " + String.join(" ", cmd));
        }
    }

    private int exec(List<String> cmd, Path cwd, Map<String, String> extraEnv)
            throws IOException, InterruptedException {
        log.accept("$ " + String.join(" ", cmd));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        if (cwd != null) pb.directory(cwd.toFile());
        if (extraEnv != null && !extraEnv.isEmpty()) pb.environment().putAll(extraEnv);
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
            String line;
            while ((line = r.readLine()) != null) log.accept(line);
        }
        return proc.waitFor();
    }

    /** Returns true if the command runs and exits 0 (used for GPU probing). */
    private static boolean commandSucceeds(String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            p.getInputStream().readAllBytes(); // drain so the process doesn't block on a full pipe
            return p.waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return false; // command not found / not runnable
        }
    }

    // --------------------------------------------------------------- demo

    /** Minimal example. Pass image files/globs (absolute paths) as program arguments. */
    public static void main(String[] args) throws Exception {
        PokeocrEnv env = new PokeocrEnv(ocrDefaultCacheDir());
        EnvHandle handle = env.prepare();            // first run downloads torch + weights (slow)

        if (args.length == 0) {
            System.out.println("Usage: PokeOcrEnv <image-or-glob> [more images ...]");
            return;
        }

        List<String> pokeArgs = new ArrayList<>(env.recommendedArgs(handle.accel()));
        pokeArgs.add("--mode");
        pokeArgs.add("split");
        pokeArgs.add("-o");
        pokeArgs.add(handle.baseDir().resolve("out").toString());
        pokeArgs.addAll(List.of(args));
        int code = env.run(handle, pokeArgs);
        System.out.println("pokeocr exited with " + code);
    }



}
