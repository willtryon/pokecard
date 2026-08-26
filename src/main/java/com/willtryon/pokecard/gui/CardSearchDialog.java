package com.willtryon.pokecard.gui;

import com.willtryon.pokecard.CardImports;
import com.willtryon.pokecard.CardSearchRepo;
import com.willtryon.pokecard.CardSearchRepo.CardHit;
import com.willtryon.pokecard.CardSignature;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.stage.Window;
import javafx.util.Duration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class CardSearchDialog {

    private static final double THUMB_HEIGHT = 90;
    private static final double ROW_HEIGHT   = 96;
    private static final int    CACHE_SIZE   = 300;

    private final CardSearchRepo repo;
    private final ObservableList<CardHit> rows = FXCollections.observableArrayList();

    /*this is required so the images in the dialog window remain in the jvm heap, otherwise whenever the
    fx application thread updates the ui the image will be retrieved from the disk again.*/
    private final Map<String, Image> thumbCache =
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Image> eldest) {
                    return size() > CACHE_SIZE;
                }
            };

   //similar idea to gc generations
    private long generation = 0;

    public CardSearchDialog(CardSearchRepo repo) {
        this.repo = repo;
    }

    public Optional<CardImports.Match> showAndWait(Window owner) {
        Dialog<CardImports.Match> dlg = new Dialog<>();
        if (owner != null) dlg.initOwner(owner);
        dlg.setTitle("Find a card");
        dlg.setHeaderText("Search the card database, then pick the correct card.");
        dlg.setResizable(true);
        dlg.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        ((Button) dlg.getDialogPane().lookupButton(ButtonType.OK)).setText("OK");

        //search bar
        TextField query = new TextField();
        query.setPromptText(repo.isFtsAvailable() //so the application doesn't kill itself.
                ? "e.g. charizard base"
                : "e.g. charizard");
        HBox.setHgrow(query, Priority.ALWAYS);

        Button searchButton = new Button("Search");
        searchButton.setDefaultButton(false);

        ProgressIndicator busy = new ProgressIndicator();
        busy.setPrefSize(18, 18);
        busy.setVisible(false);

        HBox bar = new HBox(8, query, searchButton, busy);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(0, 0, 8, 0));

        //winners table
        TableView<CardHit> winners = buildTable();
        Label status = new Label("Type a card name to begin.");
        status.setPadding(new Insets(6, 0, 0, 0));

        BorderPane root = new BorderPane();
        root.setTop(bar);
        root.setCenter(winners);
        root.setBottom(status);
        root.setPadding(new Insets(12));
        root.setPrefSize(760, 540);
        dlg.getDialogPane().setContent(root);

        //window behavior
        Node ok = dlg.getDialogPane().lookupButton(ButtonType.OK);
        ok.disableProperty().bind(
                winners.getSelectionModel().selectedItemProperty().isNull());

        // debounce is used when a pause in typing is detected, so the application doesn't perform a query every frame.
        PauseTransition debounce = new PauseTransition(Duration.millis(250));
        debounce.setOnFinished(e -> runSearch(query.getText(), status, busy));
        query.textProperty().addListener((o, a, b) -> debounce.playFromStart());

        Runnable searchNow = () -> {
            debounce.stop();
            runSearch(query.getText(), status, busy);
        };
        searchButton.setOnAction(e -> searchNow.run());
        query.setOnAction(e -> searchNow.run());

        // allows selection of a winner via doubleclicking.
        winners.setRowFactory(tv -> {
            TableRow<CardHit> row = new TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !row.isEmpty()) ((Button) ok).fire();
            });
            return row;
        });

        dlg.setResultConverter(bt -> {
            if (bt != ButtonType.OK) return null;
            CardHit hit = winners.getSelectionModel().getSelectedItem();
            if (hit == null) return null;

            CardSignature sig = repo.signature(hit.cardId());
            String imgPath = sig != null ? sig.getStringImgPath()
                    : (hit.img() == null ? "" : hit.img().toString());

            // winner = -1 marks this as a manual pick rather than an algorithm score.
            return new CardImports.Match(hit.cardId(), imgPath, -1.0);
        });

        Platform.runLater(query::requestFocus);
        return Optional.ofNullable(dlg.showAndWait().orElse(null));
    }

    //table

    private TableView<CardHit> buildTable() {
        TableView<CardHit> table = new TableView<>(rows);
        table.setPlaceholder(new Label("No matching cards."));
        table.getSelectionModel().setSelectionMode(SelectionMode.SINGLE);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        //setting a fixed size is borderline required for performance reasons.
        table.setFixedCellSize(ROW_HEIGHT);

        TableColumn<CardHit, CardHit> art = new TableColumn<>("");
        art.setCellValueFactory(cd -> new SimpleObjectProperty<>(cd.getValue()));
        art.setCellFactory(c -> thumbCell());
        art.setPrefWidth(78);
        art.setSortable(false);
        art.setReorderable(false);

        TableColumn<CardHit, String> name = new TableColumn<>("Name");
        name.setCellValueFactory(cd -> new SimpleStringProperty(cd.getValue().name()));
        name.setPrefWidth(220);

        TableColumn<CardHit, String> set = new TableColumn<>("Set");
        set.setCellValueFactory(cd -> new SimpleStringProperty(cd.getValue().setLabel()));
        set.setPrefWidth(220);

        TableColumn<CardHit, String> rarity = new TableColumn<>("Rarity");
        rarity.setCellValueFactory(cd -> new SimpleStringProperty(cd.getValue().rarity()));
        rarity.setPrefWidth(120);

        TableColumn<CardHit, String> id = new TableColumn<>("Card ID");
        id.setCellValueFactory(cd -> new SimpleStringProperty(cd.getValue().cardId()));
        id.setPrefWidth(140);

        table.getColumns().setAll(List.of(art, name, set, rarity, id));
        return table;
    }

    //this is the method that loads the image into memory.
    private TableCell<CardHit, CardHit> thumbCell() {
        return new TableCell<>() {
            private final ImageView view = new ImageView();
            {
                view.setFitHeight(THUMB_HEIGHT);
                view.setPreserveRatio(true);
                view.setSmooth(true);
                setGraphic(view);
                setAlignment(Pos.CENTER);
            }

            @Override
            protected void updateItem(CardHit hit, boolean empty) {
                super.updateItem(hit, empty);
                if (empty || hit == null || hit.img() == null || !Files.exists(hit.img())) {
                    view.setImage(null);      // required, or recycled cells show ghosts
                    return;
                }
                view.setImage(thumb(hit.img()));
            }
        };
    }

    private Image thumb(Path p) {
        return thumbCache.computeIfAbsent(p.toString(), key -> new Image(
                p.toUri().toString(),           //sry about magic numbers...
                0,                  // requestedWidth: 0 = derive from height
                THUMB_HEIGHT,       // decode at thumbnail size, not full res
                true,               // preserveRatio
                true,               // smooth
                true));             // backgroundLoading -- returns immediately
    }

    //querying

    /** Always called on the FX thread; always does its work off it. */
    private void runSearch(String text, Label status, Node busy) {
        String q = text == null ? "" : text.trim();
        final long mine = ++generation;

        if (q.isEmpty()) {
            rows.clear();
            status.setText("Type a card name to begin.");
            busy.setVisible(false);
            return;
        }

        Task<List<CardHit>> task = new Task<>() {
            @Override
            protected List<CardHit> call() throws Exception {
                return repo.search(q);
            }
        };

        task.setOnSucceeded(e -> {
            if (mine != generation) return;      // a newer search already started
            List<CardHit> hits = task.getValue();
            rows.setAll(hits);
            status.setText(hits.isEmpty()
                    ? "No matches for \u201C" + q + "\u201D."
                    : hits.size() + (hits.size() == 1 ? " match" : " matches"));
            busy.setVisible(false);
        });

        task.setOnFailed(e -> {
            if (mine != generation) return;
            busy.setVisible(false);
            Throwable ex = task.getException();
            ex.printStackTrace();
            status.setText("Search failed: " + ex.getMessage());
        });

        busy.setVisible(true);
        Thread t = new Thread(task, "card-search");
        t.setDaemon(true);
        t.start();
    }
}