package de.agiehl;

import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class WeeklyStatusFormatterTest {

    private final WeeklyStatusFormatter formatter = new WeeklyStatusFormatter();

    @Test
    void listsAllActiveStagesInBothSectionsAndOmitsProjectsWithoutProgress() {
        var current = Map.of(
                Section.CROWDFUNDING, new SectionSnapshot("today", Map.of(
                        "Active 🎲", Map.of("Molding", "In progress", "Proofing Files", "In progress",
                                "Shipping", "Not started", "Delivery", "2027 Q1"),
                        "Finished", Map.of("Shipping", "Completed"),
                        "Waiting", Map.of("Molding", "Not started"))),
                Section.PRE_ORDER, new SectionSnapshot("today", Map.of(
                        "Pre-order active", Map.of("Shipping", " in PROGRESS "))));

        assertThat(formatter.messages(current, TestFixtures.PAGE)).singleElement().asString()
                .contains("weiterhin aktiv", TestFixtures.PAGE.toString(),
                        "[Crowdfunding] Active 🎲: In progress — Molding, Proofing Files",
                        "[Pre-order] Pre-order active: In progress — Shipping")
                .doesNotContain("Finished", "Waiting", "Not started", "Completed", "2027 Q1");
    }

    @Test
    void sendsActivityConfirmationEvenWhenNoProjectIsInProgress() {
        var snapshot = new SectionSnapshot("today", Map.of("Finished", Map.of("Shipping", "Completed")));

        assertThat(formatter.messages(Map.of(Section.CROWDFUNDING, snapshot, Section.PRE_ORDER, snapshot),
                TestFixtures.PAGE)).singleElement().asString()
                .contains("weiterhin aktiv", "keine Projekte in progress").doesNotContain("Finished");
    }

    @Test
    void splitsLongStatusIntoTelegramSizedPartsWithActivityConfirmationAndLink() {
        var snapshot = new SectionSnapshot("today", Map.of("Long 🎲".repeat(1000), Map.of("Shipping", "In progress")));

        assertThat(formatter.messages(Map.of(Section.CROWDFUNDING, snapshot, Section.PRE_ORDER, snapshot),
                TestFixtures.PAGE)).hasSizeGreaterThan(1).allSatisfy(message -> {
                    assertThat(message.length()).isLessThanOrEqualTo(4000);
                    assertThat(message).contains("weiterhin aktiv", TestFixtures.PAGE.toString());
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
