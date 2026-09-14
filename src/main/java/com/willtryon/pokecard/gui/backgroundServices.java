package com.willtryon.pokecard.gui;

import com.willtryon.pokecard.Config;
import com.willtryon.pokecard.PokeocrEnv;
import com.willtryon.pokecard.TcgdbEnv;
import com.willtryon.pokecard.gitUsage;
import javafx.application.Platform;
import javafx.concurrent.Task;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Files;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static com.willtryon.pokecard.PokeocrEnv.ocrDefaultCacheDir;
import static com.willtryon.pokecard.TcgdbEnv.tcgdbDefaultCacheDir;

class backgroundServices implements AutoCloseable{

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1, r -> {
        Thread t = new Thread(r, "pokecard-background-tasks");
        t.setDaemon(true);          // don't keep the JVM alive after the window closes
        return t;
    });

    public static final Logger logger = LogManager.getLogger(backgroundServices.class);
    private final Config.Settings settings;
    private final App app;

    backgroundServices(App app, Config.Settings settings) {
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

                            if(!(Files.exists(settings.cacheDir().resolve("tcg.db")))){
                                logger.info("hit");
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
                            if(Boolean.parseBoolean(settings.useOcr())){
                                logger.info("hit");
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
                Platform.runLater(() -> {
                    Task<Void> saveTask = new Task<>() {
                        @Override
                        protected Void call() {
                            updateTitle("pokecard-auto-save");
                            logger.debug("I work!");
                            if (app.saved) app.saveSession(app.mainStage);
                            return null;
                        }
                    };
                    saveTask.setOnFailed(event -> app.showError(saveTask.getException()));
                    app.runTask(saveTask, "pokecard-auto-save",v -> {});
                });
            } catch (Throwable t) {
                logger.error("Save scheduling failed", t);
            }
        }, 10, 1, TimeUnit.MINUTES);

        scheduler.scheduleAtFixedRate(() -> {
            try {
                Platform.runLater(() -> {
                    Task<Void> fetchTask = new Task<>() {
                        @Override
                        protected Void call() {
                            updateTitle("pokecard-auto-update");
                            gitUsage git = new gitUsage((msg, frac) -> {
                                updateMessage(msg);
                                updateProgress(frac, 1);
                            });
                            git.updatePokedata(App.appHome.resolve("pokedata").toFile());
                            return null;
                        }
                    };
                    fetchTask.setOnFailed(event -> app.showError(fetchTask.getException()));
                    app.runTask(fetchTask, "pokecard-auto-update",v -> {});
                });
            } catch (Throwable t) {
                logger.error("Update fetching failed.", t);
            }
        }, 0, 30, TimeUnit.MINUTES);
    }

    @Override
    public void close() throws Exception {
        scheduler.shutdown();
    }
}