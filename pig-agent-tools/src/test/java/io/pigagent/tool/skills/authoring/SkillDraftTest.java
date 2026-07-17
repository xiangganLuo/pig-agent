package io.pigagent.tool.skills.authoring;

import io.pigagent.tool.skills.SkillManifest;
import io.pigagent.tool.skills.SkillManifestParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** SkillDraft: front-matter 渲染与 SkillManifestParser 往返一致；null 归一。 */
class SkillDraftTest {

    private final SkillManifestParser parser = SkillManifestParser.defaults();

    @Test
    void toSkillMd_roundTripsThroughParser() {
        SkillDraft draft = new SkillDraft("my-skill", "Do a useful thing",
                List.of("alpha", "beta"), "# My Skill\n\nStep 1. Do it.");

        SkillManifest m = parser.parse(draft.toSkillMd(), "fallback");

        assertThat(m.metadata().name()).isEqualTo("my-skill");
        assertThat(m.metadata().description()).isEqualTo("Do a useful thing");
        assertThat(m.metadata().keywords()).contains("alpha", "beta");
        assertThat(m.body().stripLeading()).startsWith("# My Skill");
    }

    @Test
    void toSkillMd_withoutKeywords_stillParses() {
        SkillDraft draft = new SkillDraft("s", "desc", List.of(), "# Body\ncontent");
        SkillManifest m = parser.parse(draft.toSkillMd(), "s");
        assertThat(m.metadata().name()).isEqualTo("s");
        assertThat(m.metadata().description()).isEqualTo("desc");
        assertThat(m.metadata().keywords()).isEmpty();
    }

    @Test
    void nullFieldsNormalized() {
        SkillDraft draft = new SkillDraft(null, null, null, null);
        assertThat(draft.name()).isEmpty();
        assertThat(draft.description()).isEmpty();
        assertThat(draft.keywords()).isEmpty();
        assertThat(draft.body()).isEmpty();
        // Rendering a fully-empty draft still produces a fenced front-matter (never throws).
        assertThat(draft.toSkillMd()).contains("---");
    }

    @Test
    void descriptionWithColon_isQuotedAndRoundTrips() {
        SkillDraft draft = new SkillDraft("s", "Fix: the bug", List.of(), "# B\nx");
        SkillManifest m = parser.parse(draft.toSkillMd(), "s");
        assertThat(m.metadata().description()).isEqualTo("Fix: the bug");
    }
}
