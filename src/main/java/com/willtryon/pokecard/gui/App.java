package com.willtryon.pokecard.gui;

import com.willtryon.pokecard.*;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.stage.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public class App extends Application{

    private Config config;
    private Path cacheDir;
    private AppContext ctx;

    private Label statusBar;
    private Label statusTime;
    private ProgressBar statusProgress;

    // --- settings model: sidebar sections, each holding typed fields ---
    enum Kind {
        DIRECTORY, FILE, TEXT, SECRET;
        boolean isValidValue(String v){
            return switch (this){
                case DIRECTORY -> Files.isDirectory(Path.of(v));
                case FILE      -> Files.isRegularFile(Path.of(v));
                case TEXT, SECRET -> !v.isBlank();
            };
        }
    }
    record Setting(String key, String label, Kind kind, boolean required){}
    record Section(String name, List<Setting> settings){}

    static final List<Section> SECTIONS = List.of(
            new Section("Paths", List.of(
                    new Setting(Config.DB_PATH,     "data.sqlite file",           Kind.FILE,      true),
                    new Setting(Config.IMAGES_DIR,  "images/cards folder",        Kind.DIRECTORY, true),
                    new Setting(Config.COMPARE_DIR, "folder of cards to compare", Kind.DIRECTORY, true)
            )),
            new Section("Advanced", List.of(
                    new Setting(Config.OUTPUT_DIR, "output / log folder", Kind.DIRECTORY, false),
                    new Setting(Config.CACHE_DIR,  "cache folder",        Kind.DIRECTORY, false)
            )),
            new Section("eBay API", List.of(
                    new Setting(Config.EBAY_API_KEY, "API key", Kind.SECRET, false)
                    // add more eBay fields here as you build that integration
            ))
    );

    /** OK if it's an allowed blank (optional) or passes its kind's check. */
    static boolean satisfied(Setting s, String value){
        if (value == null || value.isBlank()) return !s.required();
        return s.kind().isValidValue(value);
    }

    record AppContext(CardIndex cardDB, CardImportsIndex importDB, int size){}

    @Override
    public void start(Stage initStage){
        // Everything the program owns lives under ~/.pokecard (created on demand).
        Path appHome   = Path.of(System.getProperty("user.home"), ".pokecard");
        Path propsPath = appHome.resolve("pokecard.properties");
        try{
            Files.createDirectories(appHome);
            config = new Config(propsPath);

            // program-managed folders: default under ~/.pokecard if unset, and ensure they exist
            boolean changed = false;
            if (config.get(Config.CACHE_DIR).isBlank())  { config.set(Config.CACHE_DIR,  appHome.resolve("cache").toString());  changed = true; }
            if (config.get(Config.OUTPUT_DIR).isBlank()){
                config.set(Config.OUTPUT_DIR, appHome.resolve("output").toString());
                changed = true;
            }
            Files.createDirectories(Path.of(config.get(Config.CACHE_DIR)));
            Files.createDirectories(Path.of(config.get(Config.OUTPUT_DIR)));
            Files.createDirectories(Path.of(config.get(Config.OUTPUT_DIR)+"/logs/"));
            Files.createDirectories(Path.of(config.get(Config.OUTPUT_DIR)+"/csv/"));
            if (changed) config.save();
        }catch(IOException e){
            showError(e);
            Platform.exit();
            return;
        }

        // Only the external inputs (DB, images, import) can block startup -- see SECTIONS.
        ConfigEditor editor = new ConfigEditor(config);
        while(!allSettingsSatisfied()){
            if(!editor.showAndWait(null)){
                Platform.exit();
                return;
            }
        }

        // (re)create in case the user pointed cache/output somewhere new under Advanced
        try{
            Files.createDirectories(Path.of(config.get(Config.OUTPUT_DIR)));
            Files.createDirectories(Path.of(config.get(Config.CACHE_DIR)));
        }catch(IOException e){
            showError(e);
            Platform.exit();
            return;
        }

        Path dbPath     = Path.of(config.get(Config.DB_PATH));
        Path imagesDir  = Path.of(config.get(Config.IMAGES_DIR));
        Path compareDir = Path.of(config.get(Config.COMPARE_DIR));
        Path outputDir  = Path.of(config.get(Config.OUTPUT_DIR));
        cacheDir   = Path.of(config.get(Config.CACHE_DIR));

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

    private boolean allSettingsSatisfied(){
        for (Section sec : SECTIONS)
            for (Setting s : sec.settings())
                if (!satisfied(s, config.get(s.key()))) return false;
        return true;
    }

    public void showMainStage(){
        Stage mainStage = new Stage();
        //Menu Bar init...
        MenuItem saveSessionItem = new MenuItem("Save session");
        MenuItem loadSessionItem = new MenuItem("Load session");
        MenuItem importItem = new MenuItem("Import an image to scan...");
        MenuItem settingsItem = new MenuItem("Settings...");
        MenuItem exitItem = new MenuItem("Quit");
        Menu fileMenu = new Menu("File");
        fileMenu.getItems().addAll(saveSessionItem, loadSessionItem, importItem, settingsItem, new SeparatorMenuItem(), exitItem);
        Menu editMenu = new Menu("Edit");
        Menu helpMenu = new Menu("Help");
        MenuItem aboutItem = new MenuItem("About");
        helpMenu.getItems().addAll(aboutItem);
        MenuBar menuBar = new MenuBar(fileMenu, editMenu, helpMenu);
        menuBar.setUseSystemMenuBar(true);
        //Main init...
        Label title = new Label("Pokecard");
        ImageView view1 = new ImageView();
        ImageView view2 = new ImageView();
        view1.setFitHeight(320); view2.setFitHeight(320);
        view1.setPreserveRatio(true); view2.setPreserveRatio(true);
        File initImport = new File("/Users/willtryon/javaprojects/PokeImageComp/pokecard/src/main/resources/importedImage.png");
        File initFound = new File("/Users/willtryon/javaprojects/PokeImageComp/pokecard/src/main/resources/foundImage.png");
        view1.setImage(new Image(initImport.toURI().toString()));
        view2.setImage(new Image(initFound.toURI().toString()));
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
                    scan.setDisable(false);
                    return null;
                }
            };
            runTask(scanTask, v -> {});
        });
        HBox imageView = new HBox(20, view1, view2);
        VBox center = new VBox(12, title, imageView, result, scan);
        center.setAlignment(Pos.CENTER);
        center.setPadding(new Insets(16));

        //Menu bar operations...

        saveSessionItem.setOnAction(e -> {
            System.out.println("Saving imports to disk:");
            statusBar.setText("Saving Session...");
            statusProgress.setVisible(true);
            ctx.importDB.writeImportsToDisk(cacheDir);
            System.out.println("Done.");
            statusBar.setText("Ready.");
            statusProgress.setVisible(false);
        });

        loadSessionItem.setOnAction(e -> {
            System.out.println("Loading imports from disk:");
            statusBar.setText("Loading Session...");
            statusProgress.setVisible(true);
            ctx.importDB.readImportsFromDisk(cacheDir);
            List<CardImports> restored = ctx.importDB.getImports();
            System.out.println("Loaded " + restored.size() + " imports.");
            System.out.println(restored.get(0).getORBRecordHistory()+"\n"+restored.get(0).getOrbWinner());
            System.out.println("Done.");
            statusBar.setText("Ready.");
        });

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
                    CardImports result = ctx.importDB().scanOne(image, (msg, frac) -> {
                        updateMessage(msg);
                        updateProgress(frac, 1.0);
                    });
                    System.out.println("\n\n"+ctx.importDB.getLastImports().getOrbWinner());
                    return result;
                }
            };
            runTask(t, found -> {
                view1.setImage(new Image(file.toURI().toString()));
                String foundImage = found.getOrbWinner().img();
                System.out.println(foundImage);
                view2.setImage(new Image(new File(foundImage).toURI().toString()));
                System.out.println(ctx.importDB.getLastImports().getOrbWinner().img());
                //view2.setImage(new Image(ctx.importDB.getLastImports().getOrbWinner().img()));

            });
        });

        settingsItem.setOnAction(e -> {
            if(new ConfigEditor(config).showAndWait(mainStage)){
                new Alert(Alert.AlertType.INFORMATION, "Path changes apply next launch.", ButtonType.OK).showAndWait();
            }
        });

        exitItem.setOnAction(e -> Platform.exit());

        aboutItem.setOnAction(e -> {
            Stage aboutStage = new Stage();
            aboutStage.setTitle("About Pokecard");
            Label name = new Label("Pokecard");
            Label version = new Label("Version 0.6.0");
            Label author = new Label("by willtryon");
            Button close = new Button("Close");
            VBox aboutLayout = new VBox(12, name, version, author, close);
            aboutLayout.setAlignment(Pos.CENTER);
            aboutLayout.setPadding(new Insets(16));
            aboutStage.setScene(new Scene(aboutLayout, 300, 150));
            close.setOnAction(e1 -> aboutStage.close());
            aboutStage.show();
        });
        //build window...
        BorderPane root = new BorderPane();
        root.setTop(menuBar);
        root.setCenter(center);
        root.setBottom(buildStatusBar());
        TreeItem<String> rootItem = new TreeItem<>("Project Files");
        TreeItem<String> cardsItem = new TreeItem<>("Cards");

        root.setLeft(buildSideTree(ctx.cardDB, ctx.importDB()));

        mainStage.setScene(new Scene(root, 700, 600));
        mainStage.setTitle("Pokecard");
        mainStage.show();

        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "pokecard-scheduled-scan");
            t.setDaemon(true);          // don't keep the JVM alive after the window closes
            return t;
        });

        scheduler.scheduleAtFixedRate(() -> Platform.runLater(() -> {
            if (!scanRunning.compareAndSet(false, true)) return;
            Task<Void> tick = new Task<>() {
                @Override
                protected Void call() {
                    ctx.cardDB.scanImports(ctx.importDB(), (msg, frac) -> {
                        updateMessage(msg);
                        updateProgress(frac, 1.0);
                    });
                    return null;
                }
            };
            tick.setOnSucceeded(e -> scanRunning.set(false));
            tick.setOnFailed(e -> scanRunning.set(false));
            tick.setOnCancelled(e -> scanRunning.set(false));
            runTask(tick, v -> {});
        }), 0, 1, TimeUnit.MINUTES);
    }

    private HBox buildStatusBar(){
        statusBar = new Label("Ready.");
        statusTime = new Label("");
        statusProgress = new ProgressBar();
        statusProgress.setPrefWidth(120);
        statusTime.setVisible(false);
        statusProgress.setVisible(false);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bar = new HBox(8, statusBar, spacer, statusProgress);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(4, 8, 4, 8));
        bar.setStyle("-fx-border-color: #cccccc; -fx-border-width: 1 0 0 0;");
        return bar;
    }

    private TreeView buildSideTree(CardIndex cardDB, CardImportsIndex importDB) {
        //cardDB.retrieveFileStructure("cards/");
        TreeItem<String> rootItem = new TreeItem<>("Project Files");
        TreeItem<String> cardsItem = new TreeItem<>("Cards");
        TreeItem<String> importsItem = new TreeItem<>("Imports");
        rootItem.getChildren().addAll(cardsItem, importsItem);
        return new TreeView<>(rootItem);
    }

    private Task<?> currentStatusTask;
    private final AtomicBoolean scanRunning = new AtomicBoolean(false);

    private <T> void runTask(Task<T> task, Consumer<T> onSuccess){
        currentStatusTask = task;
        statusBar.textProperty().bind(task.messageProperty());
        statusProgress.progressProperty().bind(task.progressProperty());
        statusProgress.setVisible(true);
        task.setOnSucceeded(e -> {
            finishTask(task);
            if (onSuccess != null) onSuccess.accept(task.getValue());
        });
        task.setOnFailed(e -> { finishTask(task); showError(task.getException()); });
        task.setOnCancelled(e -> finishTask(task));
        Thread t = new Thread(task);
        t.setDaemon(true);
        t.start();
    }

    private void finishTask(Task<?> task){
        if (currentStatusTask == task){       // only the task that's still "current" may unbind
            statusBar.textProperty().unbind();
            statusProgress.progressProperty().unbind();
            statusBar.setText("Ready.");
            statusProgress.setVisible(false);
            currentStatusTask = null;
        }
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
            final CardIndex finalCardDB = cardDB;
            CountDownLatch latch = new CountDownLatch(1);
            AtomicBoolean saveChoice = new AtomicBoolean(false);

            Platform.runLater(() -> {
                Alert alert = new Alert(
                        Alert.AlertType.INFORMATION,
                        "Done calculating image data. Writing the data to the disk will take about 620MB. Do you want to save the data?",
                        ButtonType.YES, ButtonType.NO
                );
                alert.setHeaderText("Save image data");
                Optional<ButtonType> result = alert.showAndWait();
                saveChoice.set(result.isPresent() && result.get() == ButtonType.YES);
                latch.countDown();
            });

            latch.await(); // block the background thread until the user answers

            if (saveChoice.get()) {
                cardDB.writeToDisk(cacheDir);
            }
        }
        updateMessage("Indexing imports...");
        CardImportsIndex importDB = cardDB.newImportsIndex(compareDir, cacheDir);;
        return new App.AppContext(cardDB,importDB,size);
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
        dialog.setTitle("Settings");

        // one input control per key, built ONCE so edits survive switching sections
        Map<String, TextInputControl> inputs = new HashMap<>();
        Map<App.Section, Node> pages = new LinkedHashMap<>();
        for(App.Section sec : App.SECTIONS) pages.put(sec, buildPage(sec, inputs, dialog));

        // left: sidebar of section names
        ListView<App.Section> sidebar = new ListView<>();
        sidebar.getItems().addAll(App.SECTIONS);
        sidebar.setPrefWidth(150);
        sidebar.setCellFactory(lv -> new ListCell<App.Section>(){
            @Override protected void updateItem(App.Section s, boolean empty){
                super.updateItem(s, empty);
                setText(empty || s == null ? null : s.name());
            }
        });

        // right: detail pane, swapped on selection
        StackPane detail = new StackPane();
        sidebar.getSelectionModel().selectedItemProperty().addListener((obs, old, sel) -> {
            if(sel != null) detail.getChildren().setAll(pages.get(sel));
        });
        sidebar.getSelectionModel().selectFirst();

        Label error = new Label();
        error.setStyle("-fx-text-fill: #c0392b");
        Button save = new Button("Save");
        Button cancel = new Button("Cancel");
        final boolean[] saved = {false};

        save.setOnAction(e -> {
            for(App.Section sec : App.SECTIONS){
                for(App.Setting s : sec.settings()){
                    String v = inputs.get(s.key()).getText().trim();
                    if(!App.satisfied(s, v)){
                        error.setText("\u201C" + s.label() + "\u201D in " + sec.name() + " is missing or invalid.");
                        sidebar.getSelectionModel().select(sec);
                        return;
                    }
                }
            }
            try{
                for(App.Section sec : App.SECTIONS)
                    for(App.Setting s : sec.settings())
                        config.set(s.key(), inputs.get(s.key()).getText().trim());
                config.save();
                saved[0] = true;
                dialog.close();
            }catch(IOException ex){
                error.setText("Couldn't save: " + ex.getMessage());
            }
        });
        cancel.setOnAction(e -> dialog.close());

        HBox buttons = new HBox(8, save, cancel);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        BorderPane body = new BorderPane();
        body.setLeft(sidebar);
        body.setCenter(detail);
        BorderPane.setMargin(detail, new Insets(0, 0, 0, 12));

        VBox rootBox = new VBox(12, body, error, buttons);
        rootBox.setPadding(new Insets(16));
        rootBox.setPrefSize(640, 380);
        dialog.setScene(new Scene(rootBox));
        dialog.showAndWait();
        return saved[0];
    }

    private Node buildPage(App.Section sec, Map<String, TextInputControl> inputs, Window owner){
        GridPane grid = new GridPane();
        grid.setHgap(8); grid.setVgap(10);
        int row = 0;
        for(App.Setting s : sec.settings()){
            TextField field = (s.kind() == App.Kind.SECRET) ? new PasswordField() : new TextField();
            field.setText(config.get(s.key()));
            field.setPrefColumnCount(30);
            inputs.put(s.key(), field);

            grid.add(new Label(s.label()), 0, row);
            grid.add(field, 1, row);

            if(s.kind() == App.Kind.DIRECTORY || s.kind() == App.Kind.FILE){
                Button browse = new Button("Browse\u2026");
                browse.setOnAction(e -> {
                    File f;
                    if(s.kind() == App.Kind.DIRECTORY){
                        DirectoryChooser dc = new DirectoryChooser();
                        dc.setTitle("Choose " + s.label());
                        f = dc.showDialog(owner);
                    } else {
                        FileChooser fc = new FileChooser();
                        fc.setTitle("Choose " + s.label());
                        f = fc.showOpenDialog(owner);
                    }
                    if(f != null) field.setText(f.getAbsolutePath());
                });
                grid.add(browse, 2, row);
            }
            row++;
        }
        Label header = new Label(sec.name());
        header.setStyle("-fx-font-size: 15px; -fx-font-weight: bold;");
        return new VBox(12, header, grid);
    }
}
