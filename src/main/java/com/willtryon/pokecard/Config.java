package com.willtryon.pokecard;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.Scanner;
import java.util.function.Predicate;

public class Config {
    public static final String DB_PATH    = "db.path";
    public static final String IMAGES_DIR = "images.dir";
    public static final String COMPARE_DIR  = "compare.dir";
    public static final String OUTPUT_DIR = "output.dir";
    public static final String CACHE_DIR =  "cache.dir";
    public static final String EBAY_API_KEY = "ebay.apiKey";
    public static final String SCAN_THREADS = "scan.threads";
    public static final String LAST_SESSION = "session.name";

    private final Path file;
    private final Properties props = new Properties();

    public Config(Path file) throws IOException {
        this.file = file;
        if (Files.exists(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                props.load(in);
            }
        }
    }

    public Path require(String key, String prompt, Predicate<Path> valid, Scanner in) throws IOException{
        String value = props.getProperty(key, "").trim();
        while (value.isBlank() || !valid.test(Path.of(value))) {
            if (!value.isBlank()) {
                System.out.println("  '" + value + "' isn't valid for " + key + ".");
            }
            System.out.println(prompt + " not found. Please enter the path and press enter.");
            value = in.nextLine().trim();
        }
        props.setProperty(key, value);
        save();                          
        return Path.of(value);
    }

    public void save() throws IOException {
        try (OutputStream out = Files.newOutputStream(file)) {
            props.store(out, "pokecard configuration");
        }
    }

    public String get(String key){
        return props.getProperty(key, "").trim();
    }

    public boolean isValid(String key, Predicate <Path> valid){
        String v = get(key);
        return !v.isBlank() && valid.test(Path.of(v));
    }

    public int getScanThreads() {
        int cores = Runtime.getRuntime().availableProcessors();
        int fallback = Math.max(1, cores - 1);   // leave one core for the UI/OS
        String v = get(SCAN_THREADS);
        if (v.isBlank()) return fallback;
        try {
            int n = Integer.parseInt(v);
            if (n < 1) return 1;
            return Math.min(n, cores);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public void set(String key, String value){
        props.setProperty(key, value == null ? "" : value.trim());
    }
}
