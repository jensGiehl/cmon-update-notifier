package de.agiehl;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.jsoup.Jsoup;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(OutputCaptureExtension.class)
class HttpIntegrationTest {

    @TempDir
    Path directory;
    private HttpServer server;
    private URI base;
    private final AtomicInteger telegramStatus = new AtomicInteger(200);
    private final AtomicInteger pageStatus = new AtomicInteger(200);
    private final List<String> requests = new ArrayList<>();
    private String telegramResponse = "{\"ok\":true,\"result\":{\"message_id\":1}}";
    private String html;

    @BeforeEach
    void startServer() throws Exception {
        html = TestFixtures.html();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
        server.createContext("/project-status/", exchange -> respond(exchange, pageStatus.get(), html));
        server.createContext("/bot123:test-token/sendMessage", exchange -> {
            requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, telegramStatus.get(), telegramResponse);
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void bootCliBindsConfigurationRunsOnceAndPersistsAcrossInvocations(CapturedOutput output) {
        assertThat(runApplication()).isZero();
        assertThat(requests).isEmpty();
        assertThat(directory.resolve("cmon-state.mv.db")).exists();
        assertThat(output.getAll()).contains("Projektstatus [Crowdfunding] Mordred:",
                "Delivery=2026 Q4", "Shipping=Not started", "Projektstatus [Pre-order] Pre-order Game:")
                .doesNotContain("Änderungsmeldung:");
        html = html.replace("2027 Q1", "2027 Q2");
        assertThat(runApplication()).isZero();
        assertThat(requests).hasSize(1);
        var request = JsonMapper.builder().build().readTree(requests.getFirst());
        assertThat(request.path("chat_id").asString()).isEqualTo("123");
        assertThat(request.path("text").asString())
                .contains("#pre-order", "Pre-order Game expected delivery changed: 2027 Q1 → 2027 Q2");
        assertThat(runApplication()).isZero();
        assertThat(requests).hasSize(1);

        html = html.replace("2 Oct 2026.", "3 Oct 2026.");
        assertThat(runApplication()).isZero();
        assertThat(requests).hasSize(1);

        var document = Jsoup.parse(html);
        document.selectFirst("#crowdfunding tbody tr td:nth-child(2) img").attr("alt", "In progress");
        html = document.outerHtml();
        assertThat(runApplication()).isZero();
        assertThat(requests).hasSize(2);
        var progressMessage = JsonMapper.builder().build().readTree(requests.getLast()).path("text").asString();
        assertThat(progressMessage).contains("Mordred start progress \"Shipping\"", "#crowdfunding")
                .doesNotContain("Test & Spiel", "Pre-order Game", "expected delivery changed");
        assertThat(new StateStore(properties()).load().get(Section.CROWDFUNDING).projects().get("Mordred"))
                .containsEntry("Shipping", "In progress").containsEntry("Delivery", "2026 Q4");
        assertThat(runApplication()).isZero();
        assertThat(requests).hasSize(2);
    }

    @ParameterizedTest
    @CsvSource({"'',''", "'123:test-token',''", "'','123'"})
    void logsOnlyUpdatesWhenTelegramIsMissingOrIncomplete(String token, String chatId, CapturedOutput output) {
        var options = new String[]{"--monitor.telegram.bot-token=" + token, "--monitor.telegram.chat-id=" + chatId};
        assertThat(runApplication(options)).isZero();
        assertThat(output.getAll()).contains("Projektstatus [Crowdfunding] Mordred:", "Delivery=2026 Q4")
                .doesNotContain("Änderungsmeldung:");
        assertThat(requests).isEmpty();

        html = html.replace("2027 Q1", "2027 Q2");
        assertThat(runApplication(options)).isZero();
        assertThat(output.getAll()).contains("Änderungsmeldung:", "#pre-order",
                "Pre-order Game expected delivery changed: 2027 Q1 → 2027 Q2", "Delivery=2027 Q2");
        assertThat(requests).isEmpty();
        assertThat(new StateStore(properties()).load().get(Section.PRE_ORDER).projects().get("Pre-order Game"))
                .containsEntry("Delivery", "2027 Q2");

        assertThat(runApplication(options)).isZero();
        assertThat(output.getAll()).containsOnlyOnce("Änderungsmeldung:")
                .containsOnlyOnce("Pre-order Game expected delivery changed: 2027 Q1 → 2027 Q2");
        assertThat(requests).isEmpty();
    }

    @Test
    void neverSendsFirstSnapshotEvenWithOldFirstRunOption() {
        assertThat(runApplication("--monitor.notify-on-first-run=true")).isZero();
        assertThat(requests).isEmpty();
        assertThat(new StateStore(properties()).load()).hasSize(2);
    }

    @Test
    void rejectsInvalidConfiguredTokenWithoutLoggingIt(CapturedOutput output) {
        assertThat(runApplication("--monitor.telegram.bot-token=secret-bad-token")).isEqualTo(1);
        assertThat(output.getAll()).contains("TELEGRAM_BOT_TOKEN hat kein gültiges Format")
                .doesNotContain("secret-bad-token");
        assertThat(requests).isEmpty();
        assertThat(directory.resolve("cmon-state.mv.db")).doesNotExist();
    }

    @Test
    void telegramHttpAndApiFailuresAreSanitizedAndNotAcknowledged() {
        var notifier = new TelegramNotifier(properties(), JsonMapper.builder().build());
        telegramStatus.set(429);
        assertThatThrownBy(() -> notifier.send("Änderung 🎲"))
                .isInstanceOf(MonitorException.class).hasMessageContaining("HTTP 429")
                .hasMessageNotContaining("test-token");
        telegramStatus.set(200);
        telegramResponse = "{\"ok\":false,\"description\":\"secret test-token\"}";
        assertThatThrownBy(() -> notifier.send("Änderung 🎲"))
                .isInstanceOf(MonitorException.class).hasMessageNotContaining("test-token");
        telegramResponse = "broken-json";
        assertThatThrownBy(() -> notifier.send("Änderung 🎲"))
                .isInstanceOf(MonitorException.class).hasMessageNotContaining("test-token");
        telegramResponse = "";
        assertThatThrownBy(() -> notifier.send("Änderung 🎲"))
                .isInstanceOf(MonitorException.class).hasMessageNotContaining("test-token");
        telegramResponse = "{\"ok\":\"true\"}";
        assertThatThrownBy(() -> notifier.send("Änderung 🎲"))
                .isInstanceOf(MonitorException.class).hasMessageNotContaining("test-token");
    }

    @Test
    void pageHttpFailureReturnsNonZeroExitCode() {
        pageStatus.set(403);
        assertThat(runApplication()).isEqualTo(1);
        assertThat(new StateStore(properties()).load()).isEmpty();
        assertThat(requests).isEmpty();
    }

    @Test
    void rejectsRemovedWatchOptionWithoutCreatingDatabase() {
        try (var context = application().run(arguments("--watch"))) {
            assertThat(SpringApplication.exit(context)).isEqualTo(1);
            assertThat(directory.resolve("cmon-state.mv.db")).doesNotExist();
            assertThat(requests).isEmpty();
        }
    }

    @Test
    void invalidConfigurationFailsBeforeFetching() {
        try (var context = application().run(arguments("--monitor.request-timeout=0s"))) {
            assertThat(SpringApplication.exit(context)).isEqualTo(1);
            assertThat(directory.resolve("cmon-state.mv.db")).doesNotExist();
        }
    }

    private int runApplication(String... extra) {
        try (var context = application().run(arguments(extra))) {
            return SpringApplication.exit(context);
        }
    }

    private SpringApplication application() {
        return new SpringApplication(CmonUpdateApplication.class, TestClockConfiguration.class);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestClockConfiguration {
        @Bean
        @Primary
        Clock testClock() {
            return Clock.fixed(Instant.parse("2026-10-06T08:00:00Z"), ZoneId.of("Europe/Berlin"));
        }
    }

    private String[] arguments(String... extra) {
        var arguments = new ArrayList<>(List.of("--monitor.page-url=" + base.resolve("project-status/"),
                "--monitor.database-path=" + directory.resolve("cmon-state"),
                "--monitor.telegram.api-url=" + base,
                "--monitor.telegram.bot-token=123:test-token", "--monitor.telegram.chat-id=123"));
        for (var option : extra) {
            var key = option.split("=", 2)[0];
            arguments.removeIf(argument -> argument.equals(key) || argument.startsWith(key + "="));
            arguments.add(option);
        }
        return arguments.toArray(String[]::new);
    }

    private MonitorProperties properties() {
        return new MonitorProperties(base.resolve("project-status/"), directory.resolve("cmon-state"),
                Duration.ofSeconds(2),
                new MonitorProperties.Telegram("123:test-token", "123", base));
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        try (exchange) {
            var bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
    }
}
