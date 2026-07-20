package io.pigagent.core.skill;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.Toolkit;
import io.pigagent.core.agent.PigAgent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * native-skill-engine-bridge — the RED-LINE regression guard ("engine, not mouth"). Adopting the
 * native skill engine as a library MUST NOT re-open the native dynamic-skill prompt path: a
 * pig-built {@link PigAgent} keeps {@code disableDynamicSkills()}/{@code disableDefaultWorkspaceSkills()}
 * so NONE of the native skill tools ({@code load_skill_through_path}/{@code read_file}/{@code grep}/
 * {@code propose_skill}/{@code skill_manage}) ever enter the toolkit the model sees, and no native
 * dynamic-skill middleware is wired. This locks the invariant that S1 (which does not touch
 * {@code PigAgent}) preserves by construction.
 */
class NativeSkillRedLineGuardTest {

    /** Native skill tools the "mouth" path would register — MUST stay absent. */
    private static final Set<String> NATIVE_SKILL_TOOLS =
            Set.of("load_skill_through_path", "read_file", "grep", "propose_skill", "skill_manage");

    static final class TextOnlyModel implements Model {
        @Override public String getModelName() { return "fake-text"; }
        @Override public Flux<ChatResponse> stream(List<Msg> m, List<ToolSchema> t, GenerateOptions o) {
            return Flux.just(ChatResponse.builder()
                    .content(List.of(TextBlock.builder().text("ok").build())).finishReason("stop").build());
        }
    }

    /** A trivial pig-style tool so the toolkit is non-empty (paramless → no arg-binding concern). */
    static final class Probe {
        @Tool(name = "probeEcho", description = "echo")
        public String probeEcho() {
            return "echoed";
        }
    }

    @Test
    void pigAgent_exposesNoNativeSkillTools(@TempDir Path ws) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new Probe());

        PigAgent agent = PigAgent.builder()
                .name("guard").sysPrompt("sp").model(new TextOnlyModel())
                .toolkit(toolkit).workspace(ws)
                .build();

        Set<String> toolNames = agent.getReactAgent().getToolkit().getToolNames();
        assertThat(toolNames).as("pig's own tool is present").contains("probeEcho");
        assertThat(toolNames).as("the native dynamic-skill prompt-path tools MUST NOT be registered")
                .doesNotContainAnyElementsOf(NATIVE_SKILL_TOOLS);
        agent.close();
    }

    @Test
    void pigAgent_wiresNoNativeSkillMiddleware(@TempDir Path ws) {
        PigAgent agent = PigAgent.builder()
                .name("guard").sysPrompt("sp").model(new TextOnlyModel())
                .toolkit(new Toolkit()).workspace(ws)
                .build();

        boolean hasNativeSkillMiddleware = agent.getReactAgent().getMiddlewares().stream()
                .anyMatch(m -> {
                    String fqcn = m.getClass().getName();
                    return fqcn.startsWith("io.agentscope") && m.getClass().getSimpleName().contains("Skill");
                });
        assertThat(hasNativeSkillMiddleware)
                .as("no native dynamic-skill middleware is wired (disableDynamicSkills)").isFalse();
        agent.close();
    }
}
