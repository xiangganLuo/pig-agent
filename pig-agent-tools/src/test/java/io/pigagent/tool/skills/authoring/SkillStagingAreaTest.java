package io.pigagent.tool.skills.authoring;

import io.pigagent.tool.skills.Skill;
import io.pigagent.tool.skills.SkillLimits;
import io.pigagent.tool.skills.WorkspaceSkillSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** SkillStagingArea: staged drafts invisible until promoted; atomic promote; discard; name validation. */
class SkillStagingAreaTest {

    @TempDir
    Path skillsDir;

    private SkillStagingArea staging() {
        return new SkillStagingArea(skillsDir, ".pending", SkillLimits.defaults());
    }

    private static SkillDraft draft(String name) {
        return new SkillDraft(name, "desc for " + name, List.of("k"), "# " + name + "\n\nbody");
    }

    private List<String> workspaceNames() {
        return new WorkspaceSkillSource(skillsDir).discover().stream().map(Skill::name).toList();
    }

    @Test
    void stagedDraftIsNotVisibleToWorkspaceSourceUntilPromoted() throws IOException {
        SkillStagingArea s = staging();
        s.stage(draft("s1"));

        assertThat(Files.isRegularFile(skillsDir.resolve(".pending").resolve("s1").resolve("SKILL.md"))).isTrue();
        assertThat(Files.exists(skillsDir.resolve("s1"))).isFalse();
        assertThat(workspaceNames()).doesNotContain("s1");
        assertThat(s.list()).containsExactly("s1");
        assertThat(s.exists("s1")).isTrue();
        assertThat(s.read("s1")).isPresent();
    }

    @Test
    void promoteMovesDraftIntoSkillsAndMakesItDiscoverable() throws IOException {
        SkillStagingArea s = staging();
        s.stage(draft("s1"));

        s.promote("s1");

        assertThat(Files.isRegularFile(skillsDir.resolve("s1").resolve("SKILL.md"))).isTrue();
        assertThat(Files.exists(skillsDir.resolve(".pending").resolve("s1"))).isFalse();
        assertThat(workspaceNames()).contains("s1");
    }

    @Test
    void discardRemovesStagedDraft() throws IOException {
        SkillStagingArea s = staging();
        s.stage(draft("s1"));

        assertThat(s.discard("s1")).isTrue();
        assertThat(s.exists("s1")).isFalse();
        assertThat(s.discard("s1")).isFalse();  // already gone
    }

    @Test
    void stageRejectsInvalidName() {
        SkillStagingArea s = staging();
        assertThatThrownBy(() -> s.stage(draft("../evil"))).isInstanceOf(IOException.class);
    }

    @Test
    void promoteFailsWhenTargetExists() throws IOException {
        SkillStagingArea s = staging();
        Files.createDirectories(skillsDir.resolve("s1"));
        Files.writeString(skillsDir.resolve("s1").resolve("SKILL.md"), "# existing");
        s.stage(draft("s1"));

        assertThatThrownBy(() -> s.promote("s1")).isInstanceOf(IOException.class);
    }

    @Test
    void promoteFailsWhenNotStaged() {
        SkillStagingArea s = staging();
        assertThatThrownBy(() -> s.promote("nope")).isInstanceOf(IOException.class);
    }
}
