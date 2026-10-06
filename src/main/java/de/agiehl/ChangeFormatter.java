package de.agiehl;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.TreeSet;
import org.springframework.stereotype.Component;

@Component
public class ChangeFormatter {

    public List<String> messages(Section section, SectionSnapshot previous, SectionSnapshot current, URI pageUrl) {
        if (previous == null) {
            return List.of();
        }
        var changes = new ArrayList<String>();
        var oldProjects = previous.projects();
        var names = new TreeSet<>(oldProjects.keySet());
        names.addAll(current.projects().keySet());
        for (var name : names) {
            var before = oldProjects.get(name);
            var after = current.projects().get(name);
            if (after == null) {
                changes.add(name + " removed from project status");
            } else if (before != null && !before.equals(after)) {
                var stages = new TreeSet<>(before.keySet());
                stages.addAll(after.keySet());
                for (var stage : stages) {
                    if (before.containsKey(stage) && !Objects.equals(before.get(stage), after.get(stage))) {
                        changes.add(describeChange(name, stage, before.get(stage), after.get(stage)));
                    }
                }
            }
        }
        if (changes.isEmpty()) {
            return List.of();
        }
        var header = "CMON " + section.label() + " — Änderungen"
                + "\n" + section.link(pageUrl) + "\n\n";
        return TelegramMessages.split(header, changes);
    }

    private String describeChange(String game, String stage, String before, String after) {
        if (isDelivery(stage)) {
            return game + " expected delivery changed: " + display(before) + " → " + display(after);
        }
        if (after == null) {
            return game + " stage removed: \"" + stage + "\" (previously \"" + before + "\")";
        }
        return switch (after.strip().toLowerCase(Locale.ROOT)) {
            case "completed" -> game + " finished \"" + stage + "\"";
            case "in progress" -> game + " start progress \"" + stage + "\"";
            default -> game + " — \"" + stage + "\": " + display(before) + " → " + after;
        };
    }

    private boolean isDelivery(String stage) {
        return "Delivery".equalsIgnoreCase(stage);
    }

    private String display(String value) {
        return value == null ? "not listed" : value;
    }
}
