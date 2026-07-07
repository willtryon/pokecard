package com.willtryon.pokecard.gui;
import javafx.application.*;
import javafx.scene.*;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.stage.*;

public class HelloFX extends Application{
    @Override
    public void start(Stage stage){
        Label hello = new Label("hello world!");
        StackPane root = new StackPane(hello);
        Scene scene = new Scene(root, 400, 200);
        stage.setTitle("HelloFX");
        stage.setScene(scene);
        stage.show();
    }

    public static void main(String[] args){
        launch(args);
        System.out.println("Hello World!");
    }
}
