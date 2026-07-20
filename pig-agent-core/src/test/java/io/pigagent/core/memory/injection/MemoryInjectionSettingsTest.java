package io.pigagent.core.memory.injection;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Value-object coverage for {@link MemoryInjectionSettings} (capability {@code memory-retrieval-injection}):
 * safe defaults and clamping/normalization of out-of-range inputs.
 */
class MemoryInjectionSettingsTest {

    @Test
    void defaults_areDisabledAndSafe() {
        MemoryInjectionSettings s = MemoryInjectionSettings.defaults();

        assertThat(s.enabled()).isFalse();
        assertThat(s.topK()).isEqualTo(6);
        assertThat(s.pinnedSource()).isEqualTo(PinnedSource.HEADING);
        assertThat(s.pinnedHeading()).isEqualTo("Pinned");
        assertThat(s.pinnedMaxChars()).isEqualTo(800);
    }

    @Test
    void clampsTopKAndMaxChars() {
        MemoryInjectionSettings s = new MemoryInjectionSettings(true, 0, PinnedSource.HEADING, "", -5);

        assertThat(s.topK()).isEqualTo(1);          // clamped ≥ 1
        assertThat(s.pinnedMaxChars()).isEqualTo(0); // clamped ≥ 0
        assertThat(s.pinnedHeading()).isEqualTo("Pinned"); // blank → default
    }

    @Test
    void nullSource_defaultsToHeading() {
        MemoryInjectionSettings s = new MemoryInjectionSettings(true, 3, null, "Core", 100);

        assertThat(s.pinnedSource()).isEqualTo(PinnedSource.HEADING);
        assertThat(s.pinnedHeading()).isEqualTo("Core");
    }

    @Test
    void pinnedSource_fromConfig_isCaseInsensitiveWithHeadingFallback() {
        assertThat(PinnedSource.fromConfig("head")).isEqualTo(PinnedSource.HEAD);
        assertThat(PinnedSource.fromConfig("HEADING")).isEqualTo(PinnedSource.HEADING);
        assertThat(PinnedSource.fromConfig("nonsense")).isEqualTo(PinnedSource.HEADING);
        assertThat(PinnedSource.fromConfig(null)).isEqualTo(PinnedSource.HEADING);
    }
}
