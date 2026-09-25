package com.willtryon.pokecard.gui;

import com.willtryon.pokecard.*;
import javafx.application.Platform;
import javafx.concurrent.Task;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Files;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static com.willtryon.pokecard.PokeocrEnv.ocrDefaultCacheDir;
import static com.willtryon.pokecard.TcgdbEnv.tcgdbDefaultCacheDir;

final class Services implements AutoCloseable {

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1, r -> {
        Thread t = new Thread(r, "pokecard-service-dispatcher");
        t.setDaemon(true);          // don't keep the JVM alive after the window closes
        return t;
    });

    public static final Logger logger = LogManager.getLogger(Services.class);
    private final Config.Settings settings;
    private final App app;

    Services(App app, Config.Settings settings) {
        this.app = app;
        this.settings = settings;
    }

    void startBackgroundServices() {
        scheduler.scheduleAtFixedRate(() -> {
            try {
                Platform.runLater(() -> {
                    Task<Void> priceTask = new Task<>() {
                        @Override
                        protected Void call() throws Exception {
                            updateTitle("pokecard-price-fetcher");
                            if (!(Files.exists(settings.cacheDir().resolve("tcg.db")))) {
                                updateMessage("Resolving python dependencies for price fetching...");
                                updateProgress(-1, 1);
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
                    app.runTask(priceTask, "pokecard-price-fetcher", r -> {
                        if (app.presult.changed()) app.notices().info("Prices updated", app.presult.summary());
                    });
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
                            if (Boolean.parseBoolean(settings.useOcr())) {
                                updateMessage("Resolving python dependencies for pokeocr");
                                updateProgress(-1, 1);
                                PokeocrEnv env = new PokeocrEnv(ocrDefaultCacheDir(), settings);
                                env.prepare();
                            }
                            return null;
                        }
                    };
                    ocrTask.setOnFailed(event -> app.showError(ocrTask.getException()));
                    app.runTask(ocrTask, "pokeocr-dependency-fetcher", v -> {
                    });
                });
            } catch (Throwable t) {
                logger.error("OCR prep failed...", t);
            }
        }, 0, 30, TimeUnit.MINUTES);

        scheduler.scheduleAtFixedRate(() -> {
            try {
                if (app.changed) {
                    Platform.runLater(() -> {
                        Task<Void> saveTask = new Task<>() {
                            @Override
                            protected Void call() {
                                updateTitle("pokecard-auto-save");
                                app.saveSession(app.mainStage, false);
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
                    Task<Boolean> fetchTask = new Task<>() {
                        @Override
                        protected Boolean call() {
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
                        if (!changed) return;
                        app.notices().post(new NotificationCenter.Notice(Instant.now(), NotificationCenter.Severity.INFO,
                                "Update available", "Update available for database resources", this::updateResources));
                    });
                });
            } catch (Throwable t) {
                logger.error("Auto update failed...", t);
            }
        }, 0, 24, TimeUnit.HOURS);

        if (App.DEBUG) {
            scheduler.scheduleAtFixedRate(() -> {
                try {
                    Platform.runLater(() -> {
                        Task<Void> saveTask = new Task<>() {
                            @Override
                            protected Void call() {
                                updateTitle("pokecard-changed-test");
                                logger.info("changed = " + app.changed);
                                return null;
                            }
                        };
                        saveTask.setOnFailed(event -> app.showError(saveTask.getException()));
                        app.runTask(saveTask, "pokecard-changed-test", v -> {
                        });
                    });
                } catch (Throwable t) {
                    logger.error("changed-test failed", t);
                }
            }, 0, 1, TimeUnit.SECONDS);
        }
    }

    void runOrb() {
        Platform.runLater(() -> {
            Task<Void> orbTask = new Task<>() {
                @Override
                protected Void call() throws SQLException {
                    List<CardImports> temp = app.ctx.importDB().getImports();
                    app.ctx.cardDB().scanImports(app.ctx.importDB(), (msg, frac) -> {
                        updateMessage(msg);
                        updateProgress(frac, 1.0);
                    });
                    app.changed = app.ctx.importDB().equals(temp);
                    Platform.runLater(() -> {
                        updateTitle("pokecard-cv-run");
                        app.refreshImports(app.ctx.importDB());
                    });
                    return null;
                }
            };
            app.runTask(orbTask, "pokecard-cv-run", v -> {
                app.notices().info("CV job finished.", "Finished running fast card matching.");
            });
            orbTask.setOnSucceeded(event -> {
                Task<Void> ocrTask = new Task<>() {
                    @Override
                    protected Void call() {
                        updateTitle("pokeocr-ocr-run");
                        try {
                            app.ctx.importDB().runOcr((msg, frac) -> {
                                updateMessage(msg);
                                updateProgress(frac, 1.0);
                            });
                            app.changed = true;
                        } catch (Exception e) {
                            Platform.runLater(() -> app.showError(e));
                        }
                        Platform.runLater(() -> {
                            app.refreshImports(app.ctx.importDB());
                        });
                        return null;
                    }
                };
                app.runTask(ocrTask, "pokeocr-ocr-run", v -> {
                    app.notices().info("Card import job finished.", "Finished running card matching.");
                });
            });
        });
    }

    void updateResources() {
        Task<CardIndex> recompute = new Task<>() {
            @Override
            protected CardIndex call() throws Exception {
                updateTitle("pokecard-update-runner");
                gitUsage git = new gitUsage((msg, frac) -> {
                    updateMessage(msg);
                    updateProgress(frac, 1);
                });
                git.prepareGitProgress();
                Set<gitUsage.DataArea> result = git.prepareToUpdate(App.appHome.resolve("pokedata").toFile());
                if (result.contains(gitUsage.DataArea.IMAGES) || result.contains(gitUsage.DataArea.DATABASE)) {
                    InitTask.calculateDB((msg, frac) -> {
                        updateMessage(msg);
                        updateProgress(frac, 1.0);
                    }, settings, app.ctx.db().getCatalog());
                }
                return null;
            }
        };
        recompute.setOnFailed(event -> app.showError(recompute.getException()));
        app.runTask(recompute, "pokecard-db-recompute", newDB -> {

            app.notices().post(new NotificationCenter.Notice(
                    Instant.now(), NotificationCenter.Severity.INFO,
                    "Card images updated",
                    "Restart to apply the new database.",() -> app.restartApplication("update-service")
                    ));
        });
    }

    @Override
    public void close() throws Exception {
        scheduler.shutdown();
    }
}