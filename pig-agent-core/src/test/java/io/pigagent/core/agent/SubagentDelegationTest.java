package io.pigagent.core.agent;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * av2 Phase 6a/6b — native subagent delegation on the {@link PigAgent}/{@code HarnessAgent} vehicle.
 * Proves: (1) the orchestration toolset is registered when enabled and absent when disabled;
 * (2) a spawned built-in {@code general-purpose} child actually runs and its result returns to the
 * parent; (3) <b>Phase-6b</b> — the parent's permission DENY rules are <em>inherited</em> by the
 * spawned child (a delegated task cannot exceed the parent's authority): pig builds the child under a
 * fail-closed context derived from the parent, so a child spawned by a parent that DENies a tool
 * <em>cannot</em> execute it. Fully offline — scripted fake models + {@code @TempDir}.
 */
class SubagentDelegationTest {

    private static final String GENERAL_PURPOSE = "general-purpose";
    private static final String MARKER = "SUBTASK_MARKER";

    private static Msg user(String text) {
        return Msg.builder().name("user").role(MsgRole.USER)
                .content(TextBlock.builder().text(text).build()).build();
    }

    private static boolean userMessageContains(List<Msg> msgs, String needle) {
        for (Msg m : msgs) {
            if (m.getRole() == MsgRole.USER && textOf(m).contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasToolResult(List<Msg> msgs) {
        for (Msg m : msgs) {
            for (ContentBlock b : m.getContent()) {
                if (b instanceof ToolResultBlock) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String textOf(Msg m) {
        StringBuilder sb = new StringBuilder();
        for (ContentBlock b : m.getContent()) {
            if (b instanceof TextBlock t) {
                sb.append(t.getText());
            }
        }
        return sb.toString();
    }

    private static ChatResponse text(String s) {
        return ChatResponse.builder()
                .content(List.of(TextBlock.builder().text(s).build())).finishReason("stop").build();
    }

    private static ChatResponse toolCall(String id, String name, Map<String, Object> input) {
        // Set BOTH the parsed input map AND the raw-args JSON `content` — the ReAct loop reconstructs
        // tool arguments from the raw content, so a fake model must supply it (real models do).
        return ChatResponse.builder()
                .content(List.of(ToolUseBlock.builder()
                        .id(id).name(name).input(input).content(json(input)).build()))
                .finishReason("tool_calls").build();
    }

    /** Minimal JSON object for the fake model's tool-call arguments (simple string/number values). */
    private static String json(Map<String, Object> input) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : input.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(e.getKey()).append("\":");
            Object v = e.getValue();
            if (v instanceof Number || v instanceof Boolean) {
                sb.append(v);
            } else {
                sb.append('"').append(String.valueOf(v).replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
            }
        }
        return sb.append('}').toString();
    }

    /** Fake model: always answers with a fixed text (for the toggle tests that never run a turn). */
    static final class TextModel implements Model {
        @Override public String getModelName() { return "fake-text"; }
        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.just(text("ok"));
        }
    }

    // ---- A/B: the orchestration toolset toggles with .subagents(...) ----------------------------

    @Test
    void subagentToolsRegisteredWhenEnabled(@TempDir Path ws) {
        PigAgent agent = PigAgent.builder()
                .name("orch").sysPrompt("sp").model(new TextModel())
                .toolkit(new Toolkit())
                .workspace(ws)
                .subagents(true)
                .build();

        assertThat(agent.getReactAgent().getToolkit().getToolNames())
                .as("enabling subagents registers the native orchestration toolset")
                .contains("agent_spawn", "agent_send", "agent_list", "task_output", "task_cancel", "task_list");
        agent.close();
    }

    @Test
    void subagentToolsAbsentWhenDisabled(@TempDir Path ws) {
        PigAgent agent = PigAgent.builder()
                .name("orch").sysPrompt("sp").model(new TextModel())
                .toolkit(new Toolkit())
                .workspace(ws)
                .build(); // default: subagents disabled → today's behavior exactly

        assertThat(agent.getReactAgent().getToolkit().getToolNames())
                .as("with subagents disabled the schema carries no orchestration tools")
                .doesNotContain("agent_spawn", "agent_list", "task_list");
        agent.close();
    }

    // ---- C: a spawned general-purpose child runs, and its result returns to the parent ----------

    /** Parent spawns general-purpose; child answers; parent finishes. Same model serves both. */
    static final class SpawnScriptModel implements Model {
        final List<List<Msg>> captured = new ArrayList<>();
        final AtomicInteger childTurns = new AtomicInteger();

        @Override public String getModelName() { return "fake-spawn"; }

        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            captured.add(new ArrayList<>(messages));
            boolean child = userMessageContains(messages, MARKER);
            boolean toolResult = hasToolResult(messages);
            if (child) {
                childTurns.incrementAndGet();
                return Flux.just(text("CHILD_RESULT delivered"));
            }
            if (toolResult) {
                return Flux.just(text("PARENT_FINAL after delegation"));
            }
            return Flux.just(toolCall("call-spawn", "agent_spawn",
                    Map.of("agent_id", GENERAL_PURPOSE, "task", MARKER + " summarize the thing")));
        }
    }

    @Test
    void spawnedGeneralPurposeChildRunsAndResultReturnsToParent(@TempDir Path ws) {
        SpawnScriptModel model = new SpawnScriptModel();
        PigAgent agent = PigAgent.builder()
                .name("orch").sysPrompt("sp").model(model)
                .toolkit(new Toolkit())
                .workspace(ws)
                .maxIters(8)
                .subagents(true)
                .build();

        agent.stream(user("delegate the subtask to a helper")).blockLast();

        // The general-purpose child actually ran (it was invoked with the delegated task).
        assertThat(model.childTurns.get())
                .as("the spawned general-purpose child ran the delegated task").isGreaterThanOrEqualTo(1);
        // The child's result came back to the parent as a tool result (returned to the parent turn).
        boolean childResultReturnedToParent = model.captured.stream()
                .filter(SubagentDelegationTest::hasToolResult)
                .anyMatch(msgs -> allText(msgs).contains("CHILD_RESULT"));
        assertThat(childResultReturnedToParent)
                .as("the child's result was returned to the parent as a tool result").isTrue();
        agent.close();
    }

    // ---- D: Phase-6b — pig ENFORCES parent→child permission inheritance ---------------------------
    //
    // 2.0.0's SubagentDeclaration.inheritParentPermissions is declared but INERT (javap: no harness
    // class reads it), so a natively-built child would run under its own permissive context and could
    // execute a tool the parent denied — a permission-escape. pig closes it by building every spawnable
    // child itself (HarnessAgent.Builder.subagentFactory) under a fail-closed context derived from the
    // parent (SubagentPermissions.deriveChildContext), so the parent's DENY binds the child. This is
    // the genuine spawn-path proof: a real general-purpose child is spawned and its attempt to run the
    // parent-denied tool is refused (the tool body is never invoked).

    static final class SecretTool {
        final AtomicInteger invoked = new AtomicInteger();

        @Tool(name = "secretTool", description = "A sensitive tool the parent denies.")
        public String secretTool() {
            invoked.incrementAndGet();
            return "secret";
        }
    }

    /** Parent spawns general-purpose asking it to call secretTool; the child attempts it. */
    static final class DenyScriptModel implements Model {
        final AtomicInteger childToolCallEmitted = new AtomicInteger();

        @Override public String getModelName() { return "fake-deny"; }

        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            boolean child = userMessageContains(messages, MARKER);
            boolean toolResult = hasToolResult(messages);
            if (child && !toolResult) {
                childToolCallEmitted.incrementAndGet();
                return Flux.just(toolCall("call-secret", "secretTool", Map.of()));
            }
            if (child) {
                return Flux.just(text("child done (was denied)"));
            }
            if (toolResult) {
                return Flux.just(text("parent done"));
            }
            return Flux.just(toolCall("call-spawn", "agent_spawn",
                    Map.of("agent_id", GENERAL_PURPOSE, "task", MARKER + " call secretTool")));
        }
    }

    @Test
    void spawnedChildInheritsParentDenyRule_andCannotRunDeniedTool(@TempDir Path ws) {
        SecretTool secret = new SecretTool();
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(secret);

        // Deny secretTool but ALLOW the parent to spawn (else agent_spawn itself would fail-close under
        // DEFAULT with no confirmer, and the child would never run). The child inherits the DENY (the
        // ALLOW for agent_spawn is irrelevant to the child — it is a leaf and cannot spawn).
        PermissionContextState denyCtx = PermissionContextState.builder()
                .mode(PermissionMode.DEFAULT)
                .addAllowRule("agent_spawn",
                        new PermissionRule("agent_spawn", null, PermissionBehavior.ALLOW, "test"))
                .addDenyRule("secretTool",
                        new PermissionRule("secretTool", null, PermissionBehavior.DENY, "test"))
                .build();

        DenyScriptModel model = new DenyScriptModel();
        PigAgent agent = PigAgent.builder()
                .name("orch").sysPrompt("sp").model(model)
                .toolkit(toolkit)
                .permissionContext(denyCtx)
                .workspace(ws)
                .maxIters(8)
                .subagents(true)
                .build();

        agent.stream(user("delegate a task that will try secretTool")).blockLast();

        // The child DID attempt the tool (so the assertion below is meaningful, not vacuous).
        assertThat(model.childToolCallEmitted.get())
                .as("the spawned child attempted the tool").isGreaterThanOrEqualTo(1);
        // Phase-6b: the parent's DENY rule IS inherited — the child could NOT execute the denied tool.
        assertThat(secret.invoked.get())
                .as("the spawned child inherits the parent's DENY and never runs the denied tool")
                .isZero();
        agent.close();
    }

    private static String allText(List<Msg> msgs) {
        StringBuilder sb = new StringBuilder();
        for (Msg m : msgs) {
            for (ContentBlock b : m.getContent()) {
                appendBlockText(sb, b);
            }
        }
        return sb.toString();
    }

    private static void appendBlockText(StringBuilder sb, ContentBlock b) {
        if (b instanceof TextBlock t) {
            sb.append(t.getText()).append('\n');
        } else if (b instanceof ToolResultBlock tr) {
            for (ContentBlock inner : tr.getOutput()) {
                appendBlockText(sb, inner);
            }
        }
    }
}
