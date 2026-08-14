package com.willtryon.pokecard;

import dev.brachtendorf.jimagehash.hash.Hash;
import org.bytedeco.opencv.opencv_core.KeyPointVector;
import org.bytedeco.opencv.opencv_core.Mat;

import java.sql.*;
import java.nio.file.Path;

import static com.willtryon.pokecard.CardImportsIndex.globalCardVersion;

public class FullCardSignature extends CardSignature {
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
    private String cardVersion;
    private final boolean firstEdition;

    public FullCardSignature(CardSignature cardSignature, Path dbPath, Path cacheDir, String cardVersion, boolean firstEdition) throws SQLException {
        super(cardSignature.getCardID(), cardSignature.getImgPath(), cardSignature.getBinaryHash(),
                cardSignature.getMatData(), cardSignature.getKeypoints());
        this.cardVersion = cardVersion;
        this.firstEdition = firstEdition;
        String url = "jdbc:sqlite:" + dbPath;
        String cardID = getCardID();
        try (Connection conn = DriverManager.getConnection(url);
             PreparedStatement ps = conn.prepareStatement("SELECT * FROM cards WHERE cardId = ?")) {
            ps.setString(1, cardID);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()){
                    throw new SQLException("No card found with cardId = " + cardID);
                }
                idTCGP = rs.getInt("idTCGP");
                name = rs.getString("name");
                expIdTCGP = rs.getString("expIdTCGP");
                expName = rs.getString("expName");
                expCardNumber = rs.getString("expCardNumber");
                expCodeTCGP = rs.getString("expCodeTCGP");
                rarity = rs.getString("rarity");
                img = rs.getString("img");
                price = calculatePrice(cacheDir, dbPath);
                description = rs.getString("description");
                releaseDate = rs.getString("releaseDate");
                energyType = rs.getString("energyType");
                cardType = rs.getString("cardType");
                pokedex = rs.getInt("pokedex");
                variants = rs.getString("variants");
                variantMap = rs.getString("variantMap");
            }
        }
    }

    public String getCardID() {
        return super.getCardID();
    }

    public Path getImgPath() {
        return super.getImgPath();
    }

    public Hash getBinaryHash() {
        return super.getBinaryHash();
    }

    public Mat getMatData() {
        return super.getMatData();
    }

    public KeyPointVector getKeypoints() {
        return super.getKeypoints();
    }

    public int getIdTCGP() {return idTCGP;}

    public String getName() { return name;}

    public String getExpIdTCGP() {
        return expIdTCGP;
    }

    public String getExpName() {
        return expName;
    }

    public String getExpCardNumber() {
        return expCardNumber;
    }

    public String getExpCodeTCGP() {
        return expCodeTCGP;
    }

    public String getRarity() {
        return rarity;
    }

    public String getImg() {
        return img;
    }

    public float getPrice() {
        return price;
    }

    public float calculatePrice(Path cacheDir, Path dbPath) throws SQLException{
        String url = "jdbc:sqlite:" + cacheDir.resolve("tcg.db");
        if(firstEdition){
            switch (cardVersion){
                case "NORMAL" -> cardVersion = "1ST EDITION";
                case "HOLOFOIL" -> cardVersion = "1ST EDITION HOLOFOIL";
            }
        }
        String sql = "SELECT pr.sub_type, pr.market_price, pr.mid_price, pr.low_price " +
                "FROM iddb.cards ic " +
                "JOIN products p ON p.product_id = ic.idTCGP " +
                "JOIN prices pr ON pr.product_id = p.product_id " +
                "WHERE ic.idTCGP = ?"+
                "   AND pr.sub_type = ? COLLATE NOCASE";

        try (Connection conn = DriverManager.getConnection(url);
             Statement st = conn.createStatement()) {

            st.execute("ATTACH DATABASE '" + dbPath + "' AS iddb");

            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, String.valueOf(getIdTCGP()));
                ps.setString(2, getCardVersion());

                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        String subType = rs.getString("sub_type");
                        System.out.println("\n"+subType);
                        float marketPrice = rs.getFloat("market_price");
                        System.out.print("\tMarket Price: "+marketPrice);
                        float lowPrice = rs.getFloat("low_price");
                        System.out.print("\tLow Price: "+lowPrice);
                        float midPrice = rs.getFloat("mid_price");
                        System.out.print("\tMid Price: "+midPrice);
                        price = Math.max(marketPrice, Math.max(lowPrice, midPrice));
                    }else{
                        rs.close();
                        System.out.println("No data found, trying unlimited...");
                        switch(cardVersion){
                            case "NORMAL" -> cardVersion = "UNLIMITED";
                            case "HOLOFOIL" -> cardVersion = "UNLIMITED HOLOFOIL";
                        }
                        ps.setString(2, getCardVersion());
                        try(ResultSet rs2 = ps.executeQuery()) {
                            if (rs2.next()) {
                                String subType = rs.getString("sub_type");
                                System.out.println(subType);
                                float marketPrice = rs.getFloat("market_price");
                                System.out.print("\tMarket Price: "+marketPrice);
                                float lowPrice = rs.getFloat("low_price");
                                System.out.print("\tLow Price: "+lowPrice);
                                float midPrice = rs.getFloat("mid_price");
                                System.out.print("\tMid Price: "+midPrice);
                                price = Math.max(marketPrice, Math.max(lowPrice, midPrice));
                            }else {
                                rs2.close();
                                System.out.println("No price found...");
                            }
                        }

                    }
                }
            }
        }
        return price;
    }

    public String getDescription() {
        return description;
    }

    public String getReleaseDate() {
        return releaseDate;
    }

    public String getEnergyType() {
        return energyType;
    }

    public String getCardType() {
        return cardType;
    }

    public int getPokedex() {
        return pokedex;
    }

    public String getVariants() {
        return variants;
    }

    public String getVariantMap() {
        return variantMap;
    }

    public String getCardVersion() {return cardVersion;}


    public String toString() {
        return super.toString() + "\n" + "ID TGP = " + idTCGP + "\n" + "Name = " + name + "\n" + "Price = " + price;
    }
}
