package de.agiehl;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class WeeklyStatusFormatter {

    public List<TelegramMessage> messages(Map<Section, SectionSnapshot> current, URI pageUrl) {
        var header = "CMON Update ist weiterhin aktiv — Sonntagsstatus\n" + pageUrl + "\n\n";
        var body = new StringBuilder();
        var entities = new ArrayList<TelegramMessage.Entity>();
        for (var section : Section.values()) {
            current.get(section).projects().forEach((name, stages) -> {
                var progress = stages.entrySet().stream()
                        .filter(entry -> !"Delivery".equalsIgnoreCase(entry.getKey()))
                        .filter(entry -> "In progress".equalsIgnoreCase(entry.getValue().strip()))
                        .map(Map.Entry::getKey)
                        .collect(Collectors.joining(", "));
                if (!progress.isEmpty()) {
                    entities.add(new TelegramMessage.Entity("bold", body.length(), name.length()));
                    body.append(name).append('\n').append(progress).append("\n\n");
                }
            });
        }
        if (body.isEmpty()) {
            body.append("Aktuell sind keine Projekte in progress.");
        }
        return TelegramMessages.split(header, new TelegramMessage(body.toString().stripTrailing(), entities));
    }
}
