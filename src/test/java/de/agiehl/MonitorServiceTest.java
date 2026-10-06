package de.agiehl;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jsoup.Jsoup;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MonitorServiceTest {

    @TempDir
    Path directory;

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

    private MonitorService service(MonitorProperties properties, StateStore store, PageSource source, Notifier notifier) {
        return new MonitorService(properties, source, new ProjectStatusParser(), store, new ChangeFormatter(), notifier);
    }
}
