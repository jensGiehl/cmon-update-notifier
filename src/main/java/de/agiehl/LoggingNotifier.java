package de.agiehl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LoggingNotifier implements Notifier {

    private static final Logger LOG = LoggerFactory.getLogger(LoggingNotifier.class);

    @Override
    public void send(String message) {
        LOG.info("Änderungsmeldung:\n{}", message);
    }
}
