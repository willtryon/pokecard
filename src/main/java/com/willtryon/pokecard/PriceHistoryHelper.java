package com.willtryon.pokecard;

import java.nio.file.Path;
import java.sql.*;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public final class PriceHistoryHelper{
    public record Point(LocalDate date, float low, float mid, float market){}

    private static final String SQL = """
            SELECT substr(as_of,1,10) AS d, low_price, mid_price, market_price
            FROM price_history
            WHERE product_id = ?
              AND sub_type = ? COLLATE NOCASE
            ORDER BY as_of
            """;

    private PriceHistoryHelper(){}

    public static List<Point> load(Path cacheDir, int productId, String subType)throws SQLException{
        List<Point> out = query(cacheDir, productId, subType);
        if(out.isEmpty()){
            String alt = switch(subType.toUpperCase()){
                case "NORMAL" -> "UNLIMITED";
                case "HOLOFOIL" -> "UNLIMITED HOLOFOIL";
                default -> null;
            };
            if(alt != null) out = query(cacheDir, productId, subType);
        }
        return out;
    }

    private static List<Point> query(Path cacheDir, int productId, String subType) throws SQLException{
        List<Point> pts = new ArrayList<>();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + cacheDir.resolve("tcg.db"));
             PreparedStatement ps = c.prepareStatement(SQL)) {
            ps.setInt(1, productId);
            ps.setString(2, subType);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    pts.add(new Point(LocalDate.parse(rs.getString("d")),
                        nullable(rs, "low_price"),
                        nullable(rs, "mid_price"),
                        nullable(rs, "market_price")));
                }
            }
        }
        return pts;
    }

    private static float nullable(ResultSet rs, String col) throws SQLException{
        float v =  rs.getFloat(col);
        return rs.wasNull() ? null : v;
    }

}