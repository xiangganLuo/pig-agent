package io.pigagent.core.memory.decay;

import io.pigagent.core.memory.quality.MemoryLayerFormat.Layer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link FactLayerClassifier} — the deterministic fact→layer classifier (M-C, D1).
 */
class FactLayerClassifierTest {

    private final FactLayerClassifier classifier = new FactLayerClassifier();

    @Test
    void classifiesChineseIdentityFactAsPinned() {
        // Arrange
        String fact = "- 我叫罗湘赣，请牢牢记住我的名字";

        // Act
        Layer layer = classifier.classify(fact);

        // Assert
        assertThat(layer).isEqualTo(Layer.PINNED);
    }

    @Test
    void classifiesEnglishCallMeFactAsPinned() {
        assertThat(classifier.classify("You can call me Alex")).isEqualTo(Layer.PINNED);
        assertThat(classifier.classify("my name is Sam")).isEqualTo(Layer.PINNED);
    }

    @Test
    void classifiesEpisodicChineseFactAsVolatile() {
        assertThat(classifier.classify("- 昨天临时开了一个测试端口")).isEqualTo(Layer.VOLATILE);
        assertThat(classifier.classify("- 这次先用暂时的配置")).isEqualTo(Layer.VOLATILE);
    }

    @Test
    void classifiesEpisodicEnglishFactAsVolatile() {
        assertThat(classifier.classify("Temporarily using port 8081 for now")).isEqualTo(Layer.VOLATILE);
        assertThat(classifier.classify("Just now the user asked about X")).isEqualTo(Layer.VOLATILE);
    }

    @Test
    void classifiesDurablePreferenceAsGeneral() {
        assertThat(classifier.classify("- 用户在杭州工作，主要用 Java")).isEqualTo(Layer.GENERAL);
        assertThat(classifier.classify("Prefers dark mode and concise answers")).isEqualTo(Layer.GENERAL);
    }

    @Test
    void identityWinsOverEpisodicWhenBothPresent() {
        // A name stated "just now" is still identity → PINNED, not VOLATILE.
        assertThat(classifier.classify("刚才他说我叫罗湘赣")).isEqualTo(Layer.PINNED);
    }

    @Test
    void nullOrBlankFactDefaultsToGeneral() {
        assertThat(classifier.classify(null)).isEqualTo(Layer.GENERAL);
        assertThat(classifier.classify("   ")).isEqualTo(Layer.GENERAL);
    }

    @Test
    void isCaseInsensitiveForLatinSignals() {
        assertThat(classifier.classify("MY NAME IS SAM")).isEqualTo(Layer.PINNED);
        assertThat(classifier.classify("TEMPORARY note")).isEqualTo(Layer.VOLATILE);
    }
}
