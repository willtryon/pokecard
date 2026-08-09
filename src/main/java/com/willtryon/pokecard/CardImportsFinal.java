package com.willtryon.pokecard;

import java.nio.file.Path;

public class CardImportsFinal{

    private Path img;
    private FullCardSignature winningCard;

    public CardImportsFinal(Path img, FullCardSignature winningCard) {
        this.img = img;
        this.winningCard = winningCard;
    }

    public FullCardSignature getWinningCard() {
        return winningCard;
    }

}