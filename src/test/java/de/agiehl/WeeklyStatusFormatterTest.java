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

        var messages = formatter.messages(current, TestFixtures.PAGE);
        assertThat(messages).extracting(TelegramMessage::text).singleElement().asString()
                .contains("weiterhin aktiv", TestFixtures.PAGE.toString(),
                        "Active 🎲\nMolding, Proofing Files\n\nPre-order active\nShipping")
                .doesNotContain("[Crowdfunding]", "[Pre-order]", "Finished", "Waiting", "Not started", "Completed", "2027 Q1");
        var message = messages.getFirst();
        assertThat(message.entities()).allSatisfy(entity -> assertThat(entity.type()).isEqualTo("bold"))
                .extracting(entity -> message.text().substring(entity.offset(), entity.offset() + entity.length()))
                .containsExactly("Active 🎲", "Pre-order active");
    }

    @Test
    void sendsActivityConfirmationEvenWhenNoProjectIsInProgress() {
        var snapshot = new SectionSnapshot("today", Map.of("Finished", Map.of("Shipping", "Completed")));

        assertThat(formatter.messages(Map.of(Section.CROWDFUNDING, snapshot, Section.PRE_ORDER, snapshot),
                TestFixtures.PAGE)).extracting(TelegramMessage::text).singleElement().asString()
                .contains("weiterhin aktiv", "keine Projekte in progress").doesNotContain("Finished");
    }

    @Test
    void splitsLongStatusIntoTelegramSizedPartsWithActivityConfirmationAndLink() {
        var snapshot = new SectionSnapshot("today", Map.of("Long 🎲".repeat(1000), Map.of("Shipping", "In progress")));

        assertThat(formatter.messages(Map.of(Section.CROWDFUNDING, snapshot, Section.PRE_ORDER, snapshot),
                TestFixtures.PAGE)).hasSizeGreaterThan(1).allSatisfy(part -> {
                    var message = part.text();
                    assertThat(message.length()).isLessThanOrEqualTo(4000);
                    assertThat(message).contains("weiterhin aktiv", TestFixtures.PAGE.toString());
                    assertThat(part.entities()).allSatisfy(entity -> {
                        assertThat(entity.type()).isEqualTo("bold");
                        assertThat(entity.offset()).isGreaterThanOrEqualTo(0);
                        assertThat(entity.length()).isPositive();
                        assertThat(entity.offset() + entity.length()).isLessThanOrEqualTo(message.length());
                        var title = message.substring(entity.offset(), entity.offset() + entity.length());
                        assertThat(title).doesNotContain("Shipping", "\n");
                        assertThat(Character.isLowSurrogate(title.charAt(0))).isFalse();
                        assertThat(Character.isHighSurrogate(title.charAt(title.length() - 1))).isFalse();
                    });
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
