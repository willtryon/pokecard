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
            System.out.print(prompt + " Not found. Please enter the path now: ");
            value = in.nextLine().trim();
        }
        props.setProperty(key, value);
        save();                          
        return Path.of(value);
    }

    private void save() throws IOException {
        try (OutputStream out = Files.newOutputStream(file)) {
            props.store(out, "pokecard configuration");
        }
    }
}