package de.agiehl;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class MonitorPropertiesTest {

    @Test
    void missingOrEmptySelectionIncludesAllTitles() {
        assertThat(properties(null).isRelevantTitle("Mordred")).isTrue();
        assertThat(properties(List.of()).isRelevantTitle("Any title")).isTrue();
        assertThat(properties(Arrays.asList(null, "", " \t ")).isRelevantTitle("Any title")).isTrue();
    }

    @Test
    void matchesCompleteTitlesIgnoringCaseAndSurroundingWhitespace() {
        var properties = properties(List.of(" Mordred ", "DCeased", "Mordred", ""));

        assertThat(properties.interestedTitles()).containsExactly("Mordred", "DCeased");
        assertThat(properties.isRelevantTitle(" mordRED ")).isTrue();
        assertThat(properties.isRelevantTitle("dceased")).isTrue();
        assertThat(properties.isRelevantTitle("Mordred expansion")).isFalse();
        assertThat(properties.isRelevantTitle("The Dead Keep")).isFalse();
    }

    private MonitorProperties properties(List<String> titles) {
        return new MonitorProperties(null, null, null, null, titles);
    }
}
