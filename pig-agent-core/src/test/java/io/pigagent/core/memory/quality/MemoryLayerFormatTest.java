package io.pigagent.core.memory.quality;

import io.pigagent.core.memory.quality.MemoryLayerFormat.Layer;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The {@code MEMORY.md} layer format contract: parse into layers, back-compat with unstructured files. */
class MemoryLayerFormatTest {

    @Test
    void parsesStructuredLayers() {
        String md = "## Pinned\n- 我叫罗湘赣\n## General\n- 我在杭州工作\n## Recent\n- 今天聊了记忆能力";
        Map<Layer, String> layers = MemoryLayerFormat.parse(md);
        assertThat(layers.get(Layer.PINNED)).contains("罗湘赣");
        assertThat(layers.get(Layer.GENERAL)).contains("杭州");
        assertThat(layers.get(Layer.VOLATILE)).contains("记忆能力");
    }

    @Test
    void legacyUnstructuredFileIsSingleGeneralLayer() {
        // No layer headings → everything lands under GENERAL, no error (backward compatible).
        Map<Layer, String> layers = MemoryLayerFormat.parse("- fact one\n- fact two");
        assertThat(layers).containsOnlyKeys(Layer.GENERAL);
        assertThat(layers.get(Layer.GENERAL)).contains("fact one").contains("fact two");
    }

    @Test
    void contentBeforeFirstHeadingIsGeneral() {
        Map<Layer, String> layers = MemoryLayerFormat.parse("- early fact\n## Pinned\n- core fact");
        assertThat(layers.get(Layer.GENERAL)).contains("early fact");
        assertThat(layers.get(Layer.PINNED)).contains("core fact");
    }

    @Test
    void unknownHeadingIsTreatedAsContent_notALayerSwitch() {
        Map<Layer, String> layers = MemoryLayerFormat.parse("## Random\n- some fact");
        assertThat(layers).containsOnlyKeys(Layer.GENERAL);
        assertThat(layers.get(Layer.GENERAL)).contains("Random").contains("some fact");
    }

    @Test
    void layerForHeadingIsCaseInsensitive() {
        assertThat(MemoryLayerFormat.layerForHeading("pinned")).isEqualTo(Layer.PINNED);
        assertThat(MemoryLayerFormat.layerForHeading("RECENT")).isEqualTo(Layer.VOLATILE);
        assertThat(MemoryLayerFormat.layerForHeading("nope")).isNull();
    }

    @Test
    void nullInputYieldsEmptyMap() {
        assertThat(MemoryLayerFormat.parse(null)).isEmpty();
    }
}
