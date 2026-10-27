package de.agiehl;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class WeeklyStatusService {

    private static final Logger LOG = LoggerFactory.getLogger(WeeklyStatusService.class);
    private final MonitorProperties properties;
    private final StateStore store;
    private final WeeklyStatusFormatter formatter;
    private final Notifier notifier;
    private final Clock clock;

    public WeeklyStatusService(MonitorProperties properties, StateStore store, WeeklyStatusFormatter formatter,
                               Notifier notifier, Clock clock) {
        this.properties = properties;
        this.store = store;
        this.formatter = formatter;
        this.notifier = notifier;
        this.clock = clock;
    }

    public void sendIfDue(Map<Section, SectionSnapshot> current) throws InterruptedException {
        var now = ZonedDateTime.now(clock);
        if (!properties.telegramConfigured() || now.getDayOfWeek() != DayOfWeek.SUNDAY
                || now.toLocalTime().isBefore(LocalTime.of(10, 0))
                || store.weeklyStatusSent(now.toLocalDate())) {
            return;
        }
        for (var message : formatter.messages(current, properties.pageUrl(), properties::isRelevantTitle)) {
            notifier.send(message);
        }
        store.markWeeklyStatusSent(now.toLocalDate());
        LOG.info("Sonntägliche Aktivitätsmeldung für {} versendet und gespeichert.", now.toLocalDate());
    }
}
