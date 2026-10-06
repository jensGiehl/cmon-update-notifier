# CMON Update

Spring-Boot-CLI-Anwendung für **Cron**: Jeder Aufruf prüft einmal die Projektstatus auf
[Crowdfunding](https://www.cmon.com/project-status/#crowdfunding) und
[Pre-order](https://www.cmon.com/project-status/#pre-order), meldet Änderungen mit dem
jeweiligen Webseitenlink per Telegram oder im Log und beendet sich. Der Vergleichsstand wird in
einer **dateibasierten H2-Datenbank** gespeichert.

## Verhalten und H2

- Beide Abschnitte stammen aus derselben HTML-Seite, die pro Aufruf einmal abgerufen wird.
- Für jedes Spiel werden alle Produktionsschritte separat gespeichert und verglichen,
  z. B. Molding, Proofing Files, Mass Production, Pledge Packing und Shipping. Je Schritt
  wird der Status `Not started`, `In progress` oder `Completed` erfasst. Mehrere Schritte
  können gleichzeitig in Arbeit sein. Der erwartete Liefertermin, z. B. `2026 Q4`, wird
  ebenfalls pro Spiel gespeichert und unabhängig von den Produktionsschritten verglichen.
- Telegram nennt ausschließlich geänderte Spiele und Schritte. Eine Änderung nur am
  „Last Updated“-Datum löst keine Nachricht aus. Entfernte Spiele werden gemeldet.
  Für neu auftauchende Spiele oder zusätzliche Schritte wird zunächst still ein Ausgangsstand gespeichert.
- Layout, Skripte, Bilddatei-URLs, Footer und die Reihenfolge der Projekte erzeugen keine Meldungen.
- Ohne bekannten vorherigen Status wird für den Änderungsvergleich **nur der Ausgangsstand gespeichert**.
  Der erste Start erzeugt keine Änderungsmeldung, weder über Telegram noch über den
  Logging-Ausgabekanal. Erst ein späterer Wechsel eines bekannten Status oder Liefertermins
  löst eine Benachrichtigung aus. Die frühere Option `--monitor.notify-on-first-run` und
  `CMON_NOTIFY_ON_FIRST_RUN` werden nicht mehr ausgewertet.
- Bei jedem erfolgreichen Seitenabruf werden **alle aktuellen Projektstatus auf INFO-Level
  protokolliert**, einschließlich Produktionsschritten, Liefertermin und Webseiten-Aktualisierungsdatum.
  Diese Statusausgabe erfolgt auch beim ersten Start und bei unveränderten Projekten.
- H2 speichert die Abschnitte und Aktualisierungsdaten in `monitor_snapshot`, die
  Projektstatus in `project_status`: eine Zeile je Abschnitt, Spielname und Schritt.
  Der Liefertermin steht unter dem Schlüssel `Delivery`. Die Datenbank wird automatisch angelegt;
  **Flyway** verwaltet das Tabellenschema mit versionierten SQL-Migrationen.
  Es werden keine JSON-Dateien zur Persistenz verwendet.
- Der Standardpfad ist `./data/cmon-state`. H2 ergänzt die Dateiendung selbst:
  **`./data/cmon-state.mv.db`**. Nach jedem Aufruf wird die Datenbankverbindung geschlossen.
- Ein geänderter Abschnitt wird erst nach erfolgreicher Ausgabe aller zugehörigen Änderungsmeldungen
  in einer Transaktion gespeichert. Bei Versandfehlern erfolgt ein neuer Versuch beim
  nächsten Cron-Aufruf. Erfolgreiche Abschnitte bleiben auch dann gespeichert, wenn ein
  anderer Abschnitt fehlschlägt. Datenbankfehler führen zu einem Rollback der Änderung.
- Eine Dateisperre (`cmon-state.run.lock`) schützt die gesamte Prüfung davor, dass sich
  Cron-Aufrufe mit derselben Datenbank überschneiden. Ein zweiter Aufruf beendet sich mit
  Exit-Code `1`, ohne die Seite abzurufen oder Nachrichten zu senden. Die Sperrdatei darf
  bestehen bleiben; entscheidend ist die Betriebssystem-Sperre während des laufenden Prozesses.
- Lange Telegram-Meldungen werden aufgeteilt; jeder Teil enthält den Webseitenlink.
  Projektnamen werden als Klartext versendet.

## Sonntägliche Aktivitätsmeldung

Bei konfiguriertem Telegram versendet die Anwendung beim ersten erfolgreichen Seitenabruf
am **Sonntag ab 10:00 Uhr in Europe/Berlin** eine zusätzliche Meldung, dass sie weiterhin
aktiv ist. Sommer- und Winterzeit werden automatisch berücksichtigt. Die Meldung enthält
für Crowdfunding und Pre-order alle Projekte mit mindestens einem Schritt `In progress`
und nennt sämtliche aktuell laufenden Schritte. Projekte ohne laufende Schritte werden
nicht angezeigt. Sind keine Projekte in Arbeit, wird trotzdem eine kurze Aktivitätsmeldung
gesendet. Diese Meldung erfolgt auch beim ersten Start oder ohne Statusänderungen.

```text
CMON Update ist weiterhin aktiv — Sonntagsstatus
https://www.cmon.com/project-status/

[Crowdfunding] Mordred: In progress — Pledge Packing
[Pre-order] Beispielspiel: In progress — Mass Production, Proofing Files
```

Das Versanddatum wird nach erfolgreichem Versand aller Teile in H2 unter
`weekly_status_notification` gespeichert. Die Flyway-Migration
`V2__create_weekly_status.sql` ergänzt diese Tabelle automatisch und erhält vorhandene
Projektstatus. Weitere Aufrufe am selben Sonntag senden keine zweite Aktivitätsmeldung,
auch nach Neustarts oder einem Containerwechsel mit demselben Datenverzeichnis.
Am nächsten Sonntag kann wieder eine Meldung gesendet werden. Ohne Telegram wird kein
Versanddatum gespeichert. Versandfehler werden bei einem weiteren Lauf am selben Sonntag
ab 10 Uhr erneut versucht. Verpasste Sonntage werden nicht nachträglich gemeldet.

Die vorhandene Dateisperre schützt auch diese Prüfung und den Versand. Wie bei
Änderungsmeldungen kann ein Prozessabbruch zwischen Telegram-Bestätigung und H2-Speicherung
oder ein Fehler in einem späteren Nachrichtenteil zu einer Wiederholung führen.
Cron muss die Anwendung sonntags ab 10 Uhr ausführen; die bestehenden stündlichen
Cron-Beispiele erfüllen das. Im Docker-Betrieb muss `/data` dauerhaft eingebunden bleiben.

Die SQL-Migrationen liegen in `src/main/resources/db/migration`. Bei einer neuen Datenbank
legt `V1__create_project_status.sql` die Tabellen an. Bereits vorhandene H2-Datenbanken aus
der bisherigen Anwendung werden automatisch mit **Baseline-Version 1** übernommen;
gespeicherte Projektstatus bleiben erhalten. Flyway protokolliert den Schema-Stand in
`flyway_schema_history`. Neue Schemaänderungen werden als zusätzliche Migrationen
(`V2__...sql`, `V3__...sql` usw.) ergänzt; bereits ausgeführte Dateien bleiben unverändert.
Beim ersten Datenbankzugriff jedes Cron-Laufs validiert Flyway die Migrationen und führt
ausstehende Migrationen innerhalb der bestehenden Dateisperre aus, bevor die Webseite
abgerufen oder eine Änderungsmeldung ausgegeben wird. Bei einem Migrationsfehler endet
der Lauf mit Exit-Code `1`.

Sind Bot-Token oder Chat-ID nicht gesetzt bzw. leer, werden Änderungsmeldungen über
`LoggingNotifier` auf INFO-Level ausgegeben. Sind beide Telegram-Einstellungen vorhanden,
wird Telegram verwendet. Der erste Stand und unveränderte Daten erzeugen in beiden Modi
keine Änderungsmeldung. Ein Beispiel für die regelmäßige Statusausgabe:

```text
Projektstatus [Crowdfunding] Mordred: Delivery=2026 Q4; Pledge Packing=In progress; Shipping=Not started
```

Auch im Logging-Modus wird der neue Vergleichsstand in H2 gespeichert. Wird Telegram
später konfiguriert, werden deshalb nur danach erkannte Updates versendet. Ein Fehler beim
konfigurierten Telegram-Versand bleibt ein Versandfehler mit erneutem Versuch beim nächsten
Cron-Aufruf.

Beispiel einer Telegram-Meldung, wenn Mordred Pledge Packing abschließt und Shipping beginnt:

```text
CMON Crowdfunding — Änderungen
https://www.cmon.com/project-status/#crowdfunding

Mordred finished "Pledge Packing"
Mordred start progress "Shipping"
```

Ändert sich nur der Liefertermin, lautet die Meldung beispielsweise:

```text
DCeased expected delivery changed: 2026 Q4 → 2027 Q1
```

Auch Rückschritte oder unerwartete Statuswerte werden mit dem alten und neuen Wert
gemeldet, beispielsweise `Dune: Desert War — "Molding": In progress → Not started`.
Jede Nachricht enthält den Link zum entsprechenden CMON-Abschnitt. Unveränderte Spiele,
Schritte und Liefertermine werden nicht wiederholt. Änderungen eines Abschnitts werden
in einer gemeinsamen Nachricht zusammengefasst, bei Bedarf in mehreren Teilen.

Das Prüfintervall wird ausschließlich durch Cron festgelegt. `--watch`, `--monitor.watch`,
`--monitor.interval` und `--monitor.state-file` werden nicht mehr unterstützt.
Die bisherigen Variablen `CMON_WATCH`, `CMON_INTERVAL` und `CMON_STATE_FILE` können aus
der Umgebung entfernt werden. Ein alter JSON-Stand wird nicht importiert; beim ersten
H2-Aufruf wird ein neuer Ausgangsstand erfasst.

## Voraussetzungen und Build

Java **27**, Spring Boot **4.1.1**, jsoup **1.23.2** und die von Spring Boot verwaltete
H2- und Flyway-Versionen. Maven **3.9.16** wird über den mitgelieferten Maven Wrapper verwendet.
Alle eigenen Java-Klassen liegen im Package `de.agiehl`. Die Quellen und Konfigurationsdateien
sind UTF-8-kodiert. Das Spring-Boot-Banner ist deaktiviert. Es gibt keinen HTTP-Server.

Linux/macOS:

```bash
sh ./mvnw -B -ntp verify
java -jar target/cmon-update.jar --help
```

Windows/PowerShell mit Java 27 in `JAVA_HOME` und `PATH`:

```powershell
.\mvnw.cmd -B -ntp verify
java -jar target/cmon-update.jar --help
```

Die Tests prüfen Parser, Änderungen, Flyway-Migrationen für neue und vorhandene H2-Datenbanken,
H2-Persistenz nach erneuten Aufrufen,
Transaktions-Rollback, konkurrierende Prüfungen, die Sonntagsmeldung einschließlich
Zeitgrenze, Sommer-/Winterzeit, Neustarts und Versandfehlern, Logging-Ausgaben ohne Telegram und die HTTP-Kommunikation über lokale
Testendpunkte. Sie senden keine echten Telegram-Nachrichten.

## Telegram und Konfiguration

Telegram ist optional. Ohne Einstellungen genügt beispielsweise:

```bash
java -jar target/cmon-update.jar --monitor.database-path=/opt/cmon-update/data/cmon-state
```

Die Anwendung protokolliert den aktuellen Projektstatus und bei späteren Änderungen die
entsprechenden Meldungen. Für Telegram die beiden folgenden Einstellungen ergänzen:

1. Bei [@BotFather](https://t.me/BotFather) mit `/newbot` einen Bot anlegen und den Token übernehmen.
2. Den neuen Bot öffnen, „Start“ drücken und eine Nachricht senden. Für Gruppen den Bot
   hinzufügen und beispielsweise einen an ihn gerichteten Befehl senden.
3. Über [getUpdates](https://core.telegram.org/bots/api#getupdates) die `message.chat.id`
   ermitteln. Gruppen-IDs können negativ sein.

```bash
export TELEGRAM_BOT_TOKEN='123456789:DEIN_TOKEN'
curl -sS "https://api.telegram.org/bot${TELEGRAM_BOT_TOKEN}/getUpdates"
export TELEGRAM_CHAT_ID='DEINE_CHAT_ID'
java -jar target/cmon-update.jar
```

Der Bot muss im Zielchat Nachrichten senden dürfen. Für Kanäle kann `@kanalname`
als Chat-ID verwendet werden, wenn der Bot die erforderlichen Rechte besitzt.

| Umgebungsvariable | Spring-CLI-Option | Standard |
| --- | --- | --- |
| `TELEGRAM_BOT_TOKEN` | `--monitor.telegram.bot-token` | leer; für Telegram erforderlich |
| `TELEGRAM_CHAT_ID` | `--monitor.telegram.chat-id` | leer; für Telegram erforderlich |
| `CMON_DATABASE_PATH` | `--monitor.database-path` | `./data/cmon-state`, im Docker-Image `/data/cmon-state` |
| `CMON_REQUEST_TIMEOUT` | `--monitor.request-timeout` | `30s` |
| `CMON_PAGE_URL` | `--monitor.page-url` | `https://www.cmon.com/project-status/` |
| `TELEGRAM_API_URL` | `--monitor.telegram.api-url` | `https://api.telegram.org/` |

CLI-Optionen haben Vorrang vor Umgebungsvariablen. Für die Datenbank einen Pfad **ohne
`.mv.db`-Endung und ohne Semikolon** angeben. Relative Pfade beziehen sich auf das
Arbeitsverzeichnis; bei Cron sind deshalb absolute Pfade vorgesehen.
Zeitangaben für den Request-Timeout unterstützen z. B. `30s`, `1m` und `PT30S`.
Für Secrets werden Umgebungsvariablen empfohlen, da CLI-Argumente in Prozesslisten sichtbar sind.

```bash
java -jar target/cmon-update.jar \
  --monitor.database-path=/opt/cmon-update/data/cmon-state
```

Exit-Codes: `0` für eine erfolgreiche Prüfung (auch ohne Änderung), `1` bei Konfigurations-,
Abruf-, Parser-, Datenbank-, Sperr- oder Versandfehlern. Eine Unterbrechung des Prüfthreads
ergibt `130`; bei Betriebssystemsignalen gelten die Exit-Codes der JVM bzw. des Betriebssystems.
Bei Fehlern endet der Aufruf; die nächste Prüfung erfolgt zum nächsten Cron-Termin.

## Cron mit Java

Die mitgelieferten Startskripte verwenden standardmäßig `/opt/cmon-update`, damit Cron
unabhängig vom aktuellen Arbeitsverzeichnis arbeitet. Die Java-Variante lädt vorhandene
Zugangsdaten selbst aus der optionalen `telegram.env`; interaktiv gesetzte Umgebungsvariablen werden
für den Cron-Aufruf nicht vorausgesetzt.

Installation nach dem Maven-Build:

```bash
sudo install -d -o "$(id -u)" -g "$(id -g)" /opt/cmon-update
mkdir -p /opt/cmon-update/data
cp target/cmon-update.jar /opt/cmon-update/
cp scripts/run-java.sh /opt/cmon-update/
cp telegram.env.example /opt/cmon-update/telegram.env
chmod 700 /opt/cmon-update/run-java.sh
chmod 600 /opt/cmon-update/telegram.env
nano /opt/cmon-update/telegram.env
```

`telegram.env` muss für die Java-Variante Shell-kompatibel sein, z. B.
`TELEGRAM_BOT_TOKEN=123456789:DEIN_TOKEN` und `TELEGRAM_CHAT_ID=-123456789`.
Die Beispieldatei enthält zunächst leere Werte für den Logging-Modus. Sie kann auch ganz
entfallen. In diesem Fall startet das Skript ohne eine zusätzliche Konfigurationsdatei.
Ein optionaler `CMON_DATABASE_PATH` muss absolut sein. Ohne diesen Eintrag setzt das
Skript automatisch `/opt/cmon-update/data/cmon-state`.

Einmal manuell prüfen:

```bash
/bin/sh /opt/cmon-update/run-java.sh
```

Mit `crontab -e` stündlich starten:

```cron
0 * * * * /usr/bin/flock -n /opt/cmon-update/cron.lock /bin/sh /opt/cmon-update/run-java.sh >> /opt/cmon-update/cmon.log 2>&1
```

`flock` verhindert bereits vor dem Start der JVM parallele Cron-Aufrufe. Zusätzlich
schützt die Anwendung selbst die Datenbank. Der Cron-Benutzer benötigt Schreibrechte
auf das Daten- und Logverzeichnis. `/usr/bin/java` muss Java 27 sein; für einen anderen
Installationspfad `JAVA_BIN=/pfad/zu/java` in `telegram.env` setzen.
Mit `CMON_HOME` kann ein anderes Installationsverzeichnis gewählt werden.

## Cron mit Docker auf dem Raspberry Pi

Das Image unterstützt **linux/arm64** für Raspberry Pi mit **64-Bit-Betriebssystem** und
**linux/amd64**. Ein 32-Bit-System (`arm/v7`) wird nicht unterstützt.
Die GitHub Action veröffentlicht bei Pushes auf `main` oder `master` nach erfolgreichen
Tests das Image `ghcr.io/jensgiehl/cmon-update-notifier:latest`, zusätzlich Branch- und SHA-Tags.
Für private Images vorher mit einem Token
mit `read:packages` bei `ghcr.io` anmelden.

Startskript und Datenverzeichnis vorbereiten:

```bash
sudo install -d -o "$(id -u)" -g "$(id -g)" /opt/cmon-update
cp scripts/run-docker.sh /opt/cmon-update/
cp telegram.env.example /opt/cmon-update/telegram.env
chmod 700 /opt/cmon-update/run-docker.sh
chmod 600 /opt/cmon-update/telegram.env
nano /opt/cmon-update/telegram.env
sudo mkdir -p /opt/cmon-update/data
sudo chown 10001:10001 /opt/cmon-update/data
```

Das Image läuft mit UID/GID `10001:10001`. Das Startskript bindet das Hostverzeichnis
`/opt/cmon-update/data` nach `/data` ein. Dort liegt **`cmon-state.mv.db`** mit dem
Vergleichsstand; dieser bleibt nach dem Entfernen des Containers erhalten.
Die Datenbank ist eingebettet, ein separater H2-Server wird nicht benötigt.

Einmal prüfen:

```bash
/bin/sh /opt/cmon-update/run-docker.sh ghcr.io/jensgiehl/cmon-update-notifier:latest
```

Mit `crontab -e` stündlich starten:

```cron
0 * * * * /usr/bin/flock -n /opt/cmon-update/cron.lock /bin/sh /opt/cmon-update/run-docker.sh ghcr.io/jensgiehl/cmon-update-notifier:latest >> /opt/cmon-update/cmon.log 2>&1
```

Der Cron-Benutzer benötigt Zugriff auf Docker. Das Skript verwendet `/usr/bin/docker`
(über `DOCKER_BIN` anpassbar), lädt das Image mit `--pull=always`, wartet auf das Ende
der Prüfung und entfernt den Container mit `--rm`. Cron erhält den Exit-Code der Anwendung.
Fehlt `telegram.env`, startet der Container ohne `--env-file` im Logging-Modus. Die
Beispieldatei kann ebenfalls unverändert mit leeren Telegram-Werten verwendet werden.
Für den Zeitplan eine der beiden Varianten wählen.

Beispiel für einen manuell gestarteten, benannten Container:

```bash
docker rm -f cmon-update 2>/dev/null

docker run -d \
  --name cmon-update \
  --pull=always \
  -p 8089:8080 \
  --env-file /opt/cmon-update/telegram.env \
  -v /opt/cmon-update/data:/data \
  ghcr.io/jensgiehl/cmon-update-notifier:latest

docker wait cmon-update
docker logs cmon-update
```

Auch dieser Container beendet sich nach **einer Prüfung**. **8089 ist der Port am Host**,
8080 wäre der Container-Port. Da die Anwendung keinen HTTP-Server betreibt, ist die
Portzuordnung optional und stellt keinen erreichbaren Webdienst bereit.
Ein Restart-Policy ist für die Cron-Ausführung nicht vorgesehen.

## GitHub Actions und lokaler Docker-Build

[`.github/workflows/docker.yml`](.github/workflows/docker.yml) führt bei Pushes und Pull Requests
auf `main`/`master` zunächst `mvn verify` mit Java 27 aus. Nach erfolgreichen Tests
werden bei Pushes Multi-Arch-Images gebaut und in die GitHub Container Registry hochgeladen.
`workflow_dispatch` erlaubt einen manuellen Lauf; veröffentlicht wird dabei nur von
`main`/`master`. Der Workflow nutzt `GITHUB_TOKEN` mit `packages: write`.

Lokal mit laufendem Docker-Daemon:

```bash
sh ./mvnw -B -ntp verify
docker build -t cmon-update:local .
```

## Fehlerfälle und Sicherung

- HTTP-Fehler, leere Abschnitte und unerwartetes HTML verändern den gespeicherten Stand nicht.
  Sollte CMON den Zugriff mit HTTP 403 blockieren, wird dies als Abruffehler protokolliert.
  Interaktive Browser-Challenges werden nicht automatisch gelöst.
- Telegram-Fehler werden beim nächsten Cron-Aufruf erneut versucht. Bot-Token, vollständige
  Telegram-URLs und API-Antworttexte werden dabei nicht protokolliert.
- Zwischen Ausgabe einer Änderungsmeldung und H2-Commit kann ein Prozessabbruch zu einer
  doppelten Meldung beim nächsten Lauf führen. Dasselbe gilt für bereits versendete Teile
  einer mehrteiligen Nachricht, wenn ein späterer Teil fehlschlägt.
- Für eine Sicherung den Cron-Aufruf vorübergehend deaktivieren, das Ende einer laufenden
  Prüfung abwarten und `cmon-state.mv.db` kopieren. Bei Docker dasselbe gemountete
  Hostverzeichnis sichern. Eine beschädigte oder nicht lesbare Datenbank wird als Fehler
  gemeldet; es erfolgt kein automatisches Zurücksetzen.
- Ohne persistentes Datenverzeichnis wird im Docker-Betrieb bei jedem neuen Container ein
  neuer Ausgangsstand angelegt. H2-Dateien, Sperrdateien und Zugangsdaten sind in Git sowie
  im Docker-Build ausgeschlossen.
