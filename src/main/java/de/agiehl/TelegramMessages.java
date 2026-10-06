package de.agiehl;

import java.util.ArrayList;
import java.util.List;

final class TelegramMessages {

    private static final int MESSAGE_LIMIT = 4000;

    private TelegramMessages() {
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
