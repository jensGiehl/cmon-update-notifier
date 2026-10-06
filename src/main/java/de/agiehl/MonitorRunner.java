package de.agiehl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.stereotype.Component;

@Component
public class MonitorRunner implements ApplicationRunner, ExitCodeGenerator {

    private static final Logger LOG = LoggerFactory.getLogger(MonitorRunner.class);
    private final MonitorProperties properties;
    private final MonitorService service;
    private int exitCode;

    public MonitorRunner(MonitorProperties properties, MonitorService service) {
        this.properties = properties;
        this.service = service;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        try {
            if (arguments.containsOption("watch") || arguments.containsOption("monitor.watch")
                    || arguments.containsOption("monitor.interval") || arguments.containsOption("monitor.state-file")) {
                throw new MonitorException("Dauerbetrieb und JSON-Statusdateien werden nicht mehr unterstützt."
                        + " Bitte Cron und --monitor.database-path verwenden.");
            }
            properties.validate();
            LOG.info("Änderungsmeldungen werden {} ausgegeben.",
                    properties.telegramConfigured() ? "über Telegram" : "im Log (Telegram nicht vollständig konfiguriert)");
            LOG.info("Einmalige CMON-Prüfung gestartet.");
            service.check();
        } catch (MonitorException exception) {
            exitCode = 1;
            LOG.error("{}", exception.getMessage());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            exitCode = 130;
        }
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }
}
