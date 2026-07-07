package io.pigagent.tool.availability;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Availability factories + reason normalization. */
class AvailabilityTest {

    @Test
    void availableHasNoReason() {
        Availability a = Availability.AVAILABLE;
        assertThat(a.available()).isTrue();
        assertThat(a.reason()).isNull();
    }

    @Test
    void unavailableKeepsReason() {
        Availability a = Availability.unavailable("BRAVE_API_KEY not set");
        assertThat(a.available()).isFalse();
        assertThat(a.reason()).isEqualTo("BRAVE_API_KEY not set");
    }

    @Test
    void unavailableWithBlankReasonIsNormalized() {
        assertThat(Availability.unavailable(null).reason()).isEqualTo("unavailable");
        assertThat(Availability.unavailable("   ").reason()).isEqualTo("unavailable");
    }

    @Test
    void availableIgnoresProvidedReason() {
        // Even if constructed directly, an available result carries no reason.
        assertThat(new Availability(true, "ignored").reason()).isNull();
    }
}
