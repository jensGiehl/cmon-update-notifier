package de.agiehl;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(MonitorProperties.class)
public class CmonUpdateApplication {

    public static void main(String[] args) {
        System.setOut(new PrintStream(System.out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(System.err, true, StandardCharsets.UTF_8));
        if (Arrays.asList(args).contains("--help")) {
            System.out.println("""
                    CMON Update — Crowdfunding und Pre-order per Telegram überwachen

                    java -jar cmon-update.jar                 Einmal prüfen und beenden
                    java -jar cmon-update.jar --help          Diese Hilfe anzeigen

                    Optional: TELEGRAM_BOT_TOKEN und TELEGRAM_CHAT_ID
                    Ohne vollständige Telegram-Einstellungen werden Updates im Log ausgegeben.
                    Optionen: --monitor.database-path=./data/cmon-state
                    Der erste Stand wird gespeichert und nicht als Änderungsmeldung ausgegeben.
                    Zeitplanung: extern über Cron; jeder Aufruf prüft einmal und beendet sich.
                    Weitere Einstellungen: README.md
                    """);
            return;
        }
        var context = SpringApplication.run(CmonUpdateApplication.class, args);
        System.exit(SpringApplication.exit(context));
    }
}
