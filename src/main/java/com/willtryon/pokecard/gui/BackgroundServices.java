package com.willtryon.pokecard.gui;

import com.willtryon.pokecard.*;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Files;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.willtryon.pokecard.PokeocrEnv.ocrDefaultCacheDir;
import static com.willtryon.pokecard.TcgdbEnv.tcgdbDefaultCacheDir;

class BackgroundServices implements AutoCloseable{

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1, r -> {
        Thread t = new Thread(r, "pokecard-background-tasks");
        t.setDaemon(true);          // don't keep the JVM alive after the window closes
        return t;
    });

    public static final Logger logger = LogManager.getLogger(BackgroundServices.class);
    private final Config.Settings settings;
    private final App app;

    BackgroundServices(App app, Config.Settings settings) {
        this.app = app;
        this.settings = settings;
        startBackgroundServices();
    }
    void startBackgroundServices() {
        scheduler.scheduleAtFixedRate(() -> {
            try {
                Platform.runLater(() -> {
                    Task<Void> priceTask = new Task<>() {
                        @Override
                        protected Void call() throws Exception {
                            updateTitle("pokecard-price-fetcher");
                            if(!(Files.exists(settings.cacheDir().resolve("tcg.db")))){
                                updateMessage("Resolving python dependencies for price fetching..."); updateProgress(-1, 1);
                                TcgdbEnv env2 = new TcgdbEnv(tcgdbDefaultCacheDir());
                                env2.prepare();
                            }
                            app.syncPrices((msg, frac) -> {
                                updateMessage(msg);
                                updateProgress(frac, 1.0);
                            });
                            return null;
                        }
                    };
                    priceTask.setOnFailed(event -> app.showError(priceTask.getException()));
                    app.runTask(priceTask, "pokecard-price-fetcher",v -> {});
                });
            } catch (Throwable t) {
                logger.error("Price sync scheduling failed", t);
            }
        }, 0, 30, TimeUnit.MINUTES);

        scheduler.scheduleAtFixedRate(() -> {
            try {
                Platform.runLater(() -> {
                    Task<Void> ocrTask = new Task<>() {
                        @Override
                        protected Void call() throws Exception {
                            updateTitle("pokeocr-dependency-fetcher");
                            if(Boolean.parseBoolean(settings.useOcr())){
                                updateMessage("Resolving python dependencies for pokeocr"); updateProgress(-1, 1);
                                PokeocrEnv env = new PokeocrEnv(ocrDefaultCacheDir(), settings);
                                env.prepare();
                            }
                            return null;
                        }
                    };
                    ocrTask.setOnFailed(event -> app.showError(ocrTask.getException()));
                    app.runTask(ocrTask, "pokeocr-dependency-fetcher",v -> {});
                });
            } catch (Throwable t) {
                logger.error("OCR prep failed...", t);
            }
        }, 0, 30, TimeUnit.MINUTES);

        scheduler.scheduleAtFixedRate(() -> {
            try {
                if(app.changed) {
                    Platform.runLater(() -> {
                        Task<Void> saveTask = new Task<>() {
                            @Override
                            protected Void call() {
                                updateTitle("pokecard-auto-save");
                                if (app.changed) {
                                    app.saveSession(app.mainStage, false);
                                }
                                return null;
                            }
                        };
                        saveTask.setOnFailed(event -> app.showError(saveTask.getException()));
                        app.runTask(saveTask, "pokecard-auto-save", v -> {
                        });
                    });
                }
            } catch (Throwable t) {
                logger.error("Save scheduling failed", t);
            }
        }, 0, 1, TimeUnit.MINUTES);

        scheduler.scheduleAtFixedRate(() -> {
            try {
                Platform.runLater(() -> {
                    Task<Set<gitUsage.DataArea>> fetchTask = new Task<>() {
                        @Override
                        protected Set<gitUsage.DataArea> call() {
                            updateTitle("pokecard-auto-update");
                            gitUsage git = new gitUsage((msg, frac) -> {
                                updateMessage(msg);
                                updateProgress(frac, 1);
                            });
                            git.prepareGitProgress();
                            return git.fetch(App.appHome.resolve("pokedata").toFile());
                        }
                    };
                    fetchTask.setOnFailed(event -> app.showError(fetchTask.getException()));
                    app.runTask(fetchTask, "pokecard-auto-update", changed -> {
                        if (changed.contains(gitUsage.DataArea.IMAGES)) {
                            Task<CardIndex> recompute = new Task<>() {
                                @Override
                                protected CardIndex call() throws Exception {
                                    updateTitle("pokecard-db-recompute");
                                    InitTask.calculateDB((msg, frac) -> {
                                        updateMessage(msg);
                                        updateProgress(frac, 1.0);
                                    }, settings);
                                    CountDownLatch latch = new CountDownLatch(1);
                                    AtomicBoolean saveChoice = new AtomicBoolean(false);
                                    Platform.runLater(() -> {
                                        Alert alert = new Alert(
                                                Alert.AlertType.CONFIRMATION,
                                                "Restart the program to apply the changes?",
                                                ButtonType.YES, ButtonType.NO
                                        );
                                        alert.setHeaderText("Restart program and apply updates");
                                        Optional<ButtonType> choice = alert.showAndWait();
                                        saveChoice.set(choice.isPresent() && choice.get() == ButtonType.YES);
                                        latch.countDown();
                                    });
                                    latch.await();
                                    if(saveChoice.get()){
                                        app.restartApplication();
                                    }
                                    return null;
                                }
                            };
                            recompute.setOnFailed(event -> app.showError(recompute.getException()));
                            app.runTask(recompute, "pokecard-db-recompute", newDB -> {
                            });
                        }
                        if (changed.contains(gitUsage.DataArea.DATABASE)) {
                            // data.sqlite changed -> reconnect / reload
                        }
                    });
                });
            } catch (Throwable t) {
                logger.error("Save scheduling failed", t);
            }
        }, 0, 24, TimeUnit.HOURS);

        if(App.DEBUG){
            scheduler.scheduleAtFixedRate(() -> {
                try {
                    Platform.runLater(() -> {
                        Task<Void> saveTask = new Task<>() {
                            @Override
                            protected Void call() {
                                updateTitle("pokecard-changed-test");
                                logger.info("changed = "+app.changed);
                                return null;
                            }
                        };
                        saveTask.setOnFailed(event -> app.showError(saveTask.getException()));
                        app.runTask(saveTask, "pokecard-changed-test",v -> {});
                    });
                } catch (Throwable t) {
                    logger.error("changed-test failed", t);
                }
            }, 0, 1, TimeUnit.SECONDS);
        }
    }



    @Override
    public void close() throws Exception {
        scheduler.shutdown();
    }
}