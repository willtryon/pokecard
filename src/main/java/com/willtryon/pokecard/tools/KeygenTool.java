package com.willtryon.pokecard.tools;

import java.io.IOException;
import java.nio.file.*;
import java.security.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public final class KeygenTool{
    private static final Logger log = LogManager.getLogger(KeygenTool.class);

    public static void run(String[] args){
        log.info("You are in KeyGenTool mode.");
        log.debug("Generating the keys.");
        try {
            KeyPair kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            Path out = Path.of(System.getProperty("user.home"), ".pokecard/"+"keys");
            Files.createDirectories(out);
            Files.write(out.resolve("pokecard_public.der"), kp.getPublic().getEncoded());
            Files.write(out.resolve("pokecard_private.der"), kp.getPrivate().getEncoded());
            log.info("Wrote keys to {}",out);

        }catch(NoSuchAlgorithmException | IOException e){
            log.error(e.getMessage(),e);
        }finally{
            log.info("The operation completed successfully.");
        }
    }
}