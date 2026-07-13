package com.willtryon.pokecard;

import dev.brachtendorf.jimagehash.hash.Hash;
import org.bytedeco.opencv.opencv_core.KeyPointVector;
import org.bytedeco.opencv.opencv_core.Mat;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.nio.file.Path;
import java.sql.SQLException;

public class FullCardSignature extends CardSignature{
    private final int idTCGP;
    private final String name;
    private final String expIdTCGP;
    private final String expName;
    private final String expCardNumber;
    private final String expCodeTCGP;
    private final String rarity;
    private final String img;
    private float price;
    private final String description;
    private final String releaseDate;
    private final String energyType;
    private final String cardType;
    private final int pokedex;
    private final String variants;
    private final String variantMap;

    public FullCardSignature(CardSignature cardSignature, Path dbPath) throws SQLException {
        super(cardSignature.getCardID(), cardSignature.getImgPath(), cardSignature.getBinaryHash(), cardSignature.getMatData(), cardSignature.getKeypoints());
        String url = "jdbc:sqlite:" + dbPath;
        String cardID = getCardID();
        try (Connection conn = DriverManager.getConnection(url);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM cards WHERE cardId = cardID")) {
            rs.next();
            idTCGP = rs.getInt("idTCGP");
            name = rs.getString("name");
            expIdTCGP = rs.getString("expIdTCGP");
            expName = rs.getString("expName");
            expCardNumber = rs.getString("expCardNumber");
            expCodeTCGP = rs.getString("expCodeTCGP");
            rarity = rs.getString("rarity");
            img = rs.getString("img");
            price = rs.getFloat("price");
            description = rs.getString("description");
            releaseDate = rs.getString("releaseDate");
            energyType = rs.getString("energyType");
            cardType = rs.getString("cardType");
            pokedex = rs.getInt("pokedex");
            variants = rs.getString("variants");
            variantMap = rs.getString("variantMap");
        }
    }

    public String getCardID() {return super.getCardID();}
    public Path getImgPath() {return super.getImgPath();}
    public Hash getBinaryHash() {return super.getBinaryHash();}
    public Mat getMatData() {return super.getMatData();}
    public KeyPointVector getKeypoints() {return super.getKeypoints();}
    public int getIdTCGP() {return idTCGP;}
    public String getName() {return name;}
    public String getExpIdTCGP() {return expIdTCGP;}
    public String getExpName() {return expName;}
    public String getExpCardNumber() {return expCardNumber;}
    public String getExpCodeTCGP() {return expCodeTCGP;}
    public String getRarity() {return rarity;}
    public String getImg() {return img;}
    public float getPrice() {return price;}
    public String getDescription() {return description;}
    public String getReleaseDate() {return releaseDate;}
    public String getEnergyType() {return energyType;}
    public String getCardType() {return cardType;}
    public int getPokedex() {return pokedex;}
    public String getVariants() {return variants;}
    public String getVariantMap() {return variantMap;}
    public String toString(){
        return super.toString() + "\n" + "ID TGP = " + idTCGP + "\n" + "Name = " + name + "\n" + "Price = " + price;
    }



}
