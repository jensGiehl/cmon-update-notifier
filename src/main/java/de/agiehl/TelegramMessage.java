package de.agiehl;

import java.util.List;

public record TelegramMessage(String text, List<Entity> entities) {

    public TelegramMessage {
        entities = List.copyOf(entities);
    }

    public record Entity(String type, int offset, int length) {
    }
}
