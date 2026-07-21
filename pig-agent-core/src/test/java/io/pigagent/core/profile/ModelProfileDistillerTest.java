package io.pigagent.core.profile;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Offline coverage for {@link ModelProfileDistiller} (capability {@code user-profile}, M-E): when no
 * cheap model is resolvable (the {@code consolidation.model-id} → {@code memory.model-id} → primary
 * fallback all yield {@code null}), the live distiller MUST no-op — returning {@code null} before ever
 * building a throwaway agent, so the consolidation caller declines to write ({@code USER.md} untouched).
 */
class ModelProfileDistillerTest {

    @Test
    void distill_returnsNull_whenNoModelResolvable() {
        ModelProfileDistiller distiller = new ModelProfileDistiller(() -> null);

        assertThat(distiller.distill("current profile", "some memory")).isNull();
    }

    @Test
    void distill_returnsNull_whenModelSupplierBlank_forSeeding() {
        ModelProfileDistiller distiller = new ModelProfileDistiller(() -> null);

        // Empty current profile = the seeding branch; still a no-op with no model.
        assertThat(distiller.distill("", "memory with an identity")).isNull();
    }
}
