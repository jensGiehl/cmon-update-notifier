package de.agiehl;

public interface Notifier {
    void send(String message) throws InterruptedException;

    default void send(TelegramMessage message) throws InterruptedException {
        send(message.text());
    }
}
