package com.willtryon.pokecard;

@FunctionalInterface
public interface ScanProgress {
    /** @param message status-line text; @param fraction overall progress in [0,1] */
    void report(String message, double fraction);

}