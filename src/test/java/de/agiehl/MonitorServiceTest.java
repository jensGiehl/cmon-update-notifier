package de.agiehl;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.jsoup.Jsoup;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MonitorServiceTest {

    @TempDir
    Path directory;

    @Test
    void appliesSelectionToSundayReportInBothSections() throws Exception {
        var configured = TestFixtures.properties(directory);
        var properties = new MonitorProperties(configured.pageUrl(), configured.databasePath(),
                configured.requestTimeout(), configured.telegram(), List.of("mordRED", "pre-order game"));
        var document = Jsoup.parse(TestFixtures.html());
        document.selectFirst("#crowdfunding tbody tr td:nth-child(2) img").attr("alt", "In progress");
        var messages = new ArrayList<String>();

        service(properties, new StateStore(properties), document::outerHtml, messages::add,
                "2026-10-25T09:00:00Z").check();

        assertThat(messages).singleElement().asString()
                .contains("Crowdfunding\n", "Mordred\nShipping", "Pre-order\n", "Pre-order Game\nShipping")
                .doesNotContain("Test & Spiel", "Development");
    }

    @Test
    void confirmsActivityWithoutListingProjectsWhenNoTitleMatches() throws Exception {
        var configured = TestFixtures.properties(directory);
        var properties = new MonitorProperties(configured.pageUrl(), configured.databasePath(),
                configured.requestTimeout(), configured.telegram(), List.of("Unknown title"));
        var html = TestFixtures.html();
        var messages = new ArrayList<String>();

        service(properties, new StateStore(properties), () -> html, messages::add).check();
        var changed = html.replace("2027 Q1", "2027 Q2");
        service(properties, new StateStore(properties), () -> changed, messages::add,
                "2026-10-25T09:00:00Z").check();

        assertThat(messages).singleElement().asString().contains("weiterhin aktiv", "keine Projekte in progress")
                .doesNotContain("Mordred", "Test & Spiel", "Pre-order Game");
        assertThat(new StateStore(properties).load().get(Section.PRE_ORDER).projects().get("Pre-order Game"))
                .containsEntry("Delivery", "2027 Q2");
    }

    @Test
    void initializesSilentlySurvivesRestartAndOnlySendsChangedSection() throws Exception {
        var properties = TestFixtures.properties(directory);
        var store = new StateStore(properties);
        var messages = new ArrayList<String>();
        var html = TestFixtures.html();

        service(properties, store, () -> html, messages::add).check();
        assertThat(messages).isEmpty();
        var restartedStore = new StateStore(properties);
        service(properties, restartedStore, () -> html, messages::add).check();
        assertThat(messages).isEmpty();

        service(properties, restartedStore, () -> html.replace("2027 Q1", "2027 Q2"), messages::add).check();
        assertThat(messages).hasSize(1);
        assertThat(messages.getFirst())
                .contains("#pre-order", "Pre-order Game expected delivery changed: 2027 Q1 → 2027 Q2");
        assertThat(restartedStore.load().get(Section.CROWDFUNDING).projects()).containsKey("Test & Spiel 🎲");
    }

    @Test
    void failedDeliveryKeepsOldStateAndRetriesNextRun() throws Exception {
        var properties = TestFixtures.properties(directory);
        var store = new StateStore(properties);
        var html = TestFixtures.html();
        service(properties, store, () -> html, message -> {}).check();
        var before = store.load();
        var changed = html.replace("2027 Q1", "2027 Q2");

        assertThatThrownBy(() -> service(properties, store, () -> changed, message -> {
            throw new MonitorException("Telegram unavailable");
        }).check()).isInstanceOf(MonitorException.class);
        assertThat(store.load()).isEqualTo(before);
        var messages = new ArrayList<String>();
        service(properties, store, () -> changed, messages::add).check();
        assertThat(messages).hasSize(1);
        assertThat(store.load().get(Section.PRE_ORDER).projects().get("Pre-order Game"))
                .containsEntry("Delivery", "2027 Q2");
    }

    @Test
    void savesSuccessfulSectionWhileAnotherSectionFails() throws Exception {
        var properties = TestFixtures.properties(directory);
        var store = new StateStore(properties);
        var messages = new ArrayList<String>();
        var html = TestFixtures.html();

        service(properties, store, () -> html, messages::add).check();
        var changed = html.replace("2026</span> <span>Q4", "2027</span> <span>Q2")
                .replace("2027 Q1", "2027 Q2");
        assertThatThrownBy(() -> service(properties, store, () -> changed, message -> {
            if (message.contains("#crowdfunding")) {
                throw new MonitorException("Telegram unavailable");
            }
            messages.add(message);
        }).check()).isInstanceOf(MonitorException.class);
        assertThat(store.load().get(Section.CROWDFUNDING).projects().get("Mordred"))
                .containsEntry("Delivery", "2026 Q4");
        assertThat(store.load().get(Section.PRE_ORDER).projects().get("Pre-order Game"))
                .containsEntry("Delivery", "2027 Q2");

        service(properties, store, () -> changed, messages::add).check();
        assertThat(messages).hasSize(2);
        assertThat(store.load()).hasSize(2);
    }

    @Test
    void initializesNewGameSilentlyAndNotifiesOnlyItsSubsequentUpdate() throws Exception {
        var properties = TestFixtures.properties(directory);
        var store = new StateStore(properties);
        var messages = new ArrayList<String>();
        var html = TestFixtures.html();
        service(properties, store, () -> html, messages::add).check();

        var document = Jsoup.parse(html);
        var name = document.selectFirst("#crowdfunding .cps-projects li").clone();
        name.selectFirst("h3").text("Dune: Desert War");
        document.selectFirst("#crowdfunding .cps-projects ul").appendChild(name);
        var row = document.selectFirst("#crowdfunding tbody tr").clone();
        document.selectFirst("#crowdfunding tbody").appendChild(row);
        service(properties, store, document::outerHtml, messages::add).check();
        assertThat(messages).isEmpty();
        assertThat(store.load().get(Section.CROWDFUNDING).projects()).containsKey("Dune: Desert War");

        row.selectFirst("td:nth-child(2) img").attr("alt", "In progress");
        service(properties, store, document::outerHtml, messages::add).check();
        assertThat(messages).singleElement().asString().contains("Dune: Desert War start progress \"Shipping\"");
        service(properties, store, document::outerHtml, messages::add).check();
        assertThat(messages).hasSize(1);
    }

    @Test
    void neverOverwritesStateAfterInvalidPageOrCorruptDatabase() throws Exception {
        var properties = TestFixtures.properties(directory);
        var store = new StateStore(properties);
        var html = TestFixtures.html();
        service(properties, store, () -> html, message -> {}).check();
        var before = store.load();

        assertThatThrownBy(() -> service(properties, store, () -> "<h1>Error</h1>", message -> {}).check())
                .isInstanceOf(MonitorException.class);
        assertThat(store.load()).isEqualTo(before);

        var databaseFile = directory.resolve("cmon-state.mv.db");
        Files.writeString(databaseFile, "not-a-database");
        assertThatThrownBy(() -> service(properties, store, () -> html, message -> {}).check())
                .isInstanceOf(MonitorException.class);
        assertThat(Files.readString(databaseFile)).isEqualTo("not-a-database");
    }

    @Test
    void preventsConcurrentChecksWithSameDatabase() {
        var store = new StateStore(TestFixtures.properties(directory));
        try (var first = store.lock()) {
            var secondStore = new StateStore(TestFixtures.properties(directory));
            assertThatThrownBy(secondStore::lock).isInstanceOf(MonitorException.class);
        }
        try (var next = store.lock()) {
            assertThat(next.lock().isValid()).isTrue();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-10-11T07:59:59Z", "2026-10-10T09:00:00Z", "2026-10-12T09:00:00Z"})
    void doesNotSendWeeklyStatusBeforeSundayTenOrOnOtherDays(String instant) throws Exception {
        var properties = TestFixtures.properties(directory);
        var messages = new ArrayList<String>();
        var html = TestFixtures.html();

        service(properties, new StateStore(properties), () -> html, messages::add, instant).check();

        assertThat(messages).isEmpty();
    }

    @Test
    void sendsWeeklyStatusOnFirstSundayRunAndOnlyOnceAcrossRestarts() throws Exception {
        var properties = TestFixtures.properties(directory);
        var html = TestFixtures.html();
        var messages = new ArrayList<String>();
        service(properties, new StateStore(properties), () -> html, messages::add, "2026-10-11T08:00:00Z").check();
        assertThat(messages).singleElement().asString()
                .contains("weiterhin aktiv", "Test & Spiel 🎲", "Development", "Pre-order Game", "Shipping")
                .doesNotContain("Mordred");

        service(properties, new StateStore(properties), () -> html, messages::add, "2026-10-11T21:59:59Z").check();
        assertThat(messages).hasSize(1);
        service(properties, new StateStore(properties), () -> html, messages::add, "2026-10-18T08:00:00Z").check();
        assertThat(messages).hasSize(2);
    }

    @Test
    void includesActivePreordersInSundayStatusAndDoesNotResendAfterRestart() throws Exception {
        var properties = TestFixtures.properties(directory);
        var document = Jsoup.parse(TestFixtures.html());
        var preorders = document.getElementById("pre-order");
        var names = preorders.selectFirst("ul").empty();
        var headers = preorders.selectFirst("thead tr").empty();
        var rows = preorders.selectFirst("tbody").empty();
        var stages = List.of("Development", "Art & Graphics", "Sculpt", "Molding", "Proofing Files",
                "Mass Production", "Pledge Packing", "Shipping");
        stages.forEach(stage -> headers.appendElement("th").appendElement("img").attr("alt", stage));
        headers.appendElement("th").appendElement("img").attr("alt", "Delivery");
        record Preorder(String name, List<String> statuses, String delivery) {}
        var projects = List.of(
                new Preorder("The Adventurers", List.of("Completed", "Completed", "Completed", "Completed",
                        "Completed", "Completed", "Completed", "In progress"), "2026 Q4"),
                new Preorder("Super Fantasy Brawl Reborn", List.of("Completed", "In progress", "Completed",
                        "In progress", "In progress", "Not started", "Not started", "Not started"), "2027 Q2"),
                new Preorder("The Dead Keep", List.of("Completed", "Completed", "Completed", "Completed",
                        "Completed", "Not started", "Not started", "Not started"), "2027 Q1"),
                new Preorder("Cthulhu: Dark Providence", List.of("Completed", "Completed", "Completed", "Completed",
                        "Completed", "Completed", "Completed", "Completed"), "2026 Q1"));
        for (var project : projects) {
            names.appendElement("li").appendElement("h3").addClass("cps-projects__name").text(project.name());
            var row = rows.appendElement("tr");
            project.statuses().forEach(status -> row.appendElement("td").appendElement("figure")
                    .appendElement("img").attr("alt", status));
            row.appendElement("td").text(project.delivery());
        }
        var messages = new ArrayList<String>();

        service(properties, new StateStore(properties), document::outerHtml, messages::add,
                "2026-10-25T09:00:00Z").check();

        assertThat(messages).singleElement().asString()
                .contains("Pre-order\n" + Section.PRE_ORDER.link(TestFixtures.PAGE),
                        "The Adventurers\nShipping", "Super Fantasy Brawl Reborn\nArt & Graphics, Molding, Proofing Files")
                .doesNotContain("The Dead Keep", "Cthulhu: Dark Providence", "Not started", "Completed");

        service(properties, new StateStore(properties), document::outerHtml, messages::add,
                "2026-10-25T20:00:00Z").check();

        assertThat(messages).hasSize(1);
    }

    @Test
    void usesBerlinWinterTimeForSundayDeadline() throws Exception {
        var properties = TestFixtures.properties(directory);
        var html = TestFixtures.html();
        var messages = new ArrayList<String>();
        service(properties, new StateStore(properties), () -> html, messages::add, "2026-10-25T08:59:59Z").check();
        assertThat(messages).isEmpty();
        service(properties, new StateStore(properties), () -> html, messages::add, "2026-10-25T09:00:00Z").check();
        assertThat(messages).hasSize(1);
    }

    @Test
    void retriesFailedWeeklyStatusOnSameSundayWithoutRepeatingChanges() throws Exception {
        var properties = TestFixtures.properties(directory);
        var html = TestFixtures.html();
        var messages = new ArrayList<String>();
        service(properties, new StateStore(properties), () -> html, messages::add).check();
        var changed = html.replace("2027 Q1", "2027 Q2");

        assertThatThrownBy(() -> service(properties, new StateStore(properties), () -> changed, message -> {
            if (message.contains("weiterhin aktiv")) {
                throw new MonitorException("Telegram unavailable");
            }
            messages.add(message);
        }, "2026-10-11T08:00:00Z").check()).isInstanceOf(MonitorException.class);
        assertThat(messages).singleElement().asString().contains("expected delivery changed");

        service(properties, new StateStore(properties), () -> changed, messages::add, "2026-10-11T09:00:00Z").check();
        assertThat(messages).hasSize(2);
        assertThat(messages.getLast()).contains("weiterhin aktiv");
        service(properties, new StateStore(properties), () -> changed, messages::add, "2026-10-11T10:00:00Z").check();
        assertThat(messages).hasSize(2);
    }

    @Test
    void doesNotConsumeSundayNotificationWhenTelegramIsNotConfigured() throws Exception {
        var configured = TestFixtures.properties(directory);
        var properties = new MonitorProperties(configured.pageUrl(), configured.databasePath(),
                configured.requestTimeout(), null, configured.interestedTitles());
        var html = TestFixtures.html();
        var messages = new ArrayList<String>();

        service(properties, new StateStore(properties), () -> html, messages::add, "2026-10-11T08:00:00Z").check();
        assertThat(messages).isEmpty();
        service(configured, new StateStore(configured), () -> html, messages::add, "2026-10-11T09:00:00Z").check();
        assertThat(messages).hasSize(1);
    }

    private MonitorService service(MonitorProperties properties, StateStore store, PageSource source, Notifier notifier) {
        return service(properties, store, source, notifier, "2026-10-06T08:00:00Z");
    }

    private MonitorService service(MonitorProperties properties, StateStore store, PageSource source, Notifier notifier,
                                   String instant) {
        var clock = Clock.fixed(Instant.parse(instant), ZoneId.of("Europe/Berlin"));
        var weeklyStatus = new WeeklyStatusService(properties, store, new WeeklyStatusFormatter(), notifier, clock);
        return new MonitorService(properties, source, new ProjectStatusParser(), store, new ChangeFormatter(), notifier,
                weeklyStatus);
    }
}
