package com.willtryon.pokecard.gui;

import com.willtryon.pokecard.CardImports;
import org.bytedeco.opencv.opencv_core.KeyPointVector;
import org.controlsfx.control.GridCell;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;

import java.awt.*;

public class ImageGridCell extends GridCell<CardImports>{

    private final VBox layout = new VBox(5);
    private final ImageView imageView = new ImageView();
    private final Label label = new Label();

    public ImageGridCell() {
        imageView.setFitHeight(50);
        imageView.setFitWidth(50);
        imageView.setPreserveRatio(true);
        //layout.getChildren().addAll(imageView, label);
    }

    @Override
    protected void updateItem(CardImports item, boolean empty) {
        super.updateItem(item, empty);
        if (empty || item == null) {
            setGraphic(null);
        }else{
            label.setText(item.toString());
        }
    }

}
