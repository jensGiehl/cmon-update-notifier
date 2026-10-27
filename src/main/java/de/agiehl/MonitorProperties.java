package de.agiehl;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("monitor")
public record MonitorProperties(URI pageUrl, Path databasePath,
                                Duration requestTimeout, Telegram telegram, List<String> interestedTitles) {

    public MonitorProperties {
        interestedTitles = interestedTitles == null ? List.of() : interestedTitles.stream()
                .filter(Objects::nonNull)
                .map(String::strip)
                .filter(title -> !title.isEmpty())
                .distinct()
                .toList();
    }

    public boolean isRelevantTitle(String title) {
        return interestedTitles.isEmpty()
                || interestedTitles.stream().anyMatch(selected -> selected.equalsIgnoreCase(title.strip()));
    }

    public void validate() {
        validateHttpUrl(pageUrl, "CMON_PAGE_URL");
        validateDatabasePath();
        if (requestTimeout == null || requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new MonitorException("CMON_REQUEST_TIMEOUT muss positiv sein.");
        }
        if (!telegramConfigured()) {
            return;
        }
        if (!telegram.botToken().matches("[0-9]+:[A-Za-z0-9_-]+")) {
            throw new MonitorException("TELEGRAM_BOT_TOKEN hat kein gültiges Format.");
        }
        validateHttpUrl(telegram.apiUrl(), "TELEGRAM_API_URL");
    }

    public boolean telegramConfigured() {
        return telegram != null && telegram.botToken() != null && !telegram.botToken().isBlank()
                && telegram.chatId() != null && !telegram.chatId().isBlank();
    }

    public void validateDatabasePath() {
        if (databasePath == null || databasePath.getFileName() == null
                || databasePath.toString().contains(";")
                || databasePath.toString().endsWith(".mv.db")) {
            throw new MonitorException("CMON_DATABASE_PATH muss einen H2-Dateipfad ohne .mv.db-Endung"
                    + " und ohne Semikolon enthalten.");
        }
    }

    private static void validateHttpUrl(URI uri, String name) {
        if (uri == null || uri.getHost() == null || uri.getUserInfo() != null
                || !("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))) {
            throw new MonitorException(name + " muss eine HTTP(S)-URL ohne Zugangsdaten sein.");
        }
    }

    public record Telegram(String botToken, String chatId, URI apiUrl) {
        @Override
        public String toString() {
            return "Telegram[botToken=<redacted>, chatId=<redacted>, apiUrl=" + apiUrl + "]";
        }
    }
}
