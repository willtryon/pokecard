package com.willtryon.pokecard.gui;
import com.willtryon.pokecard.*;
import com.willtryon.pokecard.gui.Main.AppContext;

import java.io.File;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Scanner;

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

public class Main extends Application{

    private AppContext ctx;

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
        ctx = initTask.getValue();
        showMainStage();
        initStage.hide();
        }));
        initTask.setOnFailed(e ->{
            Throwable ex = initTask.getException();
            ex.printStackTrace();
            statusLabel.textProperty().unbind();
            statusLabel.setText("Exception occured:" +ex.getMessage());
        });
        Thread initThread = new Thread(initTask, "pokecard-init");
        initThread.setDaemon(true);
        initThread.start();

    }

    public void showMainStage(){
        Stage mainStage = new Stage();
        Label title = new Label("Pokecard");
        ImageView view = new ImageView();
        view.setFitHeight(320);
        view.setPreserveRatio(true);

        Button scan = new Button("Scan card...");
        scan.setOnAction(e ->{
            scan.setDisable(true);
            Task<Void> scanTask = new Task<>(){
                @Override
                protected Void call()throws Exception{
                    ctx.cardDB.scanImports(ctx.importDB());
                    return null;
                }
                };
                scanTask.setOnSucceeded(ev -> {
                    scan.setDisable(false);
                });
                scanTask.setOnCancelled(ev -> {
                    scan.setDisable(false);
                    scanTask.getException().printStackTrace();
                });
                Thread t = new Thread(scanTask, "pokecard-scan");
                t.setDaemon(true);
                t.start();
        });
        
        HBox root = new HBox(12, title, view, scan);
        root.setAlignment(Pos.CENTER);
        root.setPadding(new Insets(16));
        
        mainStage.setScene(new Scene(root, 420, 520));
        mainStage.setTitle("Pokecard");
        mainStage.show();
    }



    public static void main(String[] args){
        launch(args);
    }

    record AppContext(CardIndex cardDB, CardImportsIndex importDB, int size){};

}

class InitTask extends Task<AppContext>{

    @Override
    protected AppContext call()throws Exception{
        updateMessage("Starting...");
        Config config = new Config(Path.of("/Users/willtryon/VSCode/PokeImageComp/pokecard/pokecard.properties"));
         try (Scanner in = new Scanner(System.in)) {   // only used if a key is missing — keep the file complete
            Path dbPath     = config.require(Config.DB_PATH,     "Path to data.sqlite",          Files::isRegularFile, in);
            Path imagesDir  = config.require(Config.IMAGES_DIR,  "Path to images/cards folder",  Files::isDirectory,   in);
            Path compareDir = config.require(Config.COMPARE_DIR, "Path to images to compare to", Files::isDirectory,   in);
            Path outputDir  = config.require(Config.OUTPUT_DIR,  "Path to output log files",     Files::isDirectory,   in);
            Path cacheDir   = config.require(Config.CACHE_DIR,   "Path to cache directory",      Files::isDirectory,   in);
            String url = "jdbc:sqlite:" + dbPath;

            updateMessage("Connecting to database...");
            int size;
            try (Connection conn = DriverManager.getConnection(url);
                 Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT COUNT(*) AS n FROM cards")) {
                size = rs.next() ? rs.getInt("n") : 0;
            }
            App.size = size; 

            Path cacheFile = cacheDir.resolve("cache.xml");
            CardIndex cardDB;
            if (Files.isRegularFile(cacheFile)) {
                updateMessage("Loading cache (" + size + " cards)...");
                cardDB = new CardIndex(imagesDir, outputDir, cacheDir);
            } else {
                updateMessage("Computing image data for " + size + " cards...");
                cardDB = new CardIndex(size, url, imagesDir, outputDir, cacheDir);
            }

            updateMessage("Indexing imports...");
            CardImportsIndex importDB = cardDB.newImportsIndex(compareDir);

            return new AppContext(cardDB, importDB, size);
        }
    }
}