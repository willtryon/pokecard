package com.willtryon.pokecard.gui;

import com.willtryon.pokecard.*;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.ListChangeListener;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.cell.CheckBoxListCell;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.*;
import javafx.stage.*;
import javafx.util.StringConverter;
import org.controlsfx.control.PopOver;
import org.controlsfx.control.TaskProgressView;
import org.controlsfx.control.spreadsheet.*;
import org.controlsfx.control.PropertySheet;
import org.controlsfx.property.BeanPropertyUtils;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.control.ComboBox;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static com.willtryon.pokecard.CardImportsIndex.globalCardVersion;
import static com.willtryon.pokecard.CardImportsIndex.globalFirstEdition;
import static com.willtryon.pokecard.PokeocrEnv.ocrDefaultCacheDir;
import static com.willtryon.pokecard.TcgdbEnv.tcgdbDefaultCacheDir;
import com.willtryon.pokecard.Config.Settings;

import javax.swing.event.HyperlinkEvent;

public class App extends Application {

    private Config config;
    private Path sessionPath;
    private String currentSession;
    private Settings settings;
    private boolean saved;
    private final SimpleBooleanProperty isOrb = new SimpleBooleanProperty(false);
    private final SimpleBooleanProperty isHash = new SimpleBooleanProperty(false);
    private AppContext ctx;

    private Label statusBar;
    private ProgressBar statusProgress;
    private final TaskProgressView<Task<?>> taskView = new TaskProgressView<>();
    private final ObjectProperty<Task<?>> statusTask = new SimpleObjectProperty<>();
    private PopOver taskPopOver;
    private TabPane detailTabs;
    private TreeItem<SideNode> importsBranch;
    public static boolean firstRun = true;




    enum Kind {
        DIRECTORY, FILE, TEXT, SECRET, BOOLEAN, MODE;

        boolean isValidValue(String v) {
            return switch (this) {
                case DIRECTORY -> Files.isDirectory(Path.of(v));
                case FILE -> Files.isRegularFile(Path.of(v));
                case TEXT, SECRET, MODE -> !v.isBlank();
                case BOOLEAN -> Boolean.parseBoolean(v);
            };
        }
    }

    sealed interface SideNode permits Group, CardEntry, ImportEntry{}
    record Group(String label) implements SideNode{}
    record CardEntry(CardSignature sig) implements SideNode{}
    record ImportEntry(CardImports imp) implements SideNode{}

    record Setting(String key, String label, Kind kind, boolean required) {
    }

    record Section(String name, List<Setting> settings) {
    }

    static final List<Section> SECTIONS = List.of(
            new Section("Paths", List.of(
                    new Setting(Config.DB_PATH, "data.sqlite file", Kind.FILE, true),
                    new Setting(Config.IMAGES_DIR, "images/cards folder", Kind.DIRECTORY, true),
                    new Setting(Config.COMPARE_DIR, "folder of cards to compare", Kind.DIRECTORY, true)
            )),
            new Section("Advanced", List.of(
                    new Setting(Config.OUTPUT_DIR, "output / log folder", Kind.DIRECTORY, false),
                    new Setting(Config.CACHE_DIR, "cache folder", Kind.DIRECTORY, false)
            )),
            new Section("eBay API", List.of(
                    new Setting(Config.EBAY_API_KEY, "API key", Kind.SECRET, false)
                    // add more eBay fields here as you build that integration
            )),
            new Section("Performance", List.of(
                    new Setting(Config.SCAN_THREADS, "Threads", Kind.TEXT, false)
            )),
            new Section("pokeocr", List.of(
                    new Setting(Config.OCR_MODEL, "OCR model", Kind.MODE, false)
            ))
    );

    static boolean satisfied(Setting s, String value) {
        if (value == null || value.isBlank()) return !s.required();
        return s.kind().isValidValue(value);
    }

    record AppContext(CardIndex cardDB, CardImportsIndex importDB, int size) {
    }

    @Override
    public void start(Stage initStage) {
        // Everything the program owns lives under ~/.pokecard.
        Path appHome = Path.of(System.getProperty("user.home"), ".pokecard");
        Path propsPath = appHome.resolve("pokecard.properties");
        try {
            Files.createDirectories(appHome);
            config = new Config(propsPath);

            // looks for the folders and creates them if they don't exist
            boolean changed = false;
            if (config.get(Config.CACHE_DIR).isBlank()) {
                config.set(Config.CACHE_DIR, appHome.resolve("cache").toString());
                changed = true;
            }
            if (config.get(Config.OUTPUT_DIR).isBlank()) {
                config.set(Config.OUTPUT_DIR, appHome.resolve("output").toString());
                changed = true;
            }
            Files.createDirectories(Path.of(config.get(Config.CACHE_DIR)));
            Files.createDirectories(Path.of(config.get(Config.OUTPUT_DIR)));
            Files.createDirectories(Path.of(config.get(Config.OUTPUT_DIR) + "/logs/"));
            Files.createDirectories(Path.of(config.get(Config.OUTPUT_DIR) + "/csv/"));
            if (changed) config.save();
        } catch (IOException e) {
            showError(e);
            Platform.exit();
            return;
        }

        // Only the external inputs (DB, images, import) can block startup -- see class ConfigEditor.
        ConfigEditor editor = new ConfigEditor(config);
        while (!allSettingsSatisfied()) {
            if (!editor.showAndWait(null)) {
                Platform.exit();
                return;
            }
        }

        // (re)create in case the user pointed cache/output somewhere new under Advanced
        try {
            Files.createDirectories(Path.of(config.get(Config.OUTPUT_DIR)));
            Files.createDirectories(Path.of(config.get(Config.CACHE_DIR)));
        } catch (IOException e) {
            showError(e);
            Platform.exit();
            return;
        }

        sessionPath = Path.of(config.get(Config.SESSION_PATH));
        settings = Settings.from(config);

        InitTask initTask = new InitTask(settings);
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

        isOrb.set(false); isHash.set(false);

        initTask.setOnSucceeded(event -> Platform.runLater(() -> {
            ctx = initTask.getValue();
            showMainStage();
            initStage.hide();
        }));
        initTask.setOnFailed(e -> {
            Throwable ex = initTask.getException();
            ex.printStackTrace(); showError(ex);
            statusLabel.textProperty().unbind();
            statusLabel.setText("Exception occurred:" + ex.getMessage());
        });
        Thread initThread = new Thread(initTask, "pokecard-init");
        initThread.setDaemon(true);
        initThread.start();
    }

    private boolean allSettingsSatisfied() {
        for (Section sec : SECTIONS)
            for (Setting s : sec.settings())
                if (!satisfied(s, config.get(s.key()))) return false;
        return true;
    }

    public void showMainStage() {
        Stage mainStage = new Stage();
        //Main init...
        Label title = new Label("Pokecard");
        ImageView view1 = new ImageView();
        ImageView view2 = new ImageView();
        view1.setFitHeight(320);
        view2.setFitHeight(320);
        view1.setPreserveRatio(true);
        view2.setPreserveRatio(true);
        File initImport = new File("/Users/willtryon/javaprojects/PokeImageComp/pokecard/src/main/resources/importedImage.png");
        File initFound = new File("/Users/willtryon/javaprojects/PokeImageComp/pokecard/src/main/resources/foundImage.png");
        view1.setImage(new Image(initImport.toURI().toString()));
        view2.setImage(new Image(initFound.toURI().toString()));
        Label result = new Label();
        result.setWrapText(true);

        mainStage.setOnCloseRequest(event -> {
           Platform.exit();
           System.exit(0);
        });

        Button scan = new Button("Scan folder...");
        scan.setOnAction(e -> {
            scan.setDisable(true);

            Stage dialogStage = new Stage();
            dialogStage.initOwner(mainStage);
            dialogStage.initModality(Modality.WINDOW_MODAL);
            dialogStage.setTitle("Scan...");
            Label instructions = new Label("Please select the type of cards you want to scan:");
            ToggleGroup toggleGroup = new ToggleGroup();
            RadioButton normal = new RadioButton("Normal");
            normal.setToggleGroup(toggleGroup);
            RadioButton revHolo = new RadioButton("R.Holo");
            revHolo.setToggleGroup(toggleGroup);
            RadioButton holo = new RadioButton("Holo");
            holo.setToggleGroup(toggleGroup);
            toggleGroup.selectedToggleProperty().addListener((observable, oldToggle, newToggle) -> {
                if (newToggle != null) {
                    RadioButton selectedRb = (RadioButton) newToggle;
                    switch (selectedRb.getText()){
                        case "Normal" -> {
                            globalCardVersion = "NORMAL";
                            System.out.println(globalCardVersion);
                        }
                        case "R.Holo" -> globalCardVersion = "REVERSE HOLOFOIL";
                        case "Holo" -> globalCardVersion = "HOLOFOIL";
                    }
                }
            });
            CheckBox firstEdition = new CheckBox("First Edition");
            firstEdition.setOnAction(event -> {
                globalFirstEdition = firstEdition.isSelected();
                System.out.println(globalFirstEdition);
            });
            VBox mcq =  new VBox(10, normal, revHolo, holo);
            HBox options =  new HBox(10, mcq, firstEdition);
            options.setAlignment(Pos.CENTER);
            Button start = new Button("Start");
            start.setOnAction(event -> {
                dialogStage.close();
            });
            VBox setup = new VBox(10, instructions, options, start);
            setup.setAlignment(Pos.CENTER);
            setup.setSpacing(10);
            Scene setupScene = new Scene(setup, 300, 200);
            dialogStage.setScene(setupScene);
            dialogStage.showAndWait();
            dialogStage.setOnCloseRequest(event -> {
                return;
            });


            Task<Void> orbTask = new Task<>() {
                @Override
                protected Void call() throws SQLException {
                    ctx.cardDB.scanImports(ctx.importDB(), (msg, frac) -> {
                        updateMessage(msg);
                        updateProgress(frac, 1.0);
                    });
                    // Scene-graph mutations must run on the FX thread: refreshImports
                    // rebuilds the live TreeView's items, and doing it from this background
                    // thread races the FX layout pass -> ConcurrentModificationException.
                    Platform.runLater(() -> {
                        refreshImports(ctx.importDB());
                        scan.setDisable(false);
                    });
                    return null;
                }
            };

            runTask(orbTask, v -> {});
            orbTask.setOnSucceeded(event -> {
                Task<Void> ocrTask = new Task<>() {
                    @Override
                    protected Void call() {
                        try{
                            ctx.importDB.runOcr((msg, frac) -> {
                                updateMessage(msg);
                                updateProgress(frac, 1.0);
                            });
                        }catch(Exception e){
                            Platform.runLater(() -> showError(e));
                        }
                        // FX-thread only (see orbTask): refreshImports touches the live tree.
                        Platform.runLater(() -> {
                            refreshImports(ctx.importDB());
                            scan.setDisable(false);
                        });
                        return null;
                    }
                };
                runTask(ocrTask, v -> {});
            });
        });

        HBox imageView = new HBox(20, view1, view2);
        VBox center = new VBox(12, title, imageView, result, scan);
        center.setAlignment(Pos.CENTER);
        center.setPadding(new Insets(16));


        //build window...
        BorderPane root = new BorderPane();
        detailTabs = new TabPane();
        Tab scannerTab = new Tab("Scanner", center);
        root.setTop(buildTop(mainStage, detailTabs, view1, view2));
        scannerTab.setClosable(false);              // the home tab stays put
        detailTabs.getTabs().add(scannerTab);
        root.setCenter(detailTabs);
        root.setBottom(buildStatusBar());
        root.setLeft(buildSideTree(ctx.cardDB, ctx.importDB()));
        mainStage.setTitle("Pokecard");

        if(!(sessionPath.getFileName().toString().isEmpty())) {
            loadSession(mainStage, true);
            mainStage.setTitle("Pokecard - "+sessionPath.getFileName());
        }

        mainStage.setScene(new Scene(root, 1200, 600));
        mainStage.getScene().getRoot().setStyle("-fx-base: #2a2a2a;");
        mainStage.show();


        /*ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
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
            runTask(tick, v -> {
            });
        }), 0, 1, TimeUnit.MINUTES);*/
        //isOrb = false;
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "pokecard-price-fetcher");
            t.setDaemon(true);          // don't keep the JVM alive after the window closes
            return t;
        });

        scheduler.scheduleAtFixedRate(() -> Platform.runLater(() -> {
            Task<Void> priceTask = new Task<>() {
                @Override
                protected Void call() throws Exception {
                    syncPrices(true, (msg, frac) -> {
                        updateMessage(msg);
                        updateProgress(frac, 1.0);
                    });
                    return null;
                }
            };
            priceTask.setOnFailed(event -> {
                Throwable ex = priceTask.getException();
                showError(ex);
            });
            runTask(priceTask, v -> {
            });
        }), 0, 30, TimeUnit.MINUTES);
    }

    public void syncPrices(boolean force, ScanProgress progress) throws Exception {
        Path db = settings.cacheDir().resolve("tcg.db");
        progress.report("Retrieving price information...", -1);
        TcgdbEnv env = new TcgdbEnv(tcgdbDefaultCacheDir());
        TcgdbEnv.EnvHandle handle = env.prepare();
        int code = env.sync(handle, db, force);
        System.out.println("tcgdb sync exited " + code + "; db at " + db);
    }

    private VBox buildTop(Stage mainStage, TabPane detailTabs, ImageView view1, ImageView view2){

        //Menu Bar init...
        MenuItem newSessionItem = new MenuItem("New session");
        MenuItem saveSessionItem = new MenuItem("Save session");
        MenuItem loadSessionItem = new MenuItem("Load session");
        MenuItem importItem = new MenuItem("Import an image to scan...");
        MenuItem closeTabsItem = new MenuItem("Close tabs");
        MenuItem settingsItem = new MenuItem("Settings...");
        MenuItem exitItem = new MenuItem("Quit");
        Menu fileMenu = new Menu("File");
        fileMenu.getItems().addAll(newSessionItem, saveSessionItem, loadSessionItem, importItem, settingsItem, new SeparatorMenuItem(),closeTabsItem, new SeparatorMenuItem(), exitItem);
        Menu editMenu = new Menu("Edit");
        MenuItem editImportItem = new MenuItem("Edit import...");
        editMenu.getItems().addAll(editImportItem);
        Menu helpMenu = new Menu("Help");
        MenuItem aboutItem = new MenuItem("About");
        helpMenu.getItems().addAll(aboutItem);
        MenuBar menuBar = new MenuBar(fileMenu, editMenu, helpMenu);
        menuBar.setUseSystemMenuBar(true);

        //toolbar init...

        ToolBar toolBar = new ToolBar();
        Image hash1 = new Image(Objects.requireNonNull(getClass().getClassLoader().getResourceAsStream("icons/hash1a.png")));
        Button hash1Button = buildToolBarButton(hash1);
        hash1Button.disableProperty().bind(isHash.not());
        Image cv1 = new Image(Objects.requireNonNull(getClass().getClassLoader().getResourceAsStream("icons/cv1a.png")));
        Button cv1Button = buildToolBarButton(cv1);
        cv1Button.disableProperty().bind(isOrb.not());
        Image ocr1 = new Image(Objects.requireNonNull(getClass().getClassLoader().getResourceAsStream("icons/ocr1a.png")));
        Button ocr1Button = buildToolBarButton(ocr1);
        Image imp1 = new Image(Objects.requireNonNull(getClass().getClassLoader().getResourceAsStream("icons/imp1a.png")));
        Button imp1Button = buildToolBarButton(imp1);
        Image cd1 = new Image(Objects.requireNonNull(getClass().getClassLoader().getResourceAsStream("icons/cd1a.png")));
        Button cd1Button = buildToolBarButton(cd1);

        hash1Button.setOnAction(event -> {
            openSpreadSheetTab(currentImport(), "hash");
        });

        cv1Button.setOnAction(event -> {
            openSpreadSheetTab(currentImport(), "orb");
        });
        ocr1Button.setOnAction(event -> {
            openSpreadSheetTab(currentImport(), "ocr");
        });
        imp1Button.setOnAction(event -> {
            openSpreadSheetTab(null, "session");
        });
        cd1Button.setOnAction(event -> {
            new Finalize(ctx).finalizeImports(mainStage);
        });
        Separator sep = new Separator();

        toolBar.getItems().addAll(hash1Button, cv1Button, ocr1Button, imp1Button, sep, cd1Button);


        //Menu bar operations...


        newSessionItem.setOnAction(e -> {
            System.out.println("Creating new session...");
            saved = false;
            ctx.importDB.clearSession();
            refreshImports(ctx.importDB());
            currentSession = "";
            mainStage.setTitle("Pokecard - "+currentSession);
            sessionPath = Path.of(settings.outputDir()+"/"+ currentSession);
            config.set(Config.SESSION_PATH, String.valueOf(sessionPath));
        });

        saveSessionItem.setOnAction(e -> {
            saveSession(mainStage);
            mainStage.setTitle("Pokecard - "+currentSession);
        });

        loadSessionItem.setOnAction(e -> {
            //new NamedTask<Void>("Scanning Imports..."){loadSession(mainStage, false)}
            loadSession(mainStage, false);
            mainStage.setTitle("Pokecard - "+currentSession);
        });

        importItem.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Select a card to scan");
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Images", "*.png", "*.jpg", "*.jpeg"));
            File file = chooser.showOpenDialog(mainStage);
            if (file == null) return;
            Path image = file.toPath();
            Task<CardImports> t = new Task<>() {
                @Override
                protected CardImports call() throws Exception {
                    CardImports result = ctx.importDB().scanOne(image, (msg, frac) -> {
                        updateMessage(msg);
                        updateProgress(frac, 1.0);
                    });
                    System.out.println("\n\n" + ctx.importDB.getLastImports().getOrbWinner());
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

        closeTabsItem.setOnAction(e -> {
            Tab activeTab = detailTabs.getSelectionModel().getSelectedItem();
            if (activeTab != null) {
                detailTabs.getTabs().retainAll(activeTab);
            }else{
                detailTabs.getTabs().clear();
            }
        });

        settingsItem.setOnAction(e -> {
            if (new ConfigEditor(config).showAndWait(mainStage)) {
                new Alert(Alert.AlertType.INFORMATION, "Path changes apply next launch.", ButtonType.OK).showAndWait();
            }
        });

        exitItem.setOnAction(e -> {
            ctx.cardDB.shutdown();
            Platform.exit();
            System.exit(0);
        });

        editImportItem.setOnAction(e -> {
            if(currentImport() == null){
                showError(new IllegalArgumentException("No current import"));
            }
            new ImportsProperties(mainStage, ctx, currentImport());
        });

        aboutItem.setOnAction(e -> {
            Stage aboutStage = new Stage();
            aboutStage.setTitle("About Pokecard");
            Label name = new Label("Pokecard");
            Label version = new Label("Version 0.7.0");
            Label author = new Label("by willtryon");
            Button close = new Button("Close");
            VBox aboutLayout = new VBox(12, name, version, author, close);
            aboutLayout.setAlignment(Pos.CENTER);
            aboutLayout.setPadding(new Insets(16));
            aboutStage.setScene(new Scene(aboutLayout, 300, 150));
            close.setOnAction(e1 -> aboutStage.close());
            aboutStage.show();
        });
        return new VBox(15, menuBar, toolBar);
    }

    private Button buildToolBarButton(Image img){
        ImageView view = new ImageView(img);
        view.setFitHeight(24);
        view.setFitWidth(24);
        view.setPreserveRatio(true);
        Button button = new Button();
        button.setGraphic(view);
        button.setStyle("-fx-background-color: transparent; -fx-padding: 5;");
        return button;
    }


    private void saveSession(Stage owner){
        System.out.println("Saving imports to disk:");
        if(saved) ctx.importDB.writeImportsToDisk(currentSession);
        if (!saved) {
            FileChooser fc = new FileChooser();
            fc.setTitle("Save Session");
            fc.getExtensionFilters().addAll(
                    new FileChooser.ExtensionFilter("Binary (*.dat)", "*.dat")
            );
            File targetFile = fc.showSaveDialog(owner);
            if (targetFile != null) {
                String filePath = targetFile.getAbsolutePath();
                String extension = ".dat";

                if (filePath.toLowerCase().endsWith(extension + extension)) {
                    filePath = filePath.substring(0, filePath.length() - extension.length());
                }
                else if (!filePath.toLowerCase().endsWith(extension)) {
                    filePath += extension;
                }
                File fixedFile = new File(filePath);
                sessionPath = fixedFile.toPath();
                currentSession = sessionPath.getFileName().toString();
                ctx.importDB.writeImportsToDisk(currentSession);
                config.set(Config.SESSION_PATH, fixedFile.getAbsolutePath());
                try {
                    config.save();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        }
        statusBar.setText("Saving Session...");
        statusProgress.setVisible(true);
        System.out.println("Done.");
        statusBar.setText("Ready.");
        statusProgress.setVisible(false);
        saved = true;
    }

    private void loadSession(Stage owner, boolean tf){
        System.out.println("Loading imports from disk:");
        //statusBar.setText("Loading Session...");
        //statusProgress.setVisible(true);
        if (tf) {
            ctx.importDB.readImportsFromDisk(sessionPath);
        }
        else{
            FileChooser fc = new FileChooser();
            fc.getExtensionFilters().addAll(new FileChooser.ExtensionFilter("Binary (*.dat)", "*.dat"));
            File targetFile = fc.showOpenDialog(owner);
            if(targetFile != null){
                sessionPath = targetFile.toPath();
                ctx.importDB.readImportsFromDisk(sessionPath);
            }
        }
        List<CardImports> restored = ctx.importDB.getImports();
        currentSession = sessionPath.toString();
        config.set(Config.SESSION_PATH, currentSession);
        try {
            config.save();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        System.out.println("Loaded " + restored.size() + " imports.");
        refreshImports(ctx.importDB());
        /*
        if (!restored.isEmpty()) {
            System.out.println(restored.getFirst().getORBRecordHistory() + "\n" + restored.get(0).getOrbWinner());
        }*/
        System.out.println("Done.");
        //statusBar.setText("Ready.");
        //statusProgress.setVisible(false);
    }

    private HBox buildStatusBar() {
        statusBar = new Label();
        statusBar.textProperty().bind(
                statusTask.flatMap(Task::messageProperty).orElse("Ready.")
        );
        statusProgress = new ProgressBar();
        statusProgress.setPrefWidth(120);
        statusProgress.progressProperty().bind(
                statusTask.flatMap(Task::progressProperty).orElse(0.0));
        statusProgress.setCursor(Cursor.HAND);
        statusProgress.setOnMouseClicked(e -> toggleTaskPopOver());
        taskView.getTasks().addListener((ListChangeListener<Task<?>>) c -> {
            var live = taskView.getTasks();
            statusTask.set(live.isEmpty() ? null : live.get(live.size() - 1));
        });
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bar = new HBox(8, statusBar, spacer, statusProgress);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(4, 8, 4, 8));
        bar.setStyle("-fx-border-color: #cccccc; -fx-border-width: 1 0 0 0;");
        return bar;
    }

    private TreeView<SideNode> buildSideTree(CardIndex cardDB, CardImportsIndex importDB) {
        TreeItem<SideNode> rootItem = new TreeItem<>(new Group("Cards"));
        rootItem.getChildren().add(buildCardsBranch(cardDB));
        rootItem.getChildren().add(buildImportsBranch(importDB));
        rootItem.setExpanded(true);

        TreeView<SideNode> tree = new TreeView<>(rootItem);
        tree.setCellFactory(tv -> new TreeCell<>() {
            @Override
            protected void updateItem(SideNode node, boolean empty) {
                super.updateItem(node, empty);
                setText((empty||node==null) ? null : switch(node){
                    case Group g -> g.label();
                    case CardEntry c -> c.sig().getCardID();
                    case ImportEntry i -> {
                        Path q = i.imp().getQueryImage();
                        yield q == null ? "(unknown image)" : q.getFileName().toString();
                    }
                    default -> throw new IllegalStateException("Unexpected value: " + node);

                });

            }
        });
        tree.getSelectionModel().selectedItemProperty().addListener((obs, old, item) -> {
            if(item == null) return;
            switch (item.getValue()){
                case CardEntry c -> openCardTab(c.sig());
                case ImportEntry i -> openImportTab(i.imp());
                case Group g -> {}
            }
        });
        return tree;
    }

    private TreeItem<SideNode> buildCardsBranch(CardIndex cardDB) {
        TreeItem<SideNode> cards = new TreeItem<>(new Group("Database"));
        Map<String, List<CardSignature>> bySeries = new TreeMap<>();
        for(int i = 0; i<cardDB.getCardIndexSize(); i++){
            CardSignature sig = cardDB.getCardSignature(i);
            if(sig==null) continue;
            bySeries.computeIfAbsent(seriesOf(sig), k ->new ArrayList<>()).add(sig);
        }
        for(var e : bySeries.entrySet()){
            TreeItem<SideNode> series = new TreeItem<>(new Group(e.getKey()));
            for(CardSignature sig : e.getValue()){
                series.getChildren().add(new TreeItem<>(new CardEntry(sig)));
            }
            cards.getChildren().add(series);
        }
        return cards;
    }

    private TreeItem<SideNode> buildImportsBranch(CardImportsIndex importDB) {
        importsBranch = new TreeItem<>(new Group("Imports"));
        refreshImports(importDB);
        return importsBranch;
    }

    private void refreshImports(CardImportsIndex importDB) {
        importsBranch.getChildren().clear();
        for (CardImports imp : importDB.getImports()) {
            importsBranch.getChildren().add(new TreeItem<>(new ImportEntry(imp)));
        }
        importsBranch.setExpanded(true);
    }

    private String seriesOf(CardSignature sig) {
        Path p = sig.getImgPath();
        if (p == null || p.getParent() == null) return "Unknown";
        return p.getParent().getFileName().toString();
    }

    private void openCardTab(CardSignature sig) {
        isOrb.set(false); isHash.set(false);
        if (focusExistingTab("card:" + sig.getCardID())) return;
        Tab tab = new Tab(sig.getCardID(), buildCardDetail(sig));
        tab.setId("card:" + sig.getCardID());
        detailTabs.getTabs().add(tab);
        detailTabs.getSelectionModel().select(tab);
    }

    private void openImportTab(CardImports imp) {
        isOrb.set(true); isHash.set(true);
        Path q = imp.getQueryImage();
        String key = "import:" + (q == null ? String.valueOf(imp.hashCode()) : q.toString());
        if (focusExistingTab(key)) return;
        Tab tab = new Tab(q == null ? "Import" : q.getFileName().toString(), buildImportDetail(imp));
        tab.setId(key);
        tab.setUserData(imp);
        detailTabs.getTabs().add(tab);
        detailTabs.getSelectionModel().select(tab);
    }

    private void openSpreadSheetTab(CardImports imp, String args) {
        isOrb.set(false); isHash.set(false);
        Path q = sessionPath;
        if(!args.equals("session")) q = imp.getQueryImage();
        String key = "spreadsheet:" + (q == null ? String.valueOf(imp.hashCode()) : q.toString());
        if (focusExistingTab(key)) return;
        String title = (q == null ? "Import" : q.getFileName().toString()) + " \u2013 ORB matches";
        SpreadsheetView sv = buildSpreadsheet(imp, args);
        Tab tab = new Tab(title, sv);
        tab.setId(key);
        detailTabs.getTabs().add(tab);
        detailTabs.getSelectionModel().select(tab);
        if(firstRun){
            Alert a = new Alert(Alert.AlertType.INFORMATION, "In the score column, a value of 100 means my program selected it, and a value of 0 means yours did.");
            a.showAndWait();
            firstRun = false;
        }

        Platform.runLater(() ->{
            sv.setOnKeyPressed(event -> {
                if(event.isShortcutDown() && event.getCode() == KeyCode.C) {
                    sv.copyClipboard();
                }
            });
        });

    }

    private boolean focusExistingTab(String id) {
        for (Tab t : detailTabs.getTabs()) {
            if (id.equals(t.getId())) {
                detailTabs.getSelectionModel().select(t);
                return true;
            }
        }
        return false;
    }

    private CardImports currentImport() {
        Tab tab = detailTabs.getSelectionModel().getSelectedItem();
        if (tab != null && tab.getUserData() instanceof CardImports imp) {
            return imp;
        }
        return null;
    }

    private Node buildCardDetail(CardSignature sig) {
        VBox box = new VBox(10);
        box.setPadding(new Insets(16));
        box.setAlignment(Pos.CENTER_LEFT);

        Label id = new Label("Card ID: " + sig.getCardID());
        id.setStyle("-fx-font-size: 15px; -fx-font-weight: bold;");

        Label cardInformation = new Label("Card information:");
        Label cardName = new Label();
        Label collectorNum = new Label();
        Label series = new Label();
        Label idTCGP = new Label();
        Label cardType = new Label();
        Label rarity = new Label();
        Label price = new Label();
        Label description = new Label();
        VBox info = new VBox(10, cardInformation, cardName, collectorNum, series, idTCGP, cardType, rarity, price, description);
        info.setPadding(new Insets(16));
        info.setAlignment(Pos.CENTER_RIGHT);
        info.setSpacing(10);
        FullCardSignature orbSig = null;
        try {
            orbSig = new FullCardSignature(sig, settings.dbPath(), settings.cacheDir(), globalCardVersion, globalFirstEdition);
            System.out.println(orbSig.getName());
        } catch (SQLException e) {
            showError(e);
        }

        cardName.setText("Name: " + (orbSig == null ? "" : orbSig.getName()));
        collectorNum.setText("Collection Number: "+(orbSig == null ? "" : orbSig.getExpCardNumber()));
        series.setText("Series: "+(orbSig == null ? "" : orbSig.getExpName()));
        idTCGP.setText("TCGP ID: "+(orbSig == null ? "" : orbSig.getIdTCGP()));
        cardType.setText("Type: "+(orbSig == null ? "" : orbSig.getCardType()));
        rarity.setText("Rarity: "+(orbSig == null ? "" : orbSig.getRarity()));
        price.setText("Price: "+(orbSig == null ? "" : String.valueOf(orbSig.getPrice())));
        description.setText((orbSig == null ? "" : orbSig.getDescription()));

        Path p = sig.getImgPath();
        boolean hasOrb = sig.getMatData() != null && !sig.getMatData().empty();
        box.getChildren().addAll(
                id,
                new Label("Image: " + (p == null ? "(none on disk)" : p.toString())),
                new Label("pHash: " + (sig.getBinaryHash() != null ? "yes" : "no")
                        + "    ORB: " + (hasOrb ? "yes" : "no"))
        );

        if (p != null && Files.exists(p)) {
            ImageView iv = new ImageView(new Image(p.toUri().toString()));
            iv.setPreserveRatio(true);
            iv.setFitHeight(360);
            box.getChildren().add(iv);
        }
        return new HBox(10, box, info);
    }


    private Node buildImportDetail(CardImports imp) {
        HBox content = new HBox(10);
        content.setPadding(new Insets(16));

        ToggleGroup group = new ToggleGroup();

        ToggleButton overview = new ToggleButton("Overview");
        ToggleButton hashList = new ToggleButton("Hash");
        ToggleButton orbList = new ToggleButton("ORB");
        ToggleButton ocrList = new ToggleButton("OCR");

        ToggleButton[] buttons = { overview, hashList, orbList, ocrList };

        for(ToggleButton b : buttons){
            b.setToggleGroup(group);
        }

        int size = imp.getRecordSize();

        Label orbLabel  = new Label();
        Label hashLabel = new Label();
        Label ocrLabel = new Label();

        ImageView image1 = new ImageView(); image1.setPreserveRatio(true); image1.setFitHeight(300);
        ImageView image2 = new ImageView(); image2.setPreserveRatio(true); image2.setFitHeight(300);
        HBox images = new HBox(16, image1, image2);

        Path q = imp.getQueryImage();
        if (q != null && Files.exists(q)) image1.setImage(new Image(q.toUri().toString()));

        Label count = new Label();
        Button previous = new Button("Previous");
        Button next = new Button("Next");

        Label cardInformation = new Label("Card information:");
        Label cardName = new Label();
        Label collectorNum = new Label();
        Label series = new Label();
        Label idTCGP = new Label();
        Label cardType = new Label();
        Label rarity = new Label();
        Label price = new Label();
        Label description = new Label();
        VBox info = new VBox(10, cardInformation, cardName, collectorNum, series, idTCGP, cardType, rarity, price, description);
        info.setPadding(new Insets(16));
        info.setSpacing(10);

        int[] pos = {0};

        Runnable render = () -> {
            if (size == 0) {
                orbLabel.setText("ORB match: -");
                hashLabel.setText("pHash match: -");
                ocrLabel.setText("OCR match: -");
                count.setText("(0 of 0)");
                previous.setDisable(true);
                next.setDisable(true);
                return;
            }
            int p = pos[0];
            CardSignature orbSigVictim  = imp.getARecordRecord(p, "orb");
            FullCardSignature orbSig = null;
            try {
                orbSig = new FullCardSignature(orbSigVictim, settings.dbPath(), settings.cacheDir(), imp.getCardVersion(), imp.getFirstEdition());
                System.out.println(orbSig.getName());
            } catch (SQLException e) {
                showError(e);
            }
            CardSignature hashSig = imp.getARecordRecord(p, "hash");
            CardImports.Match ocrSig = imp.getOcrWinner();

            orbLabel.setText ("ORB match: "   + (orbSig  == null ? "-" : orbSig.getCardID()  + "  (" + imp.getARecordScore(p, "orb")  + ")"));
            hashLabel.setText("pHash match: " + (hashSig == null ? "-" : hashSig.getCardID() + "  (" + imp.getARecordScore(p, "hash") + ")"));
            ocrLabel.setText("OCR match: " + (ocrSig == null || ocrSig.cardID() == null ? "-" : ocrSig.cardID()));

            Path orbImg = (orbSig != null) ? orbSig.getImgPath() : null;
            image2.setImage((orbImg != null && Files.exists(orbImg)) ? new Image(orbImg.toUri().toString()) : null);

            count.setText("(" + (p + 1) + " of " + size + ")");
            previous.setDisable(p == 0);
            next.setDisable(p >= size - 1);

            cardName.setText("Name: " + (orbSig == null ? "" : orbSig.getName()));
            collectorNum.setText("Collection Number: "+(orbSig == null ? "" : orbSig.getExpCardNumber()));
            series.setText("Series: "+(orbSig == null ? "" : orbSig.getExpName()));
            idTCGP.setText("TCGP ID: "+(orbSig == null ? "" : orbSig.getIdTCGP()));
            cardType.setText("Type: "+(orbSig == null ? "" : orbSig.getCardType()));
            rarity.setText("Rarity: "+(orbSig == null ? "" : orbSig.getRarity()));
            price.setText("Price: "+(orbSig == null ? "" : String.valueOf(orbSig.getPrice())));
            description.setText((orbSig == null ? "" : orbSig.getDescription()));
        };

        previous.setOnAction(e -> { if (pos[0] > 0)        { pos[0]--; render.run(); } });
        next.setOnAction(e ->     { if (pos[0] < size - 1) { pos[0]++; render.run(); } });

        render.run();

        HBox bar = new HBox(10, overview, hashList, orbList, ocrList);

        VBox imgStack = new VBox(10);
        imgStack.getChildren().addAll(orbLabel, hashLabel, ocrLabel, images, new HBox(16, previous, count, next));
        content.getChildren().addAll(imgStack, info);
        return new VBox(10, bar, content);
    }


    private ImageView imageAt(String uri) {
        ImageView iv = new ImageView(new Image(uri));
        iv.setPreserveRatio(true);
        iv.setFitHeight(300);
        return iv;
    }

    private SpreadsheetView buildSpreadsheet(CardImports imp, String args) {
        int rows;
        if(args.equals("session")) rows = ctx.importDB.getImports().size();
        else if (args.equals("ocr")) rows = 1;
        else rows = imp.getRecordSize2();

        Map<Integer, Double> heights = new HashMap<>();
        GridBase grid = new GridBase(rows, 7);

        grid.getColumnHeaders().addAll("Subject", "DB image", "Card ID", "TCGP ID", "Price", "Score", "Release Date");
        for(int i = 0; i < rows; i++){
            heights.put(i, 140.0);
        }
        grid.setRowHeightCallback(new GridBase.MapBasedRowHeightFactory(heights));
        ObservableList<ObservableList<SpreadsheetCell>> data = FXCollections.observableArrayList();
        List<CardImports> imports = ctx.importDB.getImports();
        for (int r = 0; r < rows; r++) {
            CardSignature subSig = null; Double score = 0.0;
            CardImports rowImp = imp;
            switch (args){
                case "session" -> {
                    rowImp = imports.get(r);
                    CardImports.Match show = rowImp.bestMatch();
                    subSig = ctx.cardDB.findCardId(show.cardID());
                    score = rowImp.hasOcr() ? 0.0 : 100.0;
                }
                case "orb", "hash" -> {
                    subSig = imp.getARecordRecord(r, args);
                    score = imp.getARecordScore(r, args);
                }
                case "ocr" ->  {
                    CardImports.Match show = imp.getOcrWinner();
                    subSig = ctx.cardDB.findCardId(show.cardID());
                    score = 100.0;
                }
            }
            try{
                SpreadsheetCell subCell = SpreadsheetCellType.STRING.createCell(r, 0, 1, 1, "");
                Path p1 = (rowImp == null) ? null : rowImp.getQueryImage();
                if(p1 != null && Files.exists(p1)){
                    Image thumb = new Image(p1.toUri().toString(), 120, 0, true, true, true);
                    ImageView iv = new ImageView(thumb);;
                    iv.setPreserveRatio(true);
                    iv.setFitHeight(130);
                    subCell.setGraphic(iv);
                }
                SpreadsheetCell imgCell = SpreadsheetCellType.STRING.createCell(r, 1, 1, 1, "");
                Path p = (subSig == null) ? null : subSig.getImgPath();
                if(p != null && Files.exists(p)){
                    Image thumb = new Image(p.toUri().toString(), 120, 0, true, true, true);
                    ImageView iv = new ImageView(thumb);
                    iv.setPreserveRatio(true);
                    iv.setFitHeight(130);
                    imgCell.setGraphic(iv);
                }
                SpreadsheetCell idCell = SpreadsheetCellType.STRING.createCell(
                        r, 2, 1, 1, subSig == null ? "-" : subSig.getCardID());
                int tcgp = 0;
                double roundedValue = 0.0;
                String release = "";
                if (subSig != null) {
                    FullCardSignature domSig = new FullCardSignature(
                            subSig, settings.dbPath(), settings.cacheDir(),
                            rowImp == null ? globalCardVersion : rowImp.getCardVersion(), rowImp.getFirstEdition());
                    tcgp = domSig.getIdTCGP();
                    roundedValue = new BigDecimal(Double.toString(domSig.getPrice()))
                            .setScale(3, RoundingMode.DOWN).doubleValue();
                    release = domSig.getReleaseDate();
                }
                SpreadsheetCell numCell = SpreadsheetCellType.INTEGER.createCell(r, 3, 1, 1, tcgp);
                SpreadsheetCell priceCell = SpreadsheetCellType.DOUBLE.createCell(r, 4, 1, 1, roundedValue+0.60);
                SpreadsheetCell scoreCell = SpreadsheetCellType.DOUBLE.createCell(r, 5, 1, 1, score);
                SpreadsheetCell yearCell = SpreadsheetCellType.STRING.createCell(r, 6, 1, 1, release);
                data.add(FXCollections.observableArrayList(subCell, imgCell, idCell, numCell, priceCell, scoreCell,  yearCell));
            }catch (SQLException e){
                showError(e);
            }

        }
        grid.setRows(data);

        SpreadsheetView sv = new SpreadsheetView(grid);
        sv.setEditable(true);
        sv.getColumns().get(0).setMinWidth(130);
        sv.getColumns().get(1).setMinWidth(130);
        sv.getColumns().get(2).setMinWidth(120);
        return sv;
    }

    private Task<?> currentStatusTask;
    private final AtomicBoolean scanRunning = new AtomicBoolean(false);

    private void toggleTaskPopOver() {
        if (taskPopOver == null) {
            taskView.setPrefSize(420, 260);
            taskPopOver = new PopOver(taskView);
            taskPopOver.setArrowLocation(PopOver.ArrowLocation.BOTTOM_RIGHT);
        }
        if (taskPopOver.isShowing()) taskPopOver.hide();
        else taskPopOver.show(statusProgress);
    }

    private <T> void runTask(Task<T> task, Consumer<T> onSuccess) {
        taskView.getTasks().add(task);          // before starting the thread
        if (onSuccess != null) task.setOnSucceeded(e -> onSuccess.accept(task.getValue()));
        task.setOnFailed(e -> showError(task.getException()));
        Thread t = new Thread(task);
        t.setDaemon(true);
        t.start();
    }

    private void finishTask(Task<?> task){
        if (currentStatusTask == task) {      // only the task that's still "current" may unbind
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

    private Config.Settings settings;

    protected InitTask(Settings settings) {
        this.settings = settings;
    }

    @Override
    protected App.AppContext call() throws Exception{
        String url = "jdbc:sqlite:" + settings.dbPath();
        updateMessage("Connecting to database...");
        int size;
        try (Connection conn = DriverManager.getConnection(url);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) AS n FROM cards")) {
            size = rs.next() ? rs.getInt("n") : 0;
        }
        Main.size = size;
        System.out.println(settings.cacheDir().resolve("tcg.db"));

        Path cacheFile = settings.cacheDir().resolve("cache_meta.dat");
        CardIndex cardDB;
        if (Files.isRegularFile(cacheFile)) {
            updateMessage("Loading cache (" + size + " cards)...");
            cardDB = new CardIndex(settings);
        } else {
            updateMessage("Computing image data for " + size + " cards...");
            cardDB = new CardIndex(size, url, settings);
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
                cardDB.writeToDisk();
            }
        }
        updateMessage("Verifying python env...");
        PokeocrEnv env = new PokeocrEnv(ocrDefaultCacheDir(), settings);
        PokeocrEnv.EnvHandle handle = env.prepare();
        updateMessage("Rebuilding database...");
        TcgdbEnv env2 = new TcgdbEnv(tcgdbDefaultCacheDir());
        TcgdbEnv.EnvHandle handle2 = env2.prepare();
        new PokemonCardNameCleaner(settings.dbPath(), false);
        var stats = PokemonCardNameCleaner.reconcileIdTcgp(settings.dbPath(), settings.cacheDir().resolve("tcg.db"), false);
        updateMessage("Starting...");
        CardImportsIndex importDB = cardDB.newImportsIndex();
        return new App.AppContext(cardDB, importDB, size);
    }
}

class ConfigEditor {
    private final Config config;

    protected ConfigEditor(Config config) {
        this.config = config;
    }

    protected boolean showAndWait(Window owner) {
        Stage dialog = new Stage();
        if (owner != null) {
            dialog.initOwner(owner);
        }
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.setTitle("Settings");

        // one input control per key, built ONCE so edits survive switching sections
        Map<String, TextInputControl> inputs = new HashMap<>();
        Map<App.Section, Node> pages = new LinkedHashMap<>();
        for (App.Section sec : App.SECTIONS) pages.put(sec, buildPage(sec, inputs, dialog));

        // left: sidebar of section names
        ListView<App.Section> sidebar = new ListView<>();
        sidebar.getItems().addAll(App.SECTIONS);
        sidebar.setPrefWidth(150);
        sidebar.setCellFactory(lv -> new ListCell<App.Section>() {
            @Override
            protected void updateItem(App.Section s, boolean empty) {
                super.updateItem(s, empty);
                setText(empty || s == null ? null : s.name());
            }
        });

        // right: detail pane, swapped on selection
        StackPane detail = new StackPane();
        sidebar.getSelectionModel().selectedItemProperty().addListener((obs, old, sel) -> {
            if (sel != null) detail.getChildren().setAll(pages.get(sel));
        });
        sidebar.getSelectionModel().selectFirst();

        Label error = new Label();
        error.setStyle("-fx-text-fill: #c0392b");
        Button save = new Button("Save");
        Button cancel = new Button("Cancel");
        final boolean[] saved = {false};

        save.setOnAction(e -> {
            for (App.Section sec : App.SECTIONS) {
                for (App.Setting s : sec.settings()) {
                    String v = inputs.get(s.key()).getText().trim();
                    if (!App.satisfied(s, v)) {
                        error.setText("\u201C" + s.label() + "\u201D in " + sec.name() + " is missing or invalid.");
                        sidebar.getSelectionModel().select(sec);
                        return;
                    }
                }
            }
            try {
                for (App.Section sec : App.SECTIONS)
                    for (App.Setting s : sec.settings())
                        config.set(s.key(), inputs.get(s.key()).getText().trim());
                config.save();
                saved[0] = true;
                dialog.close();
            } catch (IOException ex) {
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

    private Node buildPage(App.Section sec, Map<String, TextInputControl> inputs, Window owner) {
        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(10);
        int row = 0;
        for (App.Setting s : sec.settings()) {
            TextField field = new TextField();
            switch(s.kind()){
                case SECRET -> {

                }
                case TEXT -> {
                    //field = new TextField();
                }
                case DIRECTORY,  FILE -> {
                    //field = new TextField();
                    Button browse = createBrowse(owner, s, field);
                    grid.add(browse, 2, row);
                }
                case MODE ->{
                    ObservableList<String> options = FXCollections.observableArrayList(
                            "Easy Ocr", "Got Ocr", "Trocr", "Qwen Model"
                    );
                    ComboBox<String> ocrMode = new ComboBox<>(options);
                    System.out.println(config.get(s.key()));
                    if(config.get(s.key()).isBlank()){
                        ocrMode.setPromptText("Select an OCR model to use...");
                    }else{
                        ocrMode.setPromptText(config.get(s.key()));
                    }
                    //ocrMode.setVisibleRowCount(4);
                    ocrMode.setOnAction(e -> {
                        switch(ocrMode.getValue()){
                            case "Easy Ocr" -> field.setText("easy-ocr");
                            case "Got Ocr" -> field.setText("got-ocr2");
                            case "Trocr" -> field.setText("trocr");
                            case "Qwen Model" -> field.setText("qwen2.5-vl");
                        }
                    });

                    grid.add(ocrMode, 1, row);
                }
            }

            field.setText(config.get(s.key()));
            field.setPrefColumnCount(30);
            inputs.put(s.key(), field);
            if(!(s.kind()==App.Kind.MODE)){
                grid.add(field, 1, row);
            }
            grid.add(new Label(s.label()), 0, row);
            row++;
        }
        Label header = new Label(sec.name());
        header.setStyle("-fx-font-size: 15px; -fx-font-weight: bold;");
        return new VBox(12, header, grid);
    }

    private static Button createBrowse(Window owner, App.Setting s, TextField field) {
        Button browse = new Button("Browse\u2026");
        browse.setOnAction(e -> {
            File f;
            if (s.kind() == App.Kind.DIRECTORY) {
                DirectoryChooser dc = new DirectoryChooser();
                dc.setTitle("Choose " + s.label());
                f = dc.showDialog(owner);
            } else {
                FileChooser fc = new FileChooser();
                fc.setTitle("Choose " + s.label());
                f = fc.showOpenDialog(owner);
            }
            if (f != null) field.setText(f.getAbsolutePath());
        });
        return browse;
    }
}

class Finalize{
    private App.AppContext ctx;
    private List<CardImports> selectedItems;

    protected Finalize(App.AppContext ctx) {
        this.ctx = ctx;
    }

    protected void finalizeImports(Stage mainStage){
        Stage stage = new Stage();
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.initOwner(mainStage);
        stage.setTitle("Finalize Imports");
        Scene scene1 = finalizeScene1(stage, () -> {
            stage.setScene(finalizeScene2(stage));
        });
        //Scene scene1 = finalizeScene1(stage);
        stage.setTitle("String-Based Object Checklist");
        stage.setScene(scene1);
        stage.showAndWait();
    }

    private Scene finalizeScene1(Stage stage, Runnable runnable) {
        Label stage1Info = new Label("Select the imports you want to finalize. To change an import's settings, select properties.");

        ObservableList<CardImports> candidates = FXCollections.observableArrayList();
        for(CardImports c : ctx.importDB().getImports()){
            c.selectedProperty().set(true);
            candidates.add(c);
        }
        ListView<CardImports> listView = new ListView<>(candidates);
        StringConverter<CardImports> converter = new StringConverter<>() {
            @Override
            public String toString(CardImports item) {
                return(item == null ? "" : String.valueOf(item.getQueryImage().getFileName()));
            }
            @Override
            public CardImports fromString(String item) {return null;}
        };
        listView.setCellFactory(CheckBoxListCell.forListView(
                CardImports::selectedProperty, converter
        ));
        Button nextButton = new Button("Next");
        Button cancelButton = new Button("Cancel");
        Button propertiesButton = new Button("Properties");
        nextButton.setOnAction(e -> {
            selectedItems = candidates.stream()
                    .filter(c -> c.selectedProperty().get())
                    .toList();
            runnable.run();
        });
        cancelButton.setOnAction(e -> {
            stage.close();
        });
        propertiesButton.setOnAction(e -> {
            CardImports temp = listView.getSelectionModel().getSelectedItem();
            new ImportsProperties(stage, ctx, temp);
        });

        HBox buttons = new HBox(10, nextButton, cancelButton, propertiesButton);
        VBox root = new VBox(stage1Info,listView, buttons);
        root.setAlignment(Pos.CENTER);
        root.setPadding(new Insets(10));
        return new Scene(root, 350, 200);
    }

    private Scene finalizeScene2(Stage stage) {
        Label label = new Label("tee hee");
        VBox root = new VBox(10, label);
        return new Scene(root, 350, 200);
    }

}

class ImportsProperties{
    private App.AppContext ctx;
    private CardImports selected;

    protected ImportsProperties(Stage mainStage, App.AppContext ctx,  CardImports selected) {
        this.ctx = ctx;
        buildEditor(mainStage, selected);
    }

    protected void buildEditor(Stage mainStage, CardImports selected) {
        Stage editor = new Stage();
        editor.initModality(Modality.APPLICATION_MODAL);
        editor.initOwner(mainStage);
        editor.setTitle("Editing "+selected.getQueryImage().getFileName());
        ObservableList<PropertySheet.Item> properties = BeanPropertyUtils.getProperties(selected);
        PropertySheet propertySheet = new PropertySheet(properties);
        VBox test = new VBox(10, propertySheet);
        test.setAlignment(Pos.CENTER);
        test.setPadding(new Insets(10));
        editor.setScene(new Scene(test));
        editor.show();
    }


}
