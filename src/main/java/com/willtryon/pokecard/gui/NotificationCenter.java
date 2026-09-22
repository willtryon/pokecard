package com.willtryon.pokecard.gui;

import javafx.application.Platform;
import javafx.beans.property.ReadOnlyIntegerProperty;
import javafx.beans.property.ReadOnlyIntegerWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Pos;
import javafx.stage.Window;
import javafx.util.Duration;
import org.controlsfx.control.Notifications;

import java.time.Instant;

public final class NotificationCenter{

    public enum Severity{
        INFO,
        WARNING,
        ERROR
    }

    public record Notice(Instant at, Severity severity, String title, String text, Runnable onClick){
        public Notice(Severity s, String title, String text){
            this(Instant.now(), s, title, text, null);
        }
    }

    private static final int MAX_HISTORY = 200;

    private final ObservableList<Notice> history = FXCollections.observableArrayList();
    private final ReadOnlyIntegerWrapper unread = new ReadOnlyIntegerWrapper(this, "unread", 0);
    private Window owner;

    void setOwner(Window owner){
        this.owner = owner;
    }

    public ObservableList<Notice> getHistory(){
        return history;
    }

    public ReadOnlyIntegerProperty unreadProperty(){
        return unread.getReadOnlyProperty();
    }

    public void markAllRead() { unread.set(0); }

    public void post(Notice n){
        if(!Platform.isFxApplicationThread()){
            Platform.runLater(()->post(n));
            return;
        }
        history.addFirst(n);
        if(history.size() > MAX_HISTORY){
            history.remove(MAX_HISTORY, history.size()-1);
        }
        unread.set(unread.get()+1);
        toast(n);
    }

    public void info(String title, String text){
        post(new Notice(Severity.INFO, title, text));
    }

    public void warning(String title, String text){
        post(new Notice(Severity.WARNING, title, text));
    }

    public void error(String title, Throwable e){
        post(new Notice(Severity.ERROR, title, String.valueOf(e.getMessage())));
    }

    private void toast(Notice n){
        Notifications b = Notifications.create()
                .title(n.title)
                .text(n.text)
                .darkStyle()
                .owner(owner)
                .position(Pos.BOTTOM_RIGHT)
                .hideAfter(n.severity() == Severity.ERROR
                ? Duration.seconds(15) : Duration.seconds(5))
                .threshold(3, Notifications.create()
                        .title("Several new notifications")
                        .position(Pos.BOTTOM_RIGHT));
        if(owner != null){
            b = b.owner(owner);
        }
        if(n.onClick() != null){
            Runnable r = n.onClick();
            b = b.onAction(a -> r.run());
        }

        switch(n.severity){
            case INFO -> b.showInformation();
            case WARNING -> b.showWarning();
            case ERROR -> b.showError();
        }
    }
}