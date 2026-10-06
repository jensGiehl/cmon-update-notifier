package de.agiehl;

public interface Notifier {
    void send(String message) throws InterruptedException;
}
