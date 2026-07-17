package io.pigagent.tool.spi.providers;

import io.pigagent.tool.skills.SkillLimits;
import io.pigagent.tool.skills.authoring.SkillAuthoringTool;
import io.pigagent.tool.skills.authoring.SkillStagingArea;
import io.pigagent.tool.spi.ToolContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** SkillAuthoringToolProvider registers the tool only when the staging area is wired (feature on). */
class SkillAuthoringToolProviderTest {

    @TempDir
    Path skillsDir;

    @Test
    void returnsNullWhenNoStagingArea() {
        // Default ToolContext has no staging area (autonomous skills not wired).
        ToolContext ctx = new ToolContext(null, skillsDir);
        assertThat(new SkillAuthoringToolProvider().create(ctx)).isNull();
    }

    @Test
    void returnsToolWhenStagingWired() {
        SkillStagingArea staging = new SkillStagingArea(skillsDir, ".pending", SkillLimits.defaults());
        ToolContext ctx = new ToolContext(null, skillsDir, skillsDir, java.util.List.of(), null,
                null, () -> false, staging, () -> true);
        assertThat(new SkillAuthoringToolProvider().create(ctx)).isInstanceOf(SkillAuthoringTool.class);
    }
}
