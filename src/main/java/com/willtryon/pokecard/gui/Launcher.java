package com.willtryon.pokecard.gui;

import com.willtryon.pokecard.tools.KeygenTool;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public final class Launcher {
    private static final Logger logger = LogManager.getLogger(Launcher.class);
    public static void main(String[] args){
        logger.info("Pokecard v0.9.0.08\nby willtryon\n");
        final String mode = argValue(args, "--mode", "client");
        switch (mode){
            case "client" -> App.main(args);
            case "keygen-tool" -> KeygenTool.run(args);
            default -> {
                System.err.println("Unknown --mode: " + mode);
                System.err.println("Modes: client | gen-keys | build-orb | build-manifest");
                System.exit(2);
            }
        }
    }

    static String argValue(String[] args, String key, String fallback) {
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals(key) && i + 1 < args.length) return args[i + 1];
            if (args[i].startsWith(key + "=")) return args[i].substring(key.length() + 1);
        }
        return fallback;
    }
}