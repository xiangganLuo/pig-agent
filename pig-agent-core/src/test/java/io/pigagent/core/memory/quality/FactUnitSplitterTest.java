package io.pigagent.core.memory.quality;

import io.pigagent.core.memory.quality.FactUnitSplitter.Segment;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Splitting {@code MEMORY.md} into fact vs structural segments (headings/blanks kept verbatim). */
class FactUnitSplitterTest {

    @Test
    void headingsAndBlanksAreStructural_bulletsAndTextAreFacts() {
        List<Segment> segments = FactUnitSplitter.split("## Pinned\n- fact one\n\nplain paragraph\n# Big");
        assertThat(segments).extracting(Segment::fact)
                .containsExactly(false, true, false, true, false);
    }

    @Test
    void factTextsReturnsOnlyFactLinesInOrder() {
        List<Segment> segments = FactUnitSplitter.split("# H\n- a\n- b\n\n- c");
        assertThat(FactUnitSplitter.factTexts(segments)).containsExactly("- a", "- b", "- c");
    }

    @Test
    void emptyInputYieldsEmptyList() {
        assertThat(FactUnitSplitter.split(null)).isEmpty();
        assertThat(FactUnitSplitter.split("")).isEmpty();
    }

    @Test
    void sevenHashesIsNotAHeading_isFact() {
        // Markdown headings are #..###### (1-6); 7 hashes is not a heading, and a hash with no space isn't.
        List<Segment> segments = FactUnitSplitter.split("####### too many\n#nospace\n### real");
        assertThat(segments).extracting(Segment::fact).containsExactly(true, true, false);
    }
}
