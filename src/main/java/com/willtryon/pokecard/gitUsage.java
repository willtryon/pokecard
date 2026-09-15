package com.willtryon.pokecard;

import com.willtryon.pokecard.gui.App;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.TransportException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.filter.PathFilterGroup;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Optional.*;

public final class gitUsage {

    private static final Logger logger = LogManager.getLogger(gitUsage.class);
    private ProgressMonitor mon;

    private final ScanProgress progress;

    public enum DataArea {
        DATABASE("databases"),
        IMAGES("images");

        private final String prefix;
        DataArea(String prefix) { this.prefix = prefix; }

        boolean matches(String repoPath) {
            return repoPath.equals(prefix) || repoPath.startsWith(prefix + "/");
        }

        static List<String> allPrefixes() {
            return Arrays.stream(values()).map(a -> a.prefix).toList();
        }
    }

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
                logger.error("Error: {}", e.getMessage());
                progress.report("Error: " + e.getMessage(), -1);
                throw new RuntimeException(e);
            }
        }else{
            logger.error("The database is required for this program to operate.");
            throw new IllegalArgumentException("The database is required for this program to operate.");
        }
    }

    public Set<DataArea> fetch(File repositoryDir){
        logger.info("Checking for updates...");
        progress.report("Checking for updates...", -1);
        try{
            if (isUpdateAvailable(repositoryDir)) {
                CountDownLatch latch = new CountDownLatch(1);
                AtomicBoolean saveChoice = new AtomicBoolean(false);
                Platform.runLater(() -> {
                    Alert alert = new Alert(
                            Alert.AlertType.CONFIRMATION,
                            "Resource update found. Do you want to apply the update?",
                            ButtonType.YES, ButtonType.NO
                        );
                    alert.setHeaderText("Update available");
                    Optional<ButtonType> choice = alert.showAndWait();
                    saveChoice.set(choice.isPresent() && choice.get() == ButtonType.YES);
                    latch.countDown();
                    });
                latch.await();
                if(saveChoice.get()) {
                    return updatePokedata(repositoryDir);
                }
            }
        } catch (Exception e) {
            logger.error(e.getMessage());
            progress.report("Error(unknown exception): " + e.getMessage(), -1);
            throw new RuntimeException();
        }
        return Set.of();
    }

    public boolean isUpdateAvailable(File repositoryDir) {
        try (Git git = Git.open(repositoryDir)) {
            Repository repo = git.getRepository();
            ObjectId localHead = repo.resolve("HEAD");   // the single commit a shallow clone holds

            Map<String, Ref> remoteRefs = git.lsRemote()
                    .setHeads(true)
                    .setTags(false)
                    .callAsMap();

            Ref remoteMain = remoteRefs.get("refs/heads/main");
            if (remoteMain == null) {
                logger.warn("Remote has no refs/heads/main; can't determine update state.");
                return false;
            }

            boolean behind = !remoteMain.getObjectId().equals(localHead);
            logger.info(behind ? "Update available on upstream." : "Already up to date.");
            return behind;

        } catch (TransportException e) {
            logger.error("Could not reach upstream: {}", e.getMessage(), e);
            progress.report("Update check failed: " + e.getMessage(), -1);
            return false;
        } catch (IOException | GitAPIException e) {
            logger.error("Update check error: {}", e.getMessage(), e);
            progress.report("Update check error: " + e.getMessage(), -1);
            return false;
        }
    }
    public Set<DataArea> updatePokedata(File repositoryDir){
        logger.info("Updating resources...");
        //progress.report("Updating resources...", -1);
        try (Git git = Git.open(repositoryDir)) {
            Repository repo = git.getRepository();
            ObjectId before = repo.resolve("HEAD");
            git.fetch()
                    .setDepth(1)
                    .setProgressMonitor(mon)
                    .call();

            git.reset()
                    .setMode(ResetCommand.ResetType.HARD)
                    .setRef("refs/remotes/origin/main")
                    .setProgressMonitor(mon)
                    .call();
            ObjectId after = repo.resolve("HEAD");
            if(before == null || before.equals(after)){
                logger.info("Resources already up to date.");
                return Set.of();
            }
            Set<DataArea> changed = changedAreas(git, before, after);
            logger.info("Resources updated, changed areas: {}", changed);
            return changed;

        }catch(TransportException e) {
            logger.error("Not connected to internet!");
            progress.report("Error(no network connection): " + e.getMessage(), -1);
            return Set.of();
        }catch(Exception e){
            logger.error(e.getMessage());
            progress.report("Error(unknown exception): " + e.getMessage(), -1);
            throw new RuntimeException();
        }
    }

    private Set<DataArea> changedAreas(Git git, ObjectId before, ObjectId after) throws Exception {
        Repository repo = git.getRepository();
        List<DiffEntry> diffs;
        try (ObjectReader reader = repo.newObjectReader()) {
            CanonicalTreeParser oldTree = new CanonicalTreeParser();
            oldTree.reset(reader, repo.resolve(before.name() + "^{tree}"));

            CanonicalTreeParser newTree = new CanonicalTreeParser();
            newTree.reset(reader, repo.resolve(after.name() + "^{tree}"));

            diffs = git.diff()
                    .setOldTree(oldTree)
                    .setNewTree(newTree)
                    .setPathFilter(PathFilterGroup.createFromStrings(DataArea.allPrefixes()))
                    .call();
        }

        EnumSet<DataArea> changed = EnumSet.noneOf(DataArea.class);
        for (DiffEntry d : diffs) {
            String path = d.getChangeType() == DiffEntry.ChangeType.DELETE ? d.getOldPath() : d.getNewPath();
            for (DataArea area : DataArea.values()) {
                if (area.matches(path)) changed.add(area);
            }
            if (changed.size() == DataArea.values().length) break;
        }
        return changed;
    }
}