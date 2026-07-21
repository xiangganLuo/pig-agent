package io.pigagent.tool.skills;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SkillSearchTool}: the independent {@code skill_search} tool ranks the shared
 * {@link SkillRegistry} via the kernel retrieval primitives, formats like {@code listSkills}, and
 * falls back to the flat listing on a blank query / no match (never errors, never empty).
 */
class SkillSearchToolTest {

    /** A skill whose cheap metadata carries description + keywords; body access fails the test. */
    private static Skill skill(String name, String description, String... keywords) {
        return new Skill() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String content() {
                throw new AssertionError("skill_search ranking must not read the body: " + name);
            }

            @Override
            public SkillMetadata metadata() {
                return new SkillMetadata(name, description, List.of(keywords), "");
            }
        };
    }

    private static SkillRegistry registryOf(Skill... skills) {
        return new SkillRegistry(List.of(() -> List.of(skills)));
    }

    @Test
    void skillSearch_ranksMostRelevantFirst() {
        SkillRegistry reg = registryOf(
                skill("systematic-debugging", "Debug a crash or failing test", "debug", "crash"),
                skill("git-commit", "Write a conventional commit message", "commit"),
                skill("security-review", "Audit code for vulnerabilities", "security"));
        SkillSearchTool tool = new SkillSearchTool(reg);

        String out = tool.skillSearch("debug crash");

        // The debugging skill is returned and appears first; unrelated ones are not in the (matched) set.
        String firstLine = out.split("\n")[0];
        assertThat(firstLine).isEqualTo("- systematic-debugging — Debug a crash or failing test");
        assertThat(out).doesNotContain("git-commit").doesNotContain("security-review");
    }

    @Test
    void skillSearch_chineseQueryMatchesChineseSkill() {
        SkillRegistry reg = registryOf(
                skill("调试排查", "系统化排查崩溃与失败", "调试"),
                skill("代码评审", "审查代码质量", "评审"));
        SkillSearchTool tool = new SkillSearchTool(reg);

        String out = tool.skillSearch("调试");

        assertThat(out).contains("调试排查");
        assertThat(out).doesNotContain("代码评审");
    }

    @Test
    void skillSearch_respectsTopK() {
        SkillRegistry reg = registryOf(
                skill("a", "test one", "test"),
                skill("b", "test two", "test"),
                skill("c", "test three", "test"));
        SkillSearchTool tool = new SkillSearchTool(reg, 2, 0.0);

        String out = tool.skillSearch("test");

        assertThat(out.split("\n")).hasSize(2);
    }

    @Test
    void skillSearch_blankQuery_fallsBackToFlatListingSortedByName() {
        SkillRegistry reg = registryOf(
                skill("tdd", "write tests"),
                skill("code-review", "review code"));
        SkillSearchTool tool = new SkillSearchTool(reg);

        String out = tool.skillSearch("   ");

        // Flat listing, sorted by name, same "- name — desc" format as listSkills.
        assertThat(out).isEqualTo("- code-review — review code\n- tdd — write tests");
    }

    @Test
    void skillSearch_noMatch_fallsBackToFlatListing() {
        SkillRegistry reg = registryOf(
                skill("tdd", "write tests"),
                skill("code-review", "review code"));
        SkillSearchTool tool = new SkillSearchTool(reg);

        String out = tool.skillSearch("zzzznonexistentquery");

        // No in-vocabulary match → all skills listed (never empty, never an error).
        assertThat(out).isEqualTo("- code-review — review code\n- tdd — write tests");
    }

    @Test
    void skillSearch_emptyRegistry_returnsNoSkillsMessage() {
        SkillSearchTool tool = new SkillSearchTool(new SkillRegistry(List.of()));

        assertThat(tool.skillSearch("anything")).isEqualTo("No skills found.");
    }

    @Test
    void skillSearch_descriptionlessSkill_formatsBareName() {
        SkillRegistry reg = registryOf(new FixedSkill("bare", "# body"));
        SkillSearchTool tool = new SkillSearchTool(reg);

        // FixedSkill has no metadata description → bare "- name", matching listSkills semantics.
        assertThat(tool.skillSearch("")).isEqualTo("- bare");
    }

    @Test
    void sharesRegistryWithSkillsTool() {
        // skill_search must rank the SAME set SkillsTool lists (shared registry, no divergence).
        SkillRegistry reg = registryOf(skill("planning", "plan the work", "plan"));
        SkillsTool skillsTool = new SkillsTool(reg);
        SkillSearchTool searchTool = new SkillSearchTool(skillsTool.registry());

        assertThat(searchTool.skillSearch("plan")).isEqualTo("- planning — plan the work");
        assertThat(skillsTool.registry()).isSameAs(reg);
    }
}
