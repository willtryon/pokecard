package com.willtryon.pokecard;

import dev.brachtendorf.jimagehash.hash.Hash;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;

import java.nio.file.Path;
import java.util.List;


@SuppressWarnings({"LombokGetterMayBeUsed", "LombokSetterMayBeUsed"})
public class CardImports {
    // one match against the DB (pHash or ORB)
    public record Match(String cardID, String img, double winner) {}

    private final Path img;
    private String cardVersion;
    private boolean firstEdition;
    private boolean isFinal;
    private boolean matchOverride;
    private float price;
    private String cat;
    private final Hash qHash;
    private final Match hashMatch;
    private final Match orbMatch;
    private Match ocrMatch;
    private Match bestMatch;
    private final List<Double> recordScore;
    private final List<CardSignature> recordRecord;
    private final List<Double> recordScore2;
    private final List<CardSignature> recordRecord2;
    private final BooleanProperty selected = new SimpleBooleanProperty(false);



    public CardImports(Path img, String cardVersion, boolean firstEdition, boolean isFinal, boolean matchOverride, float price, String cat, Hash qHash, Match hashMatch, Match orbMatch, Match ocrMatch, Match bestMatch, List<Double> recordScore, List<CardSignature> recordRecord, List<Double> recordScore2, List<CardSignature> recordRecord2) {
        this.img = img;
        this.cardVersion = cardVersion;
        this.firstEdition = firstEdition;
        this.isFinal = isFinal;
        this.matchOverride = matchOverride;
        this.price = price;
        this.cat = cat;
        this.qHash = qHash;
        this.hashMatch  = hashMatch;
        this.orbMatch   = orbMatch;
        this.ocrMatch = ocrMatch;
        this.bestMatch = bestMatch;
        this.recordScore = recordScore;
        this.recordRecord = recordRecord;
        this.recordScore2 = recordScore2;
        this.recordRecord2 = recordRecord2;
    }

    public Path getQueryImage() {
        return img; 
    }

    public String getCardVersion(){
        return cardVersion;
    }

    public void setCardVersion(String cardVersion){
        this.cardVersion = cardVersion;
    }

    public boolean getFirstEdition(){
        return firstEdition;
    }

    public void setFirstEdition(boolean firstEdition){
        this.firstEdition = firstEdition;
    }

    public Hash getQueryHash() {return qHash;}

    public Match getHashWinner(){
        return hashMatch;
    }

    public Match getOrbWinner(){
        return orbMatch;
    }

    public Match getOcrWinner(){
        return ocrMatch;
    }

    public void setOcrWinner(Match m){
        this.ocrMatch = m;
    }

    public boolean hasOcr(){
        return ocrMatch != null && ocrMatch.cardID() != null;
    }

    public Match bestMatch(){
        return (ocrMatch != null && ocrMatch.cardID() != null) ? ocrMatch : orbMatch;
    }
    
    public int getRecordSize(){
        return recordScore.size();
    }

    public int getRecordSize2(){
        return recordScore2.size();
    }

    public Double getARecordScore(int loc, String args){
        if(args.equals("hash")){
            return recordScore.get(loc);
        }
        return recordScore2.get(loc);
    }

    public CardSignature getARecordRecord(int loc, String args){
        if(args.equals("hash")){
            return recordRecord.get(loc);
        }
        return recordRecord2.get(loc);
    }

    public String getHashedRecordHistory(){
        StringBuilder sb = new StringBuilder();
        for(int i = 0; i<recordScore.size();i++){
            sb.append("\n"+recordRecord.get(i).getCardID()+" "+recordRecord.get(i).getStringImgPath()+" "+recordScore.get(i));
        }
        return sb.toString();
    }

    public String getORBRecordHistory(){
        StringBuilder sb = new StringBuilder();
        for(int i = 0; i<recordScore2.size();i++){
            sb.append("\n"+recordRecord2.get(i).getCardID()+" "+recordRecord2.get(i).getStringImgPath()+" "+recordScore2.get(i));
        }
        return sb.toString();
    }

    public int howLowIsHash(){
        String subject = orbMatch.cardID();
        for(int c = 0; c<recordRecord.size();c++){
            if(!(recordRecord.get(c).getCardID().equals(subject))){
               continue;
            }
            return c;
        }
        return -1;
    }

    public BooleanProperty selectedProperty() { return selected; }

    public boolean getFinal() { return isFinal; }

    public void setFinal(boolean isFinal){
        this.isFinal = isFinal;
    }

    public boolean getMatchOverride() { return matchOverride; }

    public void setMatchOverride(boolean matchOverride) { this.matchOverride = matchOverride; }

    public float getPrice() {
        return price;
    }

    public void setPrice(float price) {
        this.price = price;
    }

    public Match getBestMatch() {
        return bestMatch;
    }

    public void setBestMatch(Match bestMatch) {
        this.bestMatch = bestMatch;
    }

    public String getCat() {
        return cat;
    }

    public void setCat(String cat) {
        this.cat = cat;
    }

    public String[][] toCsvRows() {
        String q = img.toString();
        return new String[][]{
            { q, hashMatch.cardID(), hashMatch.img(), Double.toString(hashMatch.winner())},
            {},
            { q, orbMatch.cardID(),  orbMatch.img(),  Double.toString(orbMatch.winner())},
            {recordRecord2.get(1).getCardID(), recordRecord2.get(1).getStringImgPath(), Double.toString(recordScore2.get(1))},
            {recordRecord2.get(2).getCardID(), recordRecord2.get(2).getStringImgPath(), Double.toString(recordScore2.get(2))},
            {}
        };
    }
}