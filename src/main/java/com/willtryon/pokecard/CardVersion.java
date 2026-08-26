package com.willtryon.pokecard;

public enum CardVersion {
    NORMAL          ("NORMAL",           "Normal"),
    HOLOFOIL        ("HOLOFOIL",         "Holofoil"),
    REVERSE_HOLOFOIL("REVERSE HOLOFOIL", "Reverse holofoil");

    final String dbValue, label;
    CardVersion(final String name, final String dbValue) {
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

    public static CardVersion fromDb(String s){
        if(s!=null){
            for(CardVersion v : CardVersion.values()){
                if(v.dbValue.equals(s)){
                    if (v.dbValue.equalsIgnoreCase(s.trim())) return v;
                }
            }
        }
        return NORMAL;
    }
}