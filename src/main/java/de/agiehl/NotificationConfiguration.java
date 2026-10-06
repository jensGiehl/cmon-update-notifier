package de.agiehl;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
public class NotificationConfiguration {

    @Bean
    public Clock monitorClock() {
        return Clock.system(ZoneId.of("Europe/Berlin"));
    }

    @Bean
    public Notifier notifier(MonitorProperties properties, JsonMapper mapper) {
        return properties.telegramConfigured() ? new TelegramNotifier(properties, mapper) : new LoggingNotifier();
    }
}
