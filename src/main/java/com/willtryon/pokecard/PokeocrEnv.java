package com.willtryon.pokecard;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.FileNotFoundException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

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
        mustExec(List.of(basePython.toString(), "-m", ".venv", venvDir.toString()), baseDir);
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
        Map<String, String> extraEnv = env.platform() == Platform.MAC_ARM
                ? Map.of("PYTORCH_ENABLE_MPS_FALLBACK", "1")
                : Map.of();
        return exec(cmd, env.appDir(), extraEnv);
    }

    public List<String> recommendedArgs(Accel accel){
        return switch (accel) {
            // 4-bit NF4 ~1.8GB, negligible OCR accuracy loss -> best memory/accuracy tradeoff.
            case CUDA -> List.of("--engine", "qwen2.5-vl", "--device", "cuda", "--load-4bit");
            // ROCm reports as "cuda" to torch; no bitsandbytes, so fp16 (~7GB VRAM).
            case ROCM -> List.of("--engine", "qwen2.5-vl", "--device", "cuda");
            // Apple GPU: fp16, ~7GB unified memory, no 4-bit available.
            case MPS  -> List.of("--engine", "qwen2.5-vl", "--device", "mps");
            // CPU runs the VLM in fp32 (~12-14GB) and is very slow -> prefer the light detector.
            case CPU  -> List.of("--engine", "easyocr", "--device", "cpu");
        };
    }

    public static Path defaultCacheDir(){
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
        if(platform == Platform.LINUX
        && (Files.isDirectory(Path.of("/opt/rocm"))|| commandSucceeds("rocm.info"))) return Accel.ROCM;
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

    /** Returns true iff the command runs and exits 0 (used for GPU probing). */
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
        PokeocrEnv env = new PokeocrEnv(defaultCacheDir());
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
