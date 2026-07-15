package io.pigagent.core.memory.extraction;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Merge/supersede by subject: dedup, correction wins, higher-confidence wins, position preserved. */
class FactMergerTest {

    private final FactMerger merger = new FactMerger();

    private static ExtractedFact fact(String subject, String stmt, double conf, boolean correction) {
        return new ExtractedFact(subject, FactCategory.PROJECT_FACT, stmt, conf, correction);
    }

    @Test
    void correctionSupersedesStaleFactBySubject() {
        List<ExtractedFact> existing = List.of(fact("language", "English", 0.9, false));
        List<ExtractedFact> incoming = List.of(
                new ExtractedFact("language", FactCategory.USER_PREFERENCE, "Chinese", 1.0, true));

        List<ExtractedFact> merged = merger.merge(existing, incoming);

        assertThat(merged).hasSize(1);
        assertThat(merged.get(0).statement()).isEqualTo("Chinese");
        assertThat(merged.get(0).correction()).isTrue();
    }

    @Test
    void dedupsBySubject_incomingReplacesSameSubject() {
        List<ExtractedFact> existing = List.of(fact("build-tool", "Gradle", 0.8, false));
        List<ExtractedFact> incoming = List.of(fact("build-tool", "Maven", 0.9, false));

        List<ExtractedFact> merged = merger.merge(existing, incoming);

        assertThat(merged).hasSize(1);
        assertThat(merged.get(0).statement()).isEqualTo("Maven");
    }

    @Test
    void keepsDistinctSubjects_andPreservesExistingPosition() {
        List<ExtractedFact> existing = List.of(
                fact("a", "A0", 0.8, false), fact("b", "B0", 0.8, false));
        List<ExtractedFact> incoming = List.of(
                fact("b", "B1", 0.9, false), fact("c", "C0", 0.9, false));

        List<ExtractedFact> merged = merger.merge(existing, incoming);

        assertThat(merged).extracting(ExtractedFact::subject).containsExactly("a", "b", "c");
        assertThat(merged.get(1).statement()).isEqualTo("B1"); // superseded in place
    }

    @Test
    void withinIncomingBatch_correctionBeatsNonCorrection() {
        List<ExtractedFact> incoming = List.of(
                fact("x", "normal", 0.95, false),
                new ExtractedFact("x", FactCategory.PROJECT_FACT, "corrected", 1.0, true));

        List<ExtractedFact> merged = merger.merge(List.of(), incoming);

        assertThat(merged).hasSize(1);
        assertThat(merged.get(0).statement()).isEqualTo("corrected");
    }

    @Test
    void caseInsensitiveSubjectDedup() {
        List<ExtractedFact> merged = merger.merge(
                List.of(fact("Language", "English", 0.9, false)),
                List.of(fact("language", "Chinese", 0.9, false)));
        assertThat(merged).hasSize(1);
        assertThat(merged.get(0).statement()).isEqualTo("Chinese");
    }

    @Test
    void priorCorrectionSurvivesLowerNormalReextraction() {
        // Existing corrected value must not be downgraded by a plain re-extraction.
        List<ExtractedFact> existing = List.of(fact("tz", "UTC+8", 1.0, true));
        List<ExtractedFact> incoming = List.of(fact("tz", "UTC", 0.7, false));

        List<ExtractedFact> merged = merger.merge(existing, incoming);

        assertThat(merged.get(0).statement()).isEqualTo("UTC+8");
        assertThat(merged.get(0).correction()).isTrue();
    }

    @Test
    void nullsAreHandled() {
        assertThat(merger.merge(null, null)).isEmpty();
        assertThat(merger.merge(null, List.of(fact("a", "A", 0.9, false)))).hasSize(1);
    }
}
