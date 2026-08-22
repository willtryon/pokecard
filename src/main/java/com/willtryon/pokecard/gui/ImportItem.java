package com.willtryon.pokecard.gui;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.controlsfx.control.PropertySheet;
import javafx.beans.value.ObservableValue;

public final class ImportItem<T> implements PropertySheet.Item{
    private final String name, category, description;
    private final Class<T> type;
    private final Supplier<T> supplier;
    private final Consumer<T> setter;

    public ImportItem(String cat, String name, String description, Class<T> type, Supplier<T> supplier, Consumer<T> setter){
        this.category = cat;
        this.name = name;
        this.description = description;
        this.type = type;
        this.supplier = supplier;
        this.setter = setter;
    }

    @Override
    public Class<?> getType() {
        return type;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getDescription() {
        return description;
    }

    @Override
    public Object getValue() {
        return supplier.get();
    }

    @Override
    public String getCategory(){
        return category;
    }

    public boolean isEditable(Object value) {
        return setter != null;
    }

    @Override
    public Optional<ObservableValue<?>> getObservableValue(){
        return Optional.empty();
    }

    @SuppressWarnings("unchecked")
    @Override
    public void setValue(Object v){
        setter.accept((T) v);
    }
}