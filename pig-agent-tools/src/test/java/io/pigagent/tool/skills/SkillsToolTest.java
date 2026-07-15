package io.pigagent.tool.skills;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SkillsTool}: the {@code @Tool} surface delegates to a {@link SkillRegistry} while keeping the
 * original names / signatures / return semantics (list formatting, not-found, read-error, and the
 * workspace-overrides-built-in behaviour visible through the tool).
 */
class SkillsToolTest {

    @Test
    void listSkills_formatsNamesOnePerLine() {
        // Arrange — one built-in-like source + one workspace-like source
        SkillSource builtin = () -> List.of(new FixedSkill("tdd", "b"), new FixedSkill("planning", "b"));
        SkillSource workspace = () -> List.of(new FixedSkill("code-review", "w"));
        SkillsTool tool = new SkillsTool(new SkillRegistry(List.of(workspace, builtin)));

        // Act
        String out = tool.listSkills();

        // Assert — merged, deduped, sorted, "- name" per line
        assertThat(out).isEqualTo("- code-review\n- planning\n- tdd");
    }

    @Test
    void listSkills_noSkills_returnsMessage() {
        SkillsTool tool = new SkillsTool(new SkillRegistry(List.of()));

        assertThat(tool.listSkills()).isEqualTo("No skills found.");
    }

    @Test
    void loadSkill_returnsBuiltinContent() {
        SkillSource builtin = () -> List.of(new FixedSkill("tdd", "# TDD guide"));
        SkillsTool tool = new SkillsTool(new SkillRegistry(List.of(builtin)));

        assertThat(tool.loadSkill("tdd")).isEqualTo("# TDD guide");
    }

    @Test
    void loadSkill_unknownName_returnsNotFound() {
        SkillsTool tool = new SkillsTool(new SkillRegistry(List.of(() -> List.of(new FixedSkill("a", "x")))));

        assertThat(tool.loadSkill("missing")).isEqualTo("Skill not found: missing");
    }

    @Test
    void loadSkill_workspaceOverridesBuiltin() {
        // Arrange — same name in both; workspace source listed first (higher priority)
        SkillSource builtin = () -> List.of(new FixedSkill("code-review", "BUILTIN VERSION"));
        SkillSource workspace = () -> List.of(new FixedSkill("code-review", "WORKSPACE VERSION"));
        SkillsTool tool = new SkillsTool(new SkillRegistry(List.of(workspace, builtin)));

        // Act / Assert — workspace shadows built-in, and it appears once in the listing
        assertThat(tool.loadSkill("code-review")).isEqualTo("WORKSPACE VERSION");
        assertThat(tool.listSkills()).isEqualTo("- code-review");
    }

    @Test
    void loadSkill_readError_returnsErrorMessage() {
        // Arrange — a skill whose content() fails
        Skill failing = new Skill() {
            @Override
            public String name() {
                return "broken";
            }

            @Override
            public String content() throws IOException {
                throw new IOException("disk gone");
            }
        };
        SkillsTool tool = new SkillsTool(new SkillRegistry(List.of(() -> List.of(failing))));

        // Act / Assert — never throws to the model; friendly "Error:" message
        assertThat(tool.loadSkill("broken")).isEqualTo("Error: disk gone");
    }
}
