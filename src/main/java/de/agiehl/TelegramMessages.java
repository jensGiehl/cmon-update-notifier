package de.agiehl;

import java.util.ArrayList;
import java.util.List;

final class TelegramMessages {

    private static final int MESSAGE_LIMIT = 4000;

    private TelegramMessages() {
    }

    static List<TelegramMessage> split(String header, TelegramMessage body) {
        if (header.length() > MESSAGE_LIMIT / 2) {
            throw new MonitorException("CMON_PAGE_URL ist zu lang für eine Telegram-Nachricht.");
        }
        var messages = new ArrayList<TelegramMessage>();
        int capacity = MESSAGE_LIMIT - header.length();
        int start = 0;
        while (start < body.text().length()) {
            int end = Math.min(start + capacity, body.text().length());
            if (end < body.text().length()) {
                int paragraphEnd = body.text().lastIndexOf("\n\n", end - 2);
                if (paragraphEnd >= start) {
                    end = paragraphEnd + 2;
                } else if (Character.isHighSurrogate(body.text().charAt(end - 1))) {
                    end--;
                }
            }
            var entities = new ArrayList<TelegramMessage.Entity>();
            for (var entity : body.entities()) {
                int entityStart = Math.max(start, entity.offset());
                int entityEnd = Math.min(end, entity.offset() + entity.length());
                if (entityStart < entityEnd) {
                    entities.add(new TelegramMessage.Entity(entity.type(), header.length() + entityStart - start,
                            entityEnd - entityStart));
                }
            }
            messages.add(new TelegramMessage(header + body.text().substring(start, end), entities));
            start = end;
        }
        return List.copyOf(messages);
    }

    static List<String> split(String header, List<String> lines) {
        if (header.length() > MESSAGE_LIMIT / 2) {
            throw new MonitorException("CMON_PAGE_URL ist zu lang für eine Telegram-Nachricht.");
        }
        var messages = new ArrayList<String>();
        var buffer = new StringBuilder(header);
        for (var line : lines) {
            var remaining = line + "\n";
            while (!remaining.isEmpty()) {
                int available = MESSAGE_LIMIT - buffer.length();
                if (available == 0) {
                    messages.add(buffer.toString().stripTrailing());
                    buffer = new StringBuilder(header);
                    available = MESSAGE_LIMIT - buffer.length();
                }
                int end = Math.min(available, remaining.length());
                if (end < remaining.length() && Character.isHighSurrogate(remaining.charAt(end - 1))) {
                    end--;
                }
                if (end == 0) {
                    messages.add(buffer.toString().stripTrailing());
                    buffer = new StringBuilder(header);
                    continue;
                }
                buffer.append(remaining, 0, end);
                remaining = remaining.substring(end);
            }
        }
        if (buffer.length() > header.length()) {
            messages.add(buffer.toString().stripTrailing());
        }
        return List.copyOf(messages);
    }
}
