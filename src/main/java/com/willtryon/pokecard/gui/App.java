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
import java.sql.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public class App extends Application {

    private Config config;
    private Path cacheDir;
    private Path outputDir;
    private Path dbPath;
    private Path sessionPath;
    private String currentSession;
    private boolean saved;
    private AppContext ctx;

    private Label statusBar;
    private ProgressBar statusProgress;
    private TabPane detailTabs;
    private TreeItem<SideNode> importsBranch;




    enum Kind {
        DIRECTORY, FILE, TEXT, SECRET, BOOLEAN;

        boolean isValidValue(String v) {
            return switch (this) {
                case DIRECTORY -> Files.isDirectory(Path.of(v));
                case FILE -> Files.isRegularFile(Path.of(v));
                case TEXT, SECRET -> !v.isBlank();
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

        dbPath = Path.of(config.get(Config.DB_PATH));
        Path imagesDir = Path.of(config.get(Config.IMAGES_DIR));
        Path compareDir = Path.of(config.get(Config.COMPARE_DIR));
        outputDir = Path.of(config.get(Config.OUTPUT_DIR));
        cacheDir = Path.of(config.get(Config.CACHE_DIR));
        sessionPath = Path.of(config.get(Config.SESSION_PATH));
        int orbThreads = 1;
        try{
            orbThreads = Integer.parseInt(config.get(Config.SCAN_THREADS));
        }catch(NumberFormatException e){
            config.set(Config.SCAN_THREADS, String.valueOf(config.getScanThreads()));
        }

        InitTask initTask = new InitTask(dbPath, imagesDir, compareDir, outputDir, cacheDir, orbThreads);
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
        //Menu Bar init...
        MenuItem newSessionItem = new MenuItem("New session");
        MenuItem saveSessionItem = new MenuItem("Save session");
        MenuItem loadSessionItem = new MenuItem("Load session");
        MenuItem importItem = new MenuItem("Import an image to scan...");
        MenuItem settingsItem = new MenuItem("Settings...");
        MenuItem exitItem = new MenuItem("Quit");
        Menu fileMenu = new Menu("File");
        fileMenu.getItems().addAll(newSessionItem, saveSessionItem, loadSessionItem, importItem, settingsItem, new SeparatorMenuItem(), exitItem);
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
            Task<Void> scanTask = new Task<>() {
                @Override
                protected Void call() {
                    ctx.cardDB.scanImports(ctx.importDB(), (msg, frac) -> {
                        updateMessage(msg);
                        updateProgress(frac, 1.0);
                    });
                    refreshImports(ctx.importDB());
                    scan.setDisable(false);
                    return null;
                }
            };
            runTask(scanTask, v -> {
            });
        });
        HBox imageView = new HBox(20, view1, view2);
        VBox center = new VBox(12, title, imageView, result, scan);
        center.setAlignment(Pos.CENTER);
        center.setPadding(new Insets(16));

        //Menu bar operations...


        newSessionItem.setOnAction(e -> {
            System.out.println("Creating new session...");
            saved = false;
            ctx.importDB.clearSession();
            refreshImports(ctx.importDB());
            currentSession = "";
            mainStage.setTitle("Pokecard - "+currentSession);
            sessionPath = Path.of(outputDir+"/"+ currentSession);
            config.set(Config.SESSION_PATH, String.valueOf(sessionPath));
        });

        saveSessionItem.setOnAction(e -> {
            saveSession(mainStage);
            mainStage.setTitle("Pokecard - "+currentSession);
        });

        loadSessionItem.setOnAction(e -> {
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
        detailTabs = new TabPane();
        Tab scannerTab = new Tab("Scanner", center);
        scannerTab.setClosable(false);              // the home tab stays put
        detailTabs.getTabs().add(scannerTab);
        root.setCenter(detailTabs);
        root.setBottom(buildStatusBar());
        root.setLeft(buildSideTree(ctx.cardDB, ctx.importDB()));
        Alert a = new Alert(Alert.AlertType.INFORMATION, "To safely exit the program, click 'Quit' in the file menu. \nIf you click the x, the program will halt and you'll have to kill the program in the terminal.", ButtonType.OK);
        a.setHeaderText("Notice");
        a.showAndWait();
        mainStage.setTitle("Pokecard");

        if(!(sessionPath.getFileName().toString().isEmpty())) {
            loadSession(mainStage, true);
            mainStage.setTitle("Pokecard - "+sessionPath.getFileName());
        }

        mainStage.setScene(new Scene(root, 700, 600));
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
    }

    private void saveSession(Stage owner){
        System.out.println("Saving imports to disk:");
        if(saved) ctx.importDB.writeImportsToDisk(outputDir, currentSession);
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
                ctx.importDB.writeImportsToDisk(outputDir, currentSession);
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
        statusBar.setText("Loading Session...");
        statusProgress.setVisible(true);
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
        if (!restored.isEmpty()) {
            System.out.println(restored.getFirst().getORBRecordHistory() + "\n" + restored.get(0).getOrbWinner());
        }
        System.out.println("Done.");
        statusBar.setText("Ready.");
        statusProgress.setVisible(false);
    }

    private HBox buildStatusBar() {
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
        if (focusExistingTab("card:" + sig.getCardID())) return;
        Tab tab = new Tab(sig.getCardID(), buildCardDetail(sig));
        tab.setId("card:" + sig.getCardID());
        detailTabs.getTabs().add(tab);
        detailTabs.getSelectionModel().select(tab);
    }

    private void openImportTab(CardImports imp) {
        Path q = imp.getQueryImage();
        String key = "import:" + (q == null ? String.valueOf(imp.hashCode()) : q.toString());
        if (focusExistingTab(key)) return;
        Tab tab = new Tab(q == null ? "Import" : q.getFileName().toString(), buildImportDetail(imp));
        tab.setId(key);
        detailTabs.getTabs().add(tab);
        detailTabs.getSelectionModel().select(tab);
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

    private Node buildCardDetail(CardSignature sig) {
        VBox box = new VBox(10);
        box.setPadding(new Insets(16));

        Label id = new Label("Card ID: " + sig.getCardID());
        id.setStyle("-fx-font-size: 15px; -fx-font-weight: bold;");

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
        return box;
    }

    private Node buildImportDetail(CardImports imp) {
        HBox box = new HBox(10);
        box.setPadding(new Insets(16));

        int size = imp.getRecordSize();

        Label orbLabel  = new Label();
        Label hashLabel = new Label();

        ImageView image1 = new ImageView(); image1.setPreserveRatio(true); image1.setFitHeight(300);
        ImageView image2 = new ImageView(); image2.setPreserveRatio(true); image2.setFitHeight(300);
        HBox images = new HBox(16, image1, image2);

        Path q = imp.getQueryImage();
        if (q != null && Files.exists(q)) image1.setImage(new Image(q.toUri().toString()));

        Label count = new Label();
        Button previous = new Button("Previous");
        Button next = new Button("Next");

        Label cardInfomation = new Label("Card information:");
        Label cardName = new Label();
        Label collectorNum = new Label();
        Label series = new Label();
        Label cardType = new Label();
        Label rarity = new Label();
        Label price = new Label();
        Label description = new Label();
        VBox info = new VBox(10, cardInfomation, cardName, collectorNum, series, cardType, rarity, price, description);
        info.setPadding(new Insets(16));
        info.setSpacing(10);

        int[] pos = {0};

        Runnable render = () -> {
            if (size == 0) {
                orbLabel.setText("ORB match: -");
                hashLabel.setText("pHash match: -");
                count.setText("(0 of 0)");
                previous.setDisable(true);
                next.setDisable(true);
                return;
            }
            int p = pos[0];
            CardSignature orbSigVictim  = imp.getARecordRecord(p, "orb");
            FullCardSignature orbSig = null;
            try {
                orbSig = new FullCardSignature(orbSigVictim, dbPath);
                System.out.println(orbSig.getName());
            } catch (SQLException e) {
                showError(e);
            }
            CardSignature hashSig = imp.getARecordRecord(p, "hash");

            orbLabel.setText ("ORB match: "   + (orbSig  == null ? "-" : orbSig.getCardID()  + "  (" + imp.getARecordScore(p, "orb")  + ")"));
            hashLabel.setText("pHash match: " + (hashSig == null ? "-" : hashSig.getCardID() + "  (" + imp.getARecordScore(p, "hash") + ")"));

            Path orbImg = (orbSig != null) ? orbSig.getImgPath() : null;
            image2.setImage((orbImg != null && Files.exists(orbImg)) ? new Image(orbImg.toUri().toString()) : null);

            count.setText("(" + (p + 1) + " of " + size + ")");
            previous.setDisable(p == 0);
            next.setDisable(p >= size - 1);

            cardName.setText("Name: " + (orbSig == null ? "" : orbSig.getName()));
            collectorNum.setText("Collection Number: "+(orbSig == null ? "" : orbSig.getExpCardNumber()));
            series.setText("Series: "+(orbSig == null ? "" : orbSig.getExpName()));
            cardType.setText("Type: "+(orbSig == null ? "" : orbSig.getCardType()));
            rarity.setText("Rarity: "+(orbSig == null ? "" : orbSig.getRarity()));
            price.setText("Price (Not valid)"+(orbSig == null ? "" : String.valueOf(orbSig.getPrice())));
            description.setText((orbSig == null ? "" : orbSig.getDescription()));


        };

        previous.setOnAction(e -> { if (pos[0] > 0)        { pos[0]--; render.run(); } });
        next.setOnAction(e ->     { if (pos[0] < size - 1) { pos[0]++; render.run(); } });

        render.run();

        VBox imgStack = new VBox(10);
        imgStack.getChildren().addAll(orbLabel, hashLabel, images, new HBox(16, previous, count, next));
        box.getChildren().addAll(imgStack, info);
        return box;
    }

    private ImageView imageAt(String uri) {
        ImageView iv = new ImageView(new Image(uri));
        iv.setPreserveRatio(true);
        iv.setFitHeight(300);
        return iv;
    }

    private Task<?> currentStatusTask;
    private final AtomicBoolean scanRunning = new AtomicBoolean(false);

    private <T> void runTask(Task<T> task, Consumer<T> onSuccess) {
        currentStatusTask = task;
        statusBar.textProperty().bind(task.messageProperty());
        statusProgress.progressProperty().bind(task.progressProperty());
        statusProgress.setVisible(true);
        task.setOnSucceeded(e -> {
            finishTask(task);
            if (onSuccess != null) onSuccess.accept(task.getValue());
        });
        task.setOnFailed(e -> {
            finishTask(task);
            showError(task.getException());
        });
        task.setOnCancelled(e -> finishTask(task));
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

    private final Path dbPath, imagesDir, compareDir, outputDir, cacheDir;
    private final int orbThreads;

    protected InitTask(Path dbPath, Path imagesDir, Path compareDir, Path outputDir, Path cacheDir, int orbThreads) {
        this.dbPath = dbPath;
        this.imagesDir = imagesDir;
        this.compareDir = compareDir;
        this.outputDir = outputDir;
        this.cacheDir = cacheDir;
        this.orbThreads = orbThreads;
    }

    @Override
    protected App.AppContext call() throws Exception{
        String url = "jdbc:sqlite:" + dbPath;
        updateMessage("Connecting to database...");
        int size;
        try (Connection conn = DriverManager.getConnection(url);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) AS n FROM cards")) {
            size = rs.next() ? rs.getInt("n") : 0;
        }
        Main.size = size;

        Path cacheFile = cacheDir.resolve("cache_meta.dat");
        CardIndex cardDB;
        if (Files.isRegularFile(cacheFile)) {
            updateMessage("Loading cache (" + size + " cards)...");
            cardDB = new CardIndex(imagesDir, outputDir, cacheDir, orbThreads);
        } else {
            updateMessage("Computing image data for " + size + " cards...");
            cardDB = new CardIndex(size, url, imagesDir, outputDir, cacheDir,orbThreads);
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
        CardImportsIndex importDB = cardDB.newImportsIndex(compareDir, cacheDir);
        ;
        return new App.AppContext(cardDB, importDB, size);
    }
}

class ConfigEditor {
    private final Config config;

    ConfigEditor(Config config) {
        this.config = config;
    }

    boolean showAndWait(Window owner) {
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
            TextField field = (s.kind() == App.Kind.SECRET) ? new PasswordField() : new TextField();
            field.setText(config.get(s.key()));
            field.setPrefColumnCount(30);
            inputs.put(s.key(), field);

            grid.add(new Label(s.label()), 0, row);
            grid.add(field, 1, row);

            if (s.kind() == App.Kind.DIRECTORY || s.kind() == App.Kind.FILE) {
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
                grid.add(browse, 2, row);
            }
            row++;
        }
        Label header = new Label(sec.name());
        header.setStyle("-fx-font-size: 15px; -fx-font-weight: bold;");
        return new VBox(12, header, grid);
    }
}
