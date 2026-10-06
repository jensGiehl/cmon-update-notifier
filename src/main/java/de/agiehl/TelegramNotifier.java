package de.agiehl;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

public class TelegramNotifier implements Notifier {

    private final MonitorProperties properties;
    private final JsonMapper jsonMapper;

    public TelegramNotifier(MonitorProperties properties, JsonMapper jsonMapper) {
        this.properties = properties;
        this.jsonMapper = jsonMapper;
    }

    @Override
    public void send(String message) throws InterruptedException {
        var telegram = properties.telegram();
        var base = telegram.apiUrl().toString().replaceAll("/+$", "");
        var endpoint = URI.create(base + "/bot" + telegram.botToken() + "/sendMessage");
        var body = jsonMapper.writeValueAsString(Map.of("chat_id", telegram.chatId(), "text", message,
                "link_preview_options", Map.of("is_disabled", true)));
        try (var client = HttpClient.newBuilder().connectTimeout(properties.requestTimeout()).build()) {
            var request = HttpRequest.newBuilder(endpoint).timeout(properties.requestTimeout())
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                throw new MonitorException("Telegram-Versand fehlgeschlagen: HTTP " + response.statusCode()
                        + ". Stand bleibt unverändert.");
            }
            var result = jsonMapper.readTree(response.body());
            if (result == null || !result.path("ok").isBoolean() || !result.path("ok").asBoolean(false)) {
                throw new MonitorException("Telegram hat die Nachricht nicht bestätigt. Stand bleibt unverändert.");
            }
        } catch (IOException | tools.jackson.core.JacksonException exception) {
            throw new MonitorException("Telegram-Versand fehlgeschlagen: Netzwerkfehler, Zeitüberschreitung"
                    + " oder ungültige Antwort. Stand bleibt unverändert.");
        }
    }
}
