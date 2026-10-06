package de.agiehl;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

public record SectionSnapshot(String lastUpdated, Map<String, Map<String, String>> projects) {

    public SectionSnapshot {
        if (lastUpdated == null || projects == null || projects.isEmpty()) {
            throw new MonitorException("Ungültiger oder leerer Projektstand.");
        }
        var copy = new TreeMap<String, Map<String, String>>();
        projects.forEach((name, stages) -> {
            if (name == null || name.isBlank() || stages == null || stages.isEmpty()
                    || stages.entrySet().stream().anyMatch(entry -> entry.getKey() == null
                    || entry.getKey().isBlank() || entry.getValue() == null || entry.getValue().isBlank())) {
                throw new MonitorException("Unvollständiger Projektstand.");
            }
            copy.put(name, Collections.unmodifiableMap(new TreeMap<>(stages)));
        });
        projects = Collections.unmodifiableMap(copy);
    }
}
