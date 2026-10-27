package de.agiehl;

import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class MonitorService {

    private static final Logger LOG = LoggerFactory.getLogger(MonitorService.class);
    private final MonitorProperties properties;
    private final PageSource source;
    private final ProjectStatusParser parser;
    private final StateStore store;
    private final ChangeFormatter formatter;
    private final Notifier notifier;
    private final WeeklyStatusService weeklyStatus;

    public MonitorService(MonitorProperties properties, PageSource source, ProjectStatusParser parser,
                          StateStore store, ChangeFormatter formatter, Notifier notifier, WeeklyStatusService weeklyStatus) {
        this.properties = properties;
        this.source = source;
        this.parser = parser;
        this.store = store;
        this.formatter = formatter;
        this.notifier = notifier;
        this.weeklyStatus = weeklyStatus;
    }

    public void check() throws InterruptedException {
        try (var guard = store.lock()) {
            var state = store.load();
            var current = parser.parse(source.fetch(), properties.pageUrl());
            current.forEach(this::logCurrentStatus);
            boolean failed = false;
            for (var section : Section.values()) {
                var previous = state.get(section);
                var snapshot = current.get(section);
                if (snapshot.equals(previous)) {
                    LOG.info("{}: keine Änderung.", section.label());
                    continue;
                }
                if (previous == null) {
                    store.save(section, snapshot);
                    LOG.info("{}: erster Stand gespeichert ({} Projekte).", section.label(), snapshot.projects().size());
                    continue;
                }
                boolean messagesSent = false;
                try {
                    for (var message : formatter.messages(section, previous, snapshot, properties.pageUrl(),
                            properties::isRelevantTitle)) {
                        notifier.send(message);
                        messagesSent = true;
                    }
                } catch (MonitorException exception) {
                    LOG.error("{}: {}", section.label(), exception.getMessage());
                    failed = true;
                    continue;
                }
                store.save(section, snapshot);
                if (messagesSent) {
                    LOG.info("{}: Spieländerungen ausgegeben und gespeichert.", section.label());
                } else {
                    LOG.info("{}: keine Änderungsmeldung; aktueller Seitenstand gespeichert.", section.label());
                }
            }
            weeklyStatus.sendIfDue(current);
            if (failed) {
                throw new MonitorException("Mindestens eine Änderungsmeldung konnte nicht ausgegeben werden;"
                        + " die betroffenen Änderungen werden beim nächsten Cron-Lauf erneut versucht.");
            }
        }
    }

    private void logCurrentStatus(Section section, SectionSnapshot snapshot) {
        LOG.info("{}: aktueller Webseitenstand (Last Updated: {}).", section.label(), snapshot.lastUpdated());
        snapshot.projects().forEach((game, stages) -> {
            if (!properties.isRelevantTitle(game)) {
                return;
            }
            var status = stages.entrySet().stream().map(entry -> entry.getKey() + "=" + entry.getValue())
                    .collect(Collectors.joining("; "));
            LOG.info("Projektstatus [{}] {}: {}", section.label(), game, status);
        });
    }
}
