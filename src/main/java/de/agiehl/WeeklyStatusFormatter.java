package de.agiehl;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class WeeklyStatusFormatter {

    public List<String> messages(Map<Section, SectionSnapshot> current, URI pageUrl) {
        var header = "CMON Update ist weiterhin aktiv — Sonntagsstatus\n" + pageUrl + "\n\n";
        var projects = new ArrayList<String>();
        for (var section : Section.values()) {
            current.get(section).projects().forEach((name, stages) -> {
                var progress = stages.entrySet().stream()
                        .filter(entry -> !"Delivery".equalsIgnoreCase(entry.getKey()))
                        .filter(entry -> "In progress".equalsIgnoreCase(entry.getValue().strip()))
                        .map(Map.Entry::getKey)
                        .collect(Collectors.joining(", "));
                if (!progress.isEmpty()) {
                    projects.add("[" + section.label() + "] " + name + ": In progress — " + progress);
                }
            });
        }
        if (projects.isEmpty()) {
            projects.add("Aktuell sind keine Projekte in progress.");
        }
        return TelegramMessages.split(header, projects);
    }
}
