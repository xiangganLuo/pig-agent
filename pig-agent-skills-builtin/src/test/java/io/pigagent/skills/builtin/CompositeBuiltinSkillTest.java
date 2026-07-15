package io.pigagent.skills.builtin;

import io.pigagent.tool.skills.Skill;
import io.pigagent.tool.skills.SkillMetadata;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Composite-skill enrichment of the built-ins: every curated {@code SKILL.md} now carries YAML
 * front-matter, so {@link Skill#metadata()} surfaces a non-blank description + keywords for cheap
 * listing, while {@link Skill#content()} strips the front-matter and still starts with the Markdown
 * title (no {@code content()} regression).
 */
class CompositeBuiltinSkillTest {

    @Test
    void everyBuiltinSkill_hasFrontMatterMetadata() {
        // Act / Assert
        for (Skill skill : SkillCatalog.all()) {
            SkillMetadata meta = skill.metadata();
            assertThat(meta.name()).as("name of %s", skill.name()).isEqualTo(skill.name());
            assertThat(meta.description()).as("description of %s", skill.name()).isNotBlank();
            assertThat(meta.keywords()).as("keywords of %s", skill.name()).isNotEmpty();
            assertThat(meta.version()).as("version of %s", skill.name()).isNotBlank();
        }
    }

    @Test
    void everyBuiltinSkill_contentStripsFrontMatter_startsWithTitle() throws IOException {
        // Act / Assert — front-matter removed, body still a Markdown guide
        for (Skill skill : SkillCatalog.all()) {
            String content = skill.content();
            assertThat(content).as("%s body has no front-matter fence", skill.name())
                    .doesNotStartWith("---");
            assertThat(content.stripLeading()).as("%s body starts with a title", skill.name())
                    .startsWith("# ");
        }
    }
}
