package de.agiehl;

import java.net.URI;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

@Component
public class ProjectStatusParser {

    public Map<Section, SectionSnapshot> parse(String html, URI source) {
        var document = Jsoup.parse(html, source.toString());
        var lastUpdated = document.select("p").stream().map(Element::text)
                .filter(text -> text.startsWith("Last Updated:"))
                .map(text -> text.substring("Last Updated:".length()).trim())
                .findFirst().orElse("");
        var snapshots = new EnumMap<Section, SectionSnapshot>(Section.class);
        for (var section : Section.values()) {
            var container = document.getElementById(section.id());
            if (container == null) {
                throw invalid(section, "Abschnitt fehlt (möglicherweise Cloudflare oder geändertes HTML)");
            }
            var names = container.select(".cps-projects__name");
            var rows = container.select(".cps-data__table > tbody > tr");
            var headers = container.select(".cps-data__table > thead > tr > th").stream()
                    .map(this::semanticText).toList();
            if (names.isEmpty() || rows.size() != names.size() || headers.isEmpty()) {
                throw invalid(section, "Projektliste und Statustabelle passen nicht zusammen");
            }
            var projects = new LinkedHashMap<String, Map<String, String>>();
            for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
                var name = names.get(rowIndex).text();
                var cells = rows.get(rowIndex).children().stream()
                        .filter(element -> element.tagName().equals("td")).toList();
                if (cells.size() != headers.size()) {
                    throw invalid(section, "Anzahl der Statusspalten stimmt nicht");
                }
                var stages = new LinkedHashMap<String, String>();
                for (int columnIndex = 0; columnIndex < cells.size(); columnIndex++) {
                    var label = headers.get(columnIndex);
                    var status = semanticText(cells.get(columnIndex));
                    if (label.isBlank() || status.isBlank() || stages.put(label, status) != null) {
                        throw invalid(section, "Statusspalte ist leer oder doppelt");
                    }
                }
                if (name.isBlank() || projects.put(name, stages) != null) {
                    throw invalid(section, "Projektname ist leer oder doppelt");
                }
            }
            snapshots.put(section, new SectionSnapshot(lastUpdated, projects));
        }
        return snapshots;
    }

    private String semanticText(Element element) {
        var text = element.text();
        if (!text.isBlank()) {
            return text;
        }
        var image = element.selectFirst("img[alt]");
        if (image != null && !image.attr("alt").isBlank()) {
            return image.attr("alt").trim();
        }
        var tooltip = element.hasAttr("data-bs-title") ? element : element.selectFirst("[data-bs-title]");
        return tooltip == null ? "" : tooltip.attr("data-bs-title").trim();
    }

    private MonitorException invalid(Section section, String reason) {
        return new MonitorException("CMON " + section.label() + ": " + reason + ". Stand bleibt unverändert.");
    }
}
