package com.willtryon.pokecard.net;

import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public final class Downloader{
    private static final Logger logger = LogManager.getLogger(Downloader.class);

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static final String UA = "pokecard/0.9 (contact: tryonwilliama@gmail.com)";

    public static String getString(String url)throws Exception{
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", UA)
                .header("Accept", "application/json;q=0.9,*/*;q=0.8")
                .timeout(Duration.ofSeconds(30))
                .GET().build();
        HttpResponse<String> res = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
        if(res.statusCode() != 200){
            throw new IOException("GET " + url + " -> HTTP " + res.statusCode());
        }
        return res.body();
    }

    public static void getToFile(String url, Path dest)throws Exception{
        Files.createDirectories(dest.getParent());
        Path tmp = dest.resolve(dest.getFileName() + ".part");
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", UA)
                .timeout(Duration.ofMinutes(2))
                .GET().build();
        HttpResponse<Path> res = CLIENT.send(req, HttpResponse.BodyHandlers.ofFile(tmp));
        if(res.statusCode() != 200){
            throw new IOException("GET " + url + " -> HTTP " + res.statusCode());
        }
        Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);

    }
}