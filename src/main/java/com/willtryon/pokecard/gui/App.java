package com.willtryon.pokecard.gui;
import com.willtryon.pokecard.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.stage.*;
public class App extends Application{

    private Config config;
    private AppContext ctx;

    private Label statusBar;
    private ProgressBar statusProgress;

    record RequiredPath(String key, String label, boolean isDir){
        Predicate<Path> valid(){return isDir ? Files::isDirectory : Files::isRegularFile;}
    }

    static final List<RequiredPath> REQUIRED = List.of(
        new RequiredPath(Config.DB_PATH,     "data.sqlite file",           false),
        new RequiredPath(Config.IMAGES_DIR,  "images/cards folder",        true),
        new RequiredPath(Config.COMPARE_DIR, "folder of cards to compare", true),
        new RequiredPath(Config.OUTPUT_DIR,  "output / log folder",        true),
        new RequiredPath(Config.CACHE_DIR,   "cache folder",               true)
    );

    record AppContext(CardIndex cardDB, CardImportsIndex importDB, int size){}

    @Override
    public void start(Stage initStage){
        Path propsPath = Path.of("/Users/willtryon/javaprojects/PokeImageComp/pokecard/pokecard.properties");
        try{
            config = new Config(propsPath);
        }catch(IOException e){
            showError(e);
            Platform.exit();
            return;
        }

        ConfigEditor editor = new ConfigEditor(config);
        while(!allPathsValid()){
            if(!editor.showAndWait(null)){
                Platform.exit();
                return;
            }
        }

        Path dbPath     = Path.of(config.get(Config.DB_PATH));
        Path imagesDir  = Path.of(config.get(Config.IMAGES_DIR));
        Path compareDir = Path.of(config.get(Config.COMPARE_DIR));
        Path outputDir  = Path.of(config.get(Config.OUTPUT_DIR));
        Path cacheDir   = Path.of(config.get(Config.CACHE_DIR));

        InitTask initTask = new InitTask(dbPath, imagesDir, compareDir, outputDir, cacheDir);
        ProgressBar progressBar = new ProgressBar();
        progressBar.setPrefWidth(300);
        progressBar.progressProperty().bind(initTask.progressProperty());

        Label statusLabel = new Label("Initializing...");
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
            statusLabel.setText("Exception occurred:" +ex.getMessage());
        });
        Thread initThread = new Thread(initTask, "pokecard-init");
        initThread.setDaemon(true);
        initThread.start();
    }

    private boolean allPathsValid(){
        for(RequiredPath r : REQUIRED){
            if(!config.isValid(r.key(), r.valid())){
                return false;
            }
        }
        return true;
    }

    public void showMainStage(){
        Stage mainStage = new Stage();
        //Menu Bar init...
        MenuItem importItem = new MenuItem("Import images to scan...");
        MenuItem settingsItem = new MenuItem("Settings...");
        MenuItem exitItem = new MenuItem("Quit");
        Menu fileMenu = new Menu("File");
        fileMenu.getItems().addAll(importItem, settingsItem, new SeparatorMenuItem(), exitItem);
        Menu editMenu = new Menu("Edit");
        MenuBar menuBar = new MenuBar(fileMenu, editMenu);
        menuBar.setUseSystemMenuBar(true);
        //Main init...
        Label title = new Label("Pokecard");
        ImageView view = new ImageView();
        view.setFitHeight(320);
        view.setPreserveRatio(true);
        Label result = new Label();
        result.setWrapText(true);

        Button scan = new Button("Scan folder...");
        scan.setOnAction(e ->{
            scan.setDisable(true);
            Task<Void> scanTask = new Task<>(){
                @Override
                protected Void call(){
                    ctx.cardDB.scanImports(ctx.importDB(), (msg, frac) ->{
                        updateMessage(msg);
                        updateProgress(frac,1.0);
                    });
                    return null;
                }
                };
                runTask(scanTask, v -> {});
        });
        
        VBox center = new VBox(12, title, view, result, scan);
        center.setAlignment(Pos.CENTER);
        center.setPadding(new Insets(16));
        
        //Menu bar operations...

        importItem.setOnAction(e ->{
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Select a card to scan");
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Images", "*.png", "*.jpg", "*.jpeg"));
            File file = chooser.showOpenDialog(mainStage);
            if(file == null) return;
            Path image = file.toPath();
            Task<CardImports> t = new Task<>() {
                @Override
                protected CardImports call()throws Exception{
                    return ctx.importDB().scanOne(image, (msg, frac) -> {
                        updateMessage(msg);
                        updateProgress(frac, 1.0);
                    });
                }
            };
            runTask(t, found -> {
                view.setImage(new Image(file.toURI().toString()));
                if(found != null) result.setText(found.getORBRecordHistory());
            });
        });

        settingsItem.setOnAction(e -> {
            if(new ConfigEditor(config).showAndWait(mainStage)){
                new Alert(Alert.AlertType.INFORMATION, "Path changes apply next launch.", ButtonType.OK).showAndWait();
            }
        });

        exitItem.setOnAction(e -> Platform.exit());
        //build window...
        BorderPane root = new BorderPane();
        root.setTop(menuBar);
        root.setCenter(center);
        root.setBottom(buildStatusBar());

        mainStage.setScene(new Scene(root, 420, 520));
        mainStage.setTitle("Pokecard");
        mainStage.show();
    }

    private HBox buildStatusBar(){
        statusBar = new Label("Ready.");
        statusProgress = new ProgressBar();
        statusProgress.setPrefWidth(120);
        statusProgress.setVisible(false);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bar = new HBox(8, statusBar, spacer, statusProgress);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(4, 8, 4, 8));
        bar.setStyle("-fx-border-color: #cccccc; -fx-border-width: 1 0 0 0;");
        return bar;
    }

    private <T> void runTask(Task<T> task, Consumer<T> onSuccess){
        statusBar.textProperty().bind(task.messageProperty());
        statusProgress.progressProperty().bind(task.progressProperty());
        statusProgress.setVisible(true);
        task.setOnSucceeded(e ->{
            finishTask();
            if(onSuccess != null){
                onSuccess.accept(task.getValue());
            }
        });
        task.setOnFailed(e -> {
            finishTask();
            showError(task.getException());
        });
        task.setOnCancelled(e -> finishTask());
        Thread t = new Thread(task, "pokecard-task");
        t.setDaemon(true);
        t.start();
    }

    private void finishTask(){
        statusBar.textProperty().unbind();
        statusProgress.progressProperty().unbind();
        statusBar.setText("Ready.");
        statusProgress.setVisible(false);
    }

    private void showError(Throwable ex){
        ex.printStackTrace();
        Alert a = new Alert(Alert.AlertType.ERROR, String.valueOf(ex.getMessage()), ButtonType.OK);
        a.setHeaderText("Something went wrong.");
        a.showAndWait();
    }

    public static void main(String[] args){
        launch(args);
    }



}

