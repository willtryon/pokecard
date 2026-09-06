package com.willtryon.pokecard;
public enum Category{
    UNREMARK ("UNREMARK","Unremarkable"),
    MID ("MID","Mid"),
    HIGH ("HIGH", "High"),
    ULTRA ("ULTRA", "Ultra");

    final String dbValue, label;
    Category(final String name, final String dbValue) {
        this.label = name;
        this.dbValue = dbValue;
    }
    public String dbValue() {
        return dbValue;
    }

    public static Category fromCatDb(String s){
        if (s != null) {
            String t = s.trim();
            for (Category v : Category.values()) {
                if (t.equalsIgnoreCase(v.label)
                        || t.equalsIgnoreCase(v.dbValue)
                        || t.equalsIgnoreCase(v.name())) {
                    return v;
                }
            }
        }
        return UNREMARK;
    }
}