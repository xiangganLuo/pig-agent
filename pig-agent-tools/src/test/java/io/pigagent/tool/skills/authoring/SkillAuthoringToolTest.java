package io.pigagent.tool.skills.authoring;

import io.pigagent.tool.skills.Skill;
import io.pigagent.tool.skills.SkillLimits;
import io.pigagent.tool.skills.WorkspaceSkillSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** SkillAuthoringTool: proposeSkill stages (never installs), scan rejects secrets, skillManage, availability. */
class SkillAuthoringToolTest {

    @TempDir
    Path skillsDir;

    private SkillStagingArea staging() {
        return new SkillStagingArea(skillsDir, ".pending", SkillLimits.defaults());
    }

    private SkillAuthoringTool tool(SkillStagingArea s, boolean enabled) {
        return new SkillAuthoringTool(s, SkillContentScanner.defaults(), () -> enabled);
    }

    private List<String> workspaceNames() {
        return new WorkspaceSkillSource(skillsDir).discover().stream().map(Skill::name).toList();
    }

    @Test
    void proposeSkillStagesButNeverInstalls() {
        SkillStagingArea s = staging();
        String out = tool(s, true).proposeSkill("s1", "A skill", "# S1\nStep 1.", "a, b");

        assertThat(out).contains("staged");
        assertThat(s.exists("s1")).isTrue();
        // Fail-closed: the tool cannot install — the skill is NOT in the workspace / not discoverable.
        assertThat(Files.exists(skillsDir.resolve("s1"))).isFalse();
        assertThat(workspaceNames()).doesNotContain("s1");
    }

    @Test
    void proposeSkillRejectsCredentialContentWithoutStaging() {
        SkillStagingArea s = staging();
        String out = tool(s, true).proposeSkill("leaky", "desc", "# L\nkey sk-abcDEF1234567890", null);

        assertThat(out).contains("\"error\"");
        assertThat(out).doesNotContain("sk-abcDEF1234567890");  // secret not echoed
        assertThat(s.exists("leaky")).isFalse();  // not staged
    }

    @Test
    void skillManageListAndDiscard() {
        SkillStagingArea s = staging();
        SkillAuthoringTool t = tool(s, true);
        t.proposeSkill("s1", "desc", "# S1\nbody", null);

        assertThat(t.skillManage("list", null)).contains("s1");
        assertThat(t.skillManage("discard", "s1")).contains("Discarded");
        assertThat(s.exists("s1")).isFalse();
        assertThat(t.skillManage("list", null)).contains("No pending");
    }

    @Test
    void skillManageUnknownActionAndMissingNameReturnErrors() {
        SkillAuthoringTool t = tool(staging(), true);
        assertThat(t.skillManage("bogus", null)).contains("\"error\"");
        assertThat(t.skillManage("discard", null)).contains("\"error\"");
    }

    @Test
    void availabilityTracksEnabledFlag() {
        SkillAuthoringTool disabled = tool(staging(), false);
        assertThat(disabled.checkAvailability().available()).isFalse();

        SkillAuthoringTool enabled = tool(staging(), true);
        assertThat(enabled.checkAvailability().available()).isTrue();

        assertThat(enabled.availabilityToolNames()).containsExactlyInAnyOrder("proposeSkill", "skillManage");
    }
}
