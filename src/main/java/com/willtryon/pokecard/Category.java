package com.willtryon.pokecard;
public enum Category{
    UNREMARK ("Unremarkable","UNREMARK"),
    MID ("Mid","MID"),
    HIGH ("High", "HIGH"),
    ULTRA ("Ultra", "ULTRA");

    final String dbValue, label;
    Category(final String name, final String dbValue) {
        this.label = name;
        this.dbValue = dbValue;
    }
    public String dbValue() {
        return dbValue;
    }

    @Override
    public String toString() {
        return label;
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