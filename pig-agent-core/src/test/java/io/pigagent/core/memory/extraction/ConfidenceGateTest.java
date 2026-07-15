package io.pigagent.core.memory.extraction;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Confidence gate: keep >= threshold, drop below, corrections bypass the threshold. */
class ConfidenceGateTest {

    private final ConfidenceGate gate = new ConfidenceGate();

    private static ExtractedFact fact(String subject, double conf, boolean correction) {
        return new ExtractedFact(subject, FactCategory.PROJECT_FACT, "stmt-" + subject, conf, correction);
    }

    @Test
    void keepsHighConfidence_dropsLow() {
        List<ExtractedFact> kept = gate.gate(
                List.of(fact("a", 0.9, false), fact("b", 0.5, false)), 0.7);
        assertThat(kept).extracting(ExtractedFact::subject).containsExactly("a");
    }

    @Test
    void thresholdIsConfigurable() {
        List<ExtractedFact> in = List.of(fact("a", 0.75, false));
        assertThat(gate.gate(in, 0.7)).hasSize(1);
        assertThat(gate.gate(in, 0.8)).isEmpty();
    }

    @Test
    void atThresholdIsKept() {
        assertThat(gate.gate(List.of(fact("a", 0.7, false)), 0.7)).hasSize(1);
    }

    @Test
    void correctionBypassesThresholdAndIsNormalizedHigh() {
        // A correction with a low raw confidence still survives the gate.
        List<ExtractedFact> kept = gate.gate(List.of(fact("lang", 0.1, true)), 0.7);
        assertThat(kept).hasSize(1);
        assertThat(kept.get(0).correction()).isTrue();
        assertThat(kept.get(0).confidence()).isEqualTo(ExtractedFact.CORRECTION_CONFIDENCE);
    }

    @Test
    void dropsBlankAndHandlesNull() {
        assertThat(gate.gate(null, 0.7)).isEmpty();
        assertThat(gate.gate(List.of(new ExtractedFact("", FactCategory.REFERENCE, "", 0.99, false)), 0.7))
                .isEmpty();
    }
}
