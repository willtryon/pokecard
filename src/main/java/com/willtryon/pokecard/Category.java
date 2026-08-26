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
        if(s!=null){
            for(Category v : Category.values()){
                if(v.dbValue.equals(s)){
                    if (v.dbValue.equalsIgnoreCase(s.trim())) return v;
                }
            }
        }
        return UNREMARK;
    }
}