package com.willtryon.pokecard.gui;
import static org.bytedeco.opencv.global.opencv_core.xor;

import java.io.File;
import java.net.MalformedURLException;
import java.nio.charset.MalformedInputException;

import javafx.application.*;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.*;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.VBox;
import javafx.stage.*;

public class CardViewer extends Application{

    @SuppressWarnings("deprecation")
	@Override
    public void start(Stage stage){
        Label title = new Label("CardViewer");
        ImageView view = new ImageView();
        view.setFitHeight(320);
        view.setPreserveRatio(true);

        Button open = new Button("Open card...");
        open.setOnAction(e ->{
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Choose a card image...");
            File file = chooser.showOpenDialog(stage);
            if (file != null){
                    try {
						view.setImage(new Image(file.toURL().toString()));
					} catch (MalformedURLException e1) {
						// TODO Auto-generated catch block
						e1.printStackTrace();
					}
            }
        });
        VBox root = new VBox(12, title, view, open);
        root.setAlignment(Pos.CENTER);
        root.setPadding(new Insets(16));
        
        stage.setScene(new Scene(root, 420, 520));
        stage.setTitle("Pokecard Viewer");
        stage.show();
    }

    public static void main(String[] args){
        launch(args);
    }

}