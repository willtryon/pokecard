package com.willtryon.pokecard;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Stream;

public final class TcgdbEnv {
    private static final String RESOURCE_ROOT = "/tcgdb";
    private static final String DEPS_VERSION = "2026-07-31";
    private static final List<String> DEPS = List.of("requests");
    private static final Set<String> SKIP = Set.of("__pycache__");

    public enum Platform { MAC_ARM, MAC_X86, LINUX, WINDOWS }
    public record EnvHandle(Path baseDir, Path appDir, Path venvDir, Path python, Platform platform) {}

    private final Path baseDir;
    private final Consumer<String> log;

    public TcgdbEnv(Path baseDir) {
        this(baseDir, System.out::println);
    }

    public TcgdbEnv(Path baseDir, Consumer<String> log) {
        this.baseDir = baseDir;
        this.log = log;
    }

    public EnvHandle prepare() throws IOException, InterruptedException, URISyntaxException {
        Platform platform = detectPlatform();
        log.accept("\nTCGDB Environment");
        log.accept("Detected platform: " + platform);

        Path appDir = baseDir.resolve("app");
        Path venvDir = baseDir.resolve("venv");
        Path python = venvPython(venvDir, platform);

        Files.createDirectories(baseDir);
        extractScript(appDir);

        if (envIsReady(venvDir)) {
            log.accept("Found python virtual environment.");
            return new EnvHandle(baseDir, appDir, venvDir, python, platform);
        }

        Path basePython = findBasePython();
        log.accept("Creating venv at " + venvDir);
        mustExec(List.of(basePython.toString(), "-m", "venv", venvDir.toString()), baseDir);
        mustExec(List.of(python.toString(), "-m", "pip", "install", "--upgrade", "pip"), baseDir);

        List<String> installCmd = new ArrayList<>(List.of(python.toString(), "-m", "pip", "install"));
        installCmd.addAll(DEPS);
        mustExec(installCmd, baseDir);

        markReady(venvDir);
        log.accept("Python virtual environment ready.");
        return new EnvHandle(baseDir, appDir, venvDir, python, platform);
    }

    public int sync(EnvHandle env, Path dbPath, boolean force) throws IOException, InterruptedException {
        List<String> args = new ArrayList<>(List.of("sync"));
        if (force) args.add("--force");
        return run(env, args, dbPath);
    }


    public int run(EnvHandle env, List<String> tcgdbArgs, Path dbPath) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>(List.of(env.python().toString(), "-m", "tcgdb"));
        cmd.addAll(tcgdbArgs);
        Map<String, String> extraEnv = new HashMap<>();
        extraEnv.put("TCGDB_PATH", dbPath.toAbsolutePath().toString());
        Files.createDirectories(dbPath.toAbsolutePath().getParent());
        return exec(cmd, env.appDir(), extraEnv);
    }

    public static Path tcgdbDefaultCacheDir() {
        String home = System.getProperty("user.home");
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        Path base;
        if (os.contains("win")) {
            String appData = System.getenv("LOCALAPPDATA");
            base = appData != null ? Path.of(appData) : Path.of(home, "AppData", "Local");
        } else if (os.contains("mac") || os.contains("darwin")) {
            base = Path.of(home, "Library", "Application Support");
        } else {
            String xdg = System.getenv("XDG_CACHE_HOME");
            base = xdg != null ? Path.of(xdg) : Path.of(home, ".cache");
        }
        // separate leaf from pokeocr's "pyenv" so the two venvs never collide
        return base.resolve("pokecard").resolve("tcgdb");
    }

    private static Platform detectPlatform() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        boolean arm = arch.contains("aarch64") || arch.contains("arm");
        if (os.contains("mac") || os.contains("darwin")) {
            return arm ? Platform.MAC_ARM : Platform.MAC_X86;
        }
        if (os.contains("win")) {
            return Platform.WINDOWS;
        }
        return Platform.LINUX;
    }

    private static Path venvPython(Path venv, Platform platform) {
        return platform == Platform.WINDOWS
                ? venv.resolve("Scripts").resolve("python.exe")
                : venv.resolve("bin").resolve("python");
    }

    private Path findBasePython() throws IOException {
        List<String> candidates = detectPlatform() == Platform.WINDOWS
                ? List.of("py", "python", "python3")
                : List.of("python3.12", "python3", "python");
        for (String c : candidates) {
            int[] v = pythonVersion(c);
            if (v != null && v[0] == 3 && v[1] >= 9) {
                log.accept("Using base interpreter '" + c + "' (" + v[0] + "." + v[1] + ")");
                return Path.of(c);
            }
        }
        throw new IOException("No compatible Python (3.9+) found on PATH. Install e.g. Python 3.12.");
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

    private void extractScript(Path dest) throws IOException, URISyntaxException {
        URL res = TcgdbEnv.class.getResource(RESOURCE_ROOT);
        if (res == null) {
            throw new FileNotFoundException("Bundled tcgdb not found on classpath: " + RESOURCE_ROOT);
        }
        URI uri = res.toURI();
        if ("jar".equals(uri.getScheme())) {
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
        List<Path> entries;
        try (Stream<Path> walk = Files.walk(source)) {
            entries = walk.toList();
        }
        for (Path entry : entries) {
            Path rel = source.relativize(entry);
            if (isSkipped(rel)) continue;
            Path target = dest;
            for (Path seg : rel) target = target.resolve(seg.toString());
            if (Files.isDirectory(entry)) {
                Files.createDirectories(target);
            } else {
                Files.createDirectories(target.getParent());
                Files.copy(entry, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static boolean isSkipped(Path rel) {
        for (Path seg : rel) {
            if (SKIP.contains(seg.toString())) return true;
        }
        return false;
    }

    private boolean envIsReady(Path venvDir) throws IOException {
        Path m = markerFile(venvDir);
        return Files.isReadable(m) && Files.readString(m).strip().equals(DEPS_VERSION);
    }

    private void markReady(Path venvDir) throws IOException {
        Files.writeString(markerFile(venvDir), DEPS_VERSION);
    }

    private static Path markerFile(Path venvDir) {
        return venvDir.resolve(".tcgdb-ready");
    }

    private void mustExec(List<String> cmd, Path cwd) throws IOException, InterruptedException {
        int code = exec(cmd, cwd, null);
        if (code != 0) {
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

    public static void main(String[] args) throws Exception {
        TcgdbEnv env = new TcgdbEnv(tcgdbDefaultCacheDir());
        EnvHandle handle = env.prepare();
        Path db = tcgdbDefaultCacheDir().resolve("tcg.db");
        int code = env.sync(handle, db, false);
        System.out.println("tcgdb sync exited " + code + "; db at " + db);
    }
}