class InitTask extends Task<App.AppContext>{

    private final Path dbPath, imagesDir, compareDir, outputDir, cacheDir;
    protected InitTask(Path dbPath, Path imagesDir, Path compareDir, Path outputDir, Path cacheDir){
        this.dbPath = dbPath;
        this.imagesDir = imagesDir;
        this.compareDir = compareDir;
        this.outputDir = outputDir;
        this.cacheDir = cacheDir;
    }

    @Override
    protected App.AppContext call()throws Exception{
        String url = "jdbc:sqlite:" + dbPath;
        updateMessage("Connecting to database...");
        int size;
        try (Connection conn = DriverManager.getConnection(url);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) AS n FROM cards")) {
            size = rs.next() ? rs.getInt("n") : 0;
        }
        Main.size = size;

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
        return new App.AppContext(cardDB, importDB, size);
    }
}

class ConfigEditor{
    private final Config config;
    ConfigEditor(Config config){
        this.config = config;
    }

    boolean showAndWait(Window owner){
        Stage dialog = new Stage();
        if(owner != null){
            dialog.initOwner(owner);
        }
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.setTitle("Settings: Pokecard paths");

        GridPane grid = new GridPane();
        grid.setHgap(8); grid.setVgap(10);
        List<TextField> fields = new ArrayList<>();
        for(int i = 0; i<App.REQUIRED.size(); i++){
            App.RequiredPath r = App.REQUIRED.get(i);
            TextField field = new TextField(config.get(r.key()));
            field.setPrefColumnCount(32);
            Button browse = new Button("Browse…");
            browse.setOnAction(e -> {
                File f;
                if (r.isDir()) {
                    DirectoryChooser dc = new DirectoryChooser();
                    dc.setTitle("Choose " + r.label());
                    f = dc.showDialog(dialog);
                } else {
                    FileChooser fc = new FileChooser();
                    fc.setTitle("Choose " + r.label());
                    f = fc.showOpenDialog(dialog);
                }
                if (f != null) field.setText(f.getAbsolutePath());
            });
            grid.addRow(i, new Label(r.label()), field, browse);
            fields.add(field);
        }

        Label error = new Label();
        error.setStyle("-fx-text-fill: #c0392b");
        Button save = new Button("Save");
        Button cancel = new Button("Cancel");
        final boolean[] saved = {false};
        save.setOnAction(e -> {
            for(int i = 0; i < App.REQUIRED.size(); i++){
                App.RequiredPath r = App.REQUIRED.get(i);
                String v = fields.get(i).getText().trim();
                if(v.isBlank() || !r.valid().test(Path.of(v))){
                    error.setText("\u201C" + r.label() + "\u201D is missing or invalid.");
                    return; //Allows user to fix mistake
                }

            }
            try{
                for(int i = 0; i < App.REQUIRED.size(); i++){
                    config.set(App.REQUIRED.get(i).key(), fields.get(i).getText().trim());
                }
                config.save();
                saved[0] = true;
            }catch(IOException ex){
                error.setText("Couldn't save: "+ex.getMessage()+" Do you have permission?");
            }finally{
                dialog.close();
            }
        });
        cancel.setOnAction(e -> dialog.close());

        HBox buttons = new HBox(8, save, cancel);
        buttons.setAlignment(Pos.BOTTOM_CENTER);
        VBox rootBox = new VBox(12, grid, error, buttons);
        rootBox.setPadding(new Insets(16));
        dialog.setScene(new Scene(rootBox));
        dialog.showAndWait();
        return saved[0];

    }
}