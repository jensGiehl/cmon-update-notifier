package de.agiehl;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

final class TestFixtures {

    static final URI PAGE = URI.create("https://www.cmon.com/project-status/");

    private TestFixtures() {
    }

    static String html() throws IOException {
        try (var input = TestFixtures.class.getResourceAsStream("/project-status.html")) {
            return new String(Objects.requireNonNull(input).readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    static MonitorProperties properties(Path directory) {
        return new MonitorProperties(PAGE, directory.resolve("cmon-state"),
                Duration.ofSeconds(5),
                new MonitorProperties.Telegram("123:test-token", "123", URI.create("https://api.telegram.org/")));
    }
}
