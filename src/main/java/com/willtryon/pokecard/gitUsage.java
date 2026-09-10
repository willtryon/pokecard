package com.willtryon.pokecard;

import com.willtryon.pokecard.gui.App;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.MergeResult;
import org.eclipse.jgit.api.errors.TransportException;
import org.eclipse.jgit.lib.ProgressMonitor;

import java.io.File;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

public final class gitUsage {

    private static final Logger logger = LogManager.getLogger(gitUsage.class);
    private ProgressMonitor mon;

    private final ScanProgress progress;

    public gitUsage(ScanProgress progress) {
        this.progress = progress;
    }

    public void prepareGitProgress() {
        mon = new ProgressMonitor() {
            private int totalWork = 0;
            private int completedWork = 0;
            private String currentTaskTitle = "";

            @Override
            public void start(int totalTasks) {}

            @Override
            public void beginTask(String title, int totalWork) {
                this.currentTaskTitle = title;
                this.totalWork = totalWork;
                this.completedWork = 0;

                Platform.runLater(() -> {
                    if (this.totalWork == UNKNOWN) {
                        progress.report(currentTaskTitle, -1);
                    } else {
                        progress.report(currentTaskTitle, this.totalWork);
                    }
                });
            }
            @Override
            public void update(int completed) {
                if (totalWork != UNKNOWN && totalWork > 0) {
                    completedWork += completed;
                    String displayMessage = currentTaskTitle + " (" + completedWork + "/" + totalWork + ")";

                    // 👈 FIX: JGit runs this on a background worker thread, force UI safety via Platform.runLater
                    Platform.runLater(() -> {
                        float percent = ((float) completedWork /totalWork);
                        progress.report(displayMessage, percent);
                    });
                }
            }

            @Override
            public void endTask() {}

            @Override
            public boolean isCancelled() {
                return false;
            }

            @Override
            public void showDuration(boolean enabled) {}
        };
    }

    public void clonePokedata()throws InterruptedException{
        logger.warn("Database not found.");
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean saveChoice =  new AtomicBoolean(false);
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.INFORMATION,
                    "Pokemon database not found. To use this software, the database is required. Clone the database now? (About 3.5GB)",
                    ButtonType.YES, ButtonType.NO);
            alert.setHeaderText("Welcome!");
            Optional<ButtonType> result = alert.showAndWait();
            saveChoice.set(result.isPresent() && result.get() == ButtonType.YES);
            latch.countDown();
        });
        latch.await();
        if (saveChoice.get()) {
            logger.info("Cloning database from willtryon/pokedata...");
            try {
                Git.cloneRepository().setURI("https://github.com/willtryon/pokedata").setDirectory(App.appHome.resolve("pokedata").toFile()).setDepth(1).setProgressMonitor(mon).call();
            }catch(TransportException e){
                logger.error("Not Connected to internet! The database is required for this program to operate.");
                progress.report("Error: " + e.getMessage(), -1);
                throw new RuntimeException();
            }catch(Exception e){
                logger.error("Error: " + e.getMessage());
                progress.report("Error: " + e.getMessage(), -1);
                throw new RuntimeException(e);
            }
        }else{
            logger.error("The database is required for this program to operate.");
            throw new IllegalArgumentException("The database is required for this program to operate.");
        }
    }
    public void updatePokedata(File repositoryDir) {
        logger.info("Checking for updates...");
        try (Git git = Git.open(repositoryDir)) {
            git.fetch()
                    .setDepth(1)
                    .setProgressMonitor(mon)
                    .call();

            MergeResult result = git.merge()
                    .include(git.getRepository().findRef("refs/remotes/origin/main"))
                    .call();

            if (!(result.getMergeStatus().isSuccessful())){
                logger.error("Merge conflict or issue: {}", result.getMergeStatus());
                progress.report("Merge conflict or issue: " + result.getMergeStatus(), -1);
            }

        }catch(TransportException e) {
            logger.error("Not connected to internet!");
            progress.report("Error(no network connection): " + e.getMessage(), -1);
        }catch(Exception e){
            logger.error(e.getMessage());
            progress.report("Error(unknown exception): " + e.getMessage(), -1);
            throw new RuntimeException();
        }
    }
}