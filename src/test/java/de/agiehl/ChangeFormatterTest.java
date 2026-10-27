package de.agiehl;

import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ChangeFormatterTest {

    private final ChangeFormatter formatter = new ChangeFormatter();

    @Test
    void onlyReportsChangesAndRemovalsForSelectedTitles() {
        var previous = new SectionSnapshot("old", Map.of(
                "Mordred", Map.of("Shipping", "Not started"),
                "DCeased", Map.of("Shipping", "In progress"),
                "Ignored", Map.of("Shipping", "In progress"),
                "Ignored removal", Map.of("Shipping", "Completed")));
        var current = new SectionSnapshot("new", Map.of(
                "Mordred", Map.of("Shipping", "In progress"),
                "Ignored", Map.of("Shipping", "Completed")));

        assertThat(formatter.messages(Section.CROWDFUNDING, previous, current, TestFixtures.PAGE,
                title -> title.equals("Mordred") || title.equals("DCeased")))
                .singleElement().asString()
                .contains("Mordred start progress", "DCeased removed from project status")
                .doesNotContain("Ignored");
    }

    @Test
    void reportsKnownChangesAndRemovalsButInitializesNewGameSilently() {
        var previous = new SectionSnapshot("old", Map.of("Changed", Map.of("Shipping", "Not started"),
                "Removed", Map.of("Shipping", "Completed")));
        var current = new SectionSnapshot("new", Map.of("Changed", Map.of("Shipping", "In progress"),
                "Added", Map.of("Delivery", "2027 Q1")));

        assertThat(String.join("\n", formatter.messages(Section.PRE_ORDER, previous, current, TestFixtures.PAGE, title -> true)))
                .contains("#pre-order", "Changed start progress \"Shipping\"",
                        "Removed removed from project status")
                .doesNotContain("Added", "old → new");
    }

    @Test
    void neverNotifiesWithoutPreviousSnapshot() {
        var current = new SectionSnapshot("today", Map.of("Mordred", Map.of("Shipping", "In progress")));

        assertThat(formatter.messages(Section.CROWDFUNDING, null, current, TestFixtures.PAGE, title -> true)).isEmpty();
    }

    @Test
    void initializesNewStageAndDeliverySilentlyWhenNoPreviousValueExists() {
        var previous = new SectionSnapshot("old", Map.of("Mordred", Map.of("Shipping", "In progress")));
        var current = new SectionSnapshot("new", Map.of("Mordred", Map.of(
                "Shipping", "In progress", "Proofing Files", "Completed", "Delivery", "2026 Q4")));

        assertThat(formatter.messages(Section.CROWDFUNDING, previous, current, TestFixtures.PAGE, title -> true)).isEmpty();
    }

    @Test
    void reportsFinishedAndStartedStepsExactlyAsRequestedWithoutRepeatingUnchangedStatuses() {
        var previous = new SectionSnapshot("old", Map.of("Mordred", Map.of(
                "Mass Production", "Completed", "Pledge Packing", "In progress",
                "Shipping", "Not started", "Delivery", "2026 Q4")));
        var current = new SectionSnapshot("new", Map.of("Mordred", Map.of(
                "Mass Production", "Completed", "Pledge Packing", "Completed",
                "Shipping", "In progress", "Delivery", "2026 Q4")));

        assertThat(formatter.messages(Section.CROWDFUNDING, previous, current, TestFixtures.PAGE, title -> true))
                .singleElement().asString()
                .contains("Mordred finished \"Pledge Packing\"", "Mordred start progress \"Shipping\"", "#crowdfunding")
                .doesNotContain("Mass Production", "expected delivery", "old", "new");
    }

    @Test
    void reportsDeliveryChangeOnlyForTheAffectedGame() {
        var dune = Map.of("Molding", "In progress", "Delivery", "2027 Q1");
        var previous = new SectionSnapshot("old", Map.of(
                "DCeased", Map.of("Delivery", "2026 Q4"), "Dune: Desert War", dune));
        var current = new SectionSnapshot("new", Map.of(
                "DCeased", Map.of("Delivery", "2027 Q1"), "Dune: Desert War", dune));

        assertThat(formatter.messages(Section.CROWDFUNDING, previous, current, TestFixtures.PAGE, title -> true))
                .singleElement().asString().contains("DCeased expected delivery changed: 2026 Q4 → 2027 Q1")
                .doesNotContain("Dune: Desert War", "finished \"Delivery\"");
    }

    @Test
    void ignoresPageUpdateDateWhenGamesHaveNotChanged() {
        var projects = Map.of("Mordred", Map.of("Pledge Packing", "In progress", "Delivery", "2026 Q4"));

        assertThat(formatter.messages(Section.CROWDFUNDING, new SectionSnapshot("old", projects),
                new SectionSnapshot("new", projects), TestFixtures.PAGE, title -> true)).isEmpty();
    }

    @Test
    void reportsRegressionsAndUnexpectedStatusesWithBothValues() {
        var previous = new SectionSnapshot("old", Map.of("Mordred", Map.of(
                "Shipping", "In progress", "Proofing Files", "Completed")));
        var current = new SectionSnapshot("new", Map.of("Mordred", Map.of(
                "Shipping", "Not started", "Proofing Files", "On hold")));

        assertThat(formatter.messages(Section.CROWDFUNDING, previous, current, TestFixtures.PAGE, title -> true))
                .singleElement().asString()
                .contains("Mordred — \"Shipping\": In progress → Not started",
                        "Mordred — \"Proofing Files\": Completed → On hold");
    }

    @Test
    void splitsLongMessagesWithoutBreakingEmojiAndKeepsLinkInEveryPart() {
        var game = "Very long 🎲".repeat(1000);
        var previous = new SectionSnapshot("yesterday", Map.of(game, Map.of("Delivery", "2026 Q4")));
        var current = new SectionSnapshot("today", Map.of(game, Map.of("Delivery", "2027 Q1")));

        var messages = formatter.messages(Section.CROWDFUNDING, previous, current, TestFixtures.PAGE, title -> true);

        assertThat(messages).hasSizeGreaterThan(1).allSatisfy(message -> {
            assertThat(message.length()).isLessThanOrEqualTo(4000);
            assertThat(message).contains("https://www.cmon.com/project-status/#crowdfunding");
            for (int index = 0; index < message.length(); index++) {
                if (Character.isHighSurrogate(message.charAt(index))) {
                    assertThat(index + 1).isLessThan(message.length());
                    assertThat(Character.isLowSurrogate(message.charAt(++index))).isTrue();
                } else {
                    assertThat(Character.isLowSurrogate(message.charAt(index))).isFalse();
                }
            }
        });
    }
}
