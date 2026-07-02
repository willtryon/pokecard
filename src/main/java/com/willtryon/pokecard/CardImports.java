package com.willtryon.pokecard;

import java.nio.file.Path;
import java.util.List;

public class CardImports {
    // one match against the DB (pHash or ORB)
    public record Match(String cardID, String img, double winner) {}

    private final Path img;
    private final Match hashMatch;
    private final Match orbMatch;
    private final List<Double> recordScore;
    private final List<CardSignature> recordRecord;
    private final List<Double> recordScore2;
    private final List<CardSignature> recordRecord2;

    public CardImports(Path img, Match hashMatch, Match orbMatch, List<Double> recordScore, List<CardSignature> recordRecord, List<Double> recordScore2, List<CardSignature> recordRecord2) {
        this.img = img;
        this.hashMatch  = hashMatch;
        this.orbMatch   = orbMatch;
        this.recordScore = recordScore;
        this.recordRecord = recordRecord;
        this.recordScore2 = recordScore2;
        this.recordRecord2 = recordRecord2;
    }

    public Path getQueryImage() {
        return img; 
    }

    public Match getHashWinner(){
        return hashMatch;
    }

    public Match getOrbWinner(){
        return orbMatch;
    }
    
    public int getRecordSize(){
        return recordScore.size();
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
        return recordRecord.get(loc);
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