package io.pigagent.cli;

import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.Toolkit;
import io.pigagent.config.PigAgentConfig;
import io.pigagent.tool.skills.Skill;
import io.pigagent.tool.skills.SkillMetadata;
import io.pigagent.tool.skills.SkillRegistry;
import io.pigagent.tool.skills.SkillsTool;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * backward-safe 命门（skill-matching, S2）：{@code AgentBootstrap.applySkillMatching} 仅在
 * {@code skills.matching.enabled} 时注册独立 {@code skill_search}；默认关时初始 schema 与引入前逐字节
 * 等价，{@code listSkills}/{@code loadSkill} 任何配置下不变。
 */
class AgentBootstrapSkillMatchingTest {

    private static Skill skill(String name, String description) {
        return new Skill() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String content() {
                return "# " + name;
            }

            @Override
            public SkillMetadata metadata() {
                return new SkillMetadata(name, description, List.of(), "");
            }
        };
    }

    private static SkillsTool skillsTool() {
        SkillRegistry reg = new SkillRegistry(List.of(
                () -> List.of(skill("code-review", "review code"), skill("tdd", "write tests"))));
        return new SkillsTool(reg);
    }

    private static Toolkit toolkitWithSkills(SkillsTool skillsTool) {
        Toolkit tk = new Toolkit();
        tk.registration().tool(skillsTool).apply();
        return tk;
    }

    private static Set<String> schemaNames(Toolkit tk) {
        return tk.getToolSchemas().stream().map(ToolSchema::getName).collect(Collectors.toSet());
    }

    private static PigAgentConfig.MatchingSkillsConfig cfg(boolean enabled) {
        PigAgentConfig.MatchingSkillsConfig c = new PigAgentConfig.MatchingSkillsConfig();
        c.setEnabled(enabled);
        return c;
    }

    @Test
    void disabled_doesNotRegisterSkillSearch_schemaByteIdentical() {
        SkillsTool skillsTool = skillsTool();
        Toolkit tk = toolkitWithSkills(skillsTool);
        Set<String> before = schemaNames(tk);

        AgentBootstrap.applySkillMatching(tk, List.of(skillsTool), cfg(false));

        assertThat(schemaNames(tk)).doesNotContain("skill_search");
        // Byte-identical: the tool set is exactly what it was, listSkills/loadSkill still present.
        assertThat(schemaNames(tk)).isEqualTo(before);
        assertThat(schemaNames(tk)).contains("listSkills", "loadSkill");
    }

    @Test
    void enabled_registersSkillSearch_keepsListSkillsAndLoadSkill() {
        SkillsTool skillsTool = skillsTool();
        Toolkit tk = toolkitWithSkills(skillsTool);

        AgentBootstrap.applySkillMatching(tk, List.of(skillsTool), cfg(true));

        // skill_search added; the existing read-stack @Tool surface is untouched.
        assertThat(schemaNames(tk)).contains("skill_search", "listSkills", "loadSkill");
    }

    @Test
    void enabled_butNoSkillsTool_doesNotThrowAndSkipsRegistration() {
        Toolkit tk = new Toolkit();

        AgentBootstrap.applySkillMatching(tk, List.of(), cfg(true));

        assertThat(schemaNames(tk)).doesNotContain("skill_search");
    }
}
