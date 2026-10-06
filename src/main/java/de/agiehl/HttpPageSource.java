package de.agiehl;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;

@Component
public class HttpPageSource implements PageSource {

    private final MonitorProperties properties;

    public HttpPageSource(MonitorProperties properties) {
        this.properties = properties;
    }

    @Override
    public String fetch() throws InterruptedException {
        try (var client = HttpClient.newBuilder().connectTimeout(properties.requestTimeout())
                .followRedirects(HttpClient.Redirect.NORMAL).build()) {
            var pageUrl = properties.pageUrl().toString().split("#", 2)[0];
            var request = HttpRequest.newBuilder(URI.create(pageUrl))
                    .timeout(properties.requestTimeout())
                    .header("User-Agent", "CMON-Update/1.0 (project status monitor)")
                    .header("Accept", "text/html")
                    .GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                throw new MonitorException("CMON-Abruf fehlgeschlagen: HTTP " + response.statusCode()
                        + (response.statusCode() == 403 ? " (Zugriff blockiert, möglicherweise Cloudflare)." : "."));
            }
            return response.body();
        } catch (IOException exception) {
            throw new MonitorException("CMON-Abruf fehlgeschlagen: Netzwerkfehler oder Zeitüberschreitung.");
        }
    }
}
