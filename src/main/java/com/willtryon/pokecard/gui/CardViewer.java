package com.willtryon.pokecard.gui;
import java.io.File;
import java.net.MalformedURLException;
import javafx.application.*;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.*;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.*;

public class CardViewer extends Application{

    @SuppressWarnings("deprecation")
	@Override
    public void start(Stage initStage){
        InitTask initTask = new InitTask();
        ProgressBar progressBar = new ProgressBar();
        progressBar.setPrefWidth(300);
        progressBar.progressProperty().bind(initTask.progressProperty());

        Label statusLabel = new Label("Initalizing...");
        statusLabel.textProperty().bind(initTask.messageProperty());

        VBox splashLayout = new VBox(15, statusLabel, progressBar);
        splashLayout.setAlignment(Pos.CENTER);
        splashLayout.setStyle("-fx-background-color: #2c3e50; -fx-padding: 20;");
        statusLabel.setStyle("-fx-text-fill: white; -fx-font-size: 14px;");
        Scene splashScene = new Scene(splashLayout, 400, 250);
        initStage.initStyle(StageStyle.UNDECORATED);
        initStage.setScene(splashScene);
        initStage.show();

        initTask.setOnSucceeded(event -> Platform.runLater(() -> {
            try{
                showMainStage();
                initStage.hide();
            }catch(Exception e){
                e.printStackTrace();
            }
        }));
        Thread initThread = new Thread(initTask);
        initThread.setDaemon(true);
        initThread.start();

    }

    public void showMainStage(){
        Stage mainStage = new Stage();
        Label title = new Label("CardViewer");
        ImageView view = new ImageView();
        view.setFitHeight(320);
        view.setPreserveRatio(true);

        Button open = new Button("Open card...");
        open.setOnAction(e ->{
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Choose a card image...");
            File file = chooser.showOpenDialog(mainStage);
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
        root.setAlignment(Pos.CENTER_LEFT);
        root.setPadding(new Insets(16));
        
        mainStage.setScene(new Scene(root, 420, 520));
        mainStage.setTitle("Pokecard Viewer");
        mainStage.show();
    }



    public static void main(String[] args){
        launch(args);
    }

}

class InitTask extends Task<Void>{
    @Override
    protected Void call()throws Exception{
        int maxSteps = 100;
        for(int i = 0; i <= maxSteps; i++){
            Thread.sleep(30);
            updateProgress(i, maxSteps);
            updateMessage("Loading, please wait...");
        }
        return null;
    }
}