package de.agiehl;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProjectStatusParserTest {

    private final ProjectStatusParser parser = new ProjectStatusParser();

    @Test
    void readsSeparateNamesAndTableRowsIncludingImageOnlyStatuses() throws Exception {
        var snapshots = parser.parse(TestFixtures.html(), TestFixtures.PAGE);

        assertThat(snapshots).hasSize(2);
        assertThat(snapshots.get(Section.CROWDFUNDING).lastUpdated()).isEqualTo("2 Oct 2026.");
        assertThat(snapshots.get(Section.CROWDFUNDING).projects().get("Mordred"))
                .containsEntry("Development", "Completed")
                .containsEntry("Shipping", "Not started")
                .containsEntry("Delivery", "2026 Q4");
        assertThat(snapshots.get(Section.CROWDFUNDING).projects()).containsKey("Test & Spiel 🎲");
        assertThat(snapshots.get(Section.PRE_ORDER).projects().get("Pre-order Game"))
                .containsEntry("Shipping", "In progress");
    }

    @Test
    void ignoresStylesScriptsFooterAndImageAssetUrls() throws Exception {
        var html = TestFixtures.html();
        var changed = html.replace("Newsletter", "Other footer")
                .replace("'random'", "'other'").replace("completed.png", "completed-v2.png")
                .replace("id=\"crowdfunding\"", "id=\"crowdfunding\" style=\"display:none\"");

        assertThat(parser.parse(changed, TestFixtures.PAGE)).isEqualTo(parser.parse(html, TestFixtures.PAGE));
    }

    @Test
    void detectsImageStatusChangeWithIdenticalVisibleText() throws Exception {
        var html = TestFixtures.html();
        var changed = html.replaceFirst("alt=\"Completed\"", "alt=\"In progress\"");

        assertThat(parser.parse(changed, TestFixtures.PAGE).get(Section.CROWDFUNDING))
                .isNotEqualTo(parser.parse(html, TestFixtures.PAGE).get(Section.CROWDFUNDING));
    }

    @Test
    void rejectsMissingSectionChallengePagesAndMismatchedRows() throws Exception {
        var html = TestFixtures.html();
        assertThatThrownBy(() -> parser.parse("<h1>Just a moment...</h1>", TestFixtures.PAGE))
                .isInstanceOf(MonitorException.class);
        assertThatThrownBy(() -> parser.parse(html.replace("id=\"pre-order\"", "id=\"missing\""), TestFixtures.PAGE))
                .isInstanceOf(MonitorException.class);
        assertThatThrownBy(() -> parser.parse(html.replace("class=\"cps-projects__name\">Mordred", ">Mordred"), TestFixtures.PAGE))
                .isInstanceOf(MonitorException.class);
    }
}
