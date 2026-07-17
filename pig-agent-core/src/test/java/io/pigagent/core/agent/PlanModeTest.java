package io.pigagent.core.agent;

import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.ConfirmResult;
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
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * av2 native Plan Mode on the {@link PigAgent}/{@code HarnessAgent} vehicle. Proves, fully offline
 * (scripted fake models + {@code @TempDir}): the plan trio is registered only when enabled; the
 * programmatic enter/exit/status API works; the read-only guarantee is enforced during the plan phase
 * (a mutating tool is denied while a read-only one is allowed) — the crux is that native Plan Mode
 * resolves read-only via {@code AgentTool.isReadOnly()}, which pig's tools report correctly with zero
 * per-tool changes; {@code plan_write} lands a {@code PLAN.md}; and {@code plan_exit} is HITL-gated
 * (surfaces {@link RequireUserConfirmEvent}) — approve exits, reject stays.
 */
class PlanModeTest {

    private static final String PLAN_TEXT = "PLAN: 1) inspect 2) refactor 3) test";
    /** Plan Mode's programmatic API keys off (userId,sessionId); the REPL always supplies a session. */
    private static final String SID = "s1";

    private static PlanModeSettings enabled(String dir) {
        return new PlanModeSettings(true, dir, false);
    }

    private static Msg user(String text) {
        return Msg.builder().name("user").role(MsgRole.USER)
                .content(TextBlock.builder().text(text).build()).build();
    }

    private static ChatResponse text(String s) {
        return ChatResponse.builder()
                .content(List.of(TextBlock.builder().text(s).build())).finishReason("stop").build();
    }

    private static ChatResponse toolCall(String id, String name, Map<String, Object> input) {
        return ChatResponse.builder()
                .content(List.of(ToolUseBlock.builder()
                        .id(id).name(name).input(input).content(json(input)).build()))
                .finishReason("tool_calls").build();
    }

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

    /** Count tool-result blocks across the conversation so a fake model can advance deterministically. */
    private static int toolResultCount(List<Msg> msgs) {
        int n = 0;
        for (Msg m : msgs) {
            for (ContentBlock b : m.getContent()) {
                if (b instanceof ToolResultBlock) {
                    n++;
                }
            }
        }
        return n;
    }

    // ---- Tools: one read-only, one mutating (default readOnly=false) --------------------------------

    static final class Peek {
        final AtomicInteger invoked = new AtomicInteger();
        @Tool(name = "peek", description = "A read-only inspection tool.", readOnly = true)
        public String peek() {
            invoked.incrementAndGet();
            return "peeked";
        }
    }

    static final class Mutate {
        final AtomicInteger invoked = new AtomicInteger();
        @Tool(name = "mutate", description = "A mutating tool (not read-only).")
        public String mutate() {
            invoked.incrementAndGet();
            return "mutated";
        }
    }

    /** Always answers with text — used for the toggle + programmatic-API tests (no turn tool calls). */
    static final class TextModel implements Model {
        @Override public String getModelName() { return "fake-text"; }
        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.just(text("ok"));
        }
    }

    // ---- A/B: the plan trio toggles with .planMode(...) --------------------------------------------

    @Test
    void planToolsRegisteredWhenEnabled(@TempDir Path ws) {
        PigAgent agent = PigAgent.builder()
                .name("planner").sysPrompt("sp").model(new TextModel())
                .toolkit(new Toolkit()).workspace(ws)
                .planMode(enabled("plans"))
                .build();

        assertThat(agent.getReactAgent().getToolkit().getToolNames())
                .as("enabling Plan Mode registers the plan trio")
                .contains("plan_enter", "plan_write", "plan_exit");
        agent.close();
    }

    @Test
    void planToolsAbsentWhenDisabled(@TempDir Path ws) {
        PigAgent agent = PigAgent.builder()
                .name("planner").sysPrompt("sp").model(new TextModel())
                .toolkit(new Toolkit()).workspace(ws)
                .build(); // default: Plan Mode disabled → today's behavior exactly

        assertThat(agent.getReactAgent().getToolkit().getToolNames())
                .as("with Plan Mode disabled the schema carries no plan tools")
                .doesNotContain("plan_enter", "plan_write", "plan_exit");
        agent.close();
    }

    @Test
    void disabledSettingsIsTheZeroBehaviorBaseline() {
        PlanModeSettings d = PlanModeSettings.disabled();
        assertThat(d.enabled()).isFalse();
        assertThat(d.planDir()).isEqualTo("plans");
        assertThat(d.allowShell()).isFalse();
        // Blank dir normalizes to the native default so the object is always valid to pass on.
        assertThat(new PlanModeSettings(true, "  ", false).planDir()).isEqualTo("plans");
    }

    // ---- C: programmatic enter / status / exit -----------------------------------------------------

    @Test
    void enterExitStatusProgrammatic(@TempDir Path ws) {
        PigAgent agent = PigAgent.builder()
                .name("planner").sysPrompt("sp").model(new TextModel())
                .toolkit(new Toolkit()).workspace(ws)
                .planMode(enabled("plans"))
                .build();

        assertThat(agent.isPlanModeActive(SID)).as("not active before entering").isFalse();
        agent.enterPlanMode(SID);
        assertThat(agent.isPlanModeActive(SID)).as("active after enter").isTrue();
        agent.exitPlanMode(SID);
        assertThat(agent.isPlanModeActive(SID)).as("inactive after exit").isFalse();
        agent.close();
    }

    // ---- D: read-only enforced during the plan phase ----------------------------------------------

    /** Calls a read-only tool first, then a mutating tool, then stops. */
    static final class RwScriptModel implements Model {
        @Override public String getModelName() { return "fake-rw"; }
        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            int done = toolResultCount(messages);
            if (done == 0) {
                return Flux.just(toolCall("c-peek", "peek", Map.of()));
            }
            if (done == 1) {
                return Flux.just(toolCall("c-mutate", "mutate", Map.of()));
            }
            return Flux.just(text("finished"));
        }
    }

    @Test
    void readOnlyEnforcedDuringPlanMode(@TempDir Path ws) {
        Peek peek = new Peek();
        Mutate mutate = new Mutate();
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(peek);
        toolkit.registerTool(mutate);

        PigAgent agent = PigAgent.builder()
                .name("planner").sysPrompt("sp").model(new RwScriptModel())
                .toolkit(toolkit).workspace(ws)
                .maxIters(8)
                .planMode(enabled("plans"))
                .build();

        agent.enterPlanMode(SID);
        agent.stream(user("investigate then change something"), SID).blockLast(Duration.ofSeconds(30));

        assertThat(peek.invoked.get())
                .as("a read-only tool IS allowed during the plan phase").isGreaterThanOrEqualTo(1);
        assertThat(mutate.invoked.get())
                .as("a mutating tool is DENIED during the plan phase (read-only guarantee)").isZero();
        agent.close();
    }

    // ---- E: plan_write writes PLAN.md --------------------------------------------------------------

    /** With Plan Mode already active, writes the plan via plan_write, then stops. */
    static final class PlanWriteModel implements Model {
        @Override public String getModelName() { return "fake-write"; }
        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            if (toolResultCount(messages) == 0) {
                return Flux.just(toolCall("c-write", "plan_write", Map.of("content", PLAN_TEXT)));
            }
            return Flux.just(text("plan written"));
        }
    }

    @Test
    void planWriteWritesPlanFile(@TempDir Path ws) throws IOException {
        PigAgent agent = PigAgent.builder()
                .name("planner").sysPrompt("sp").model(new PlanWriteModel())
                .toolkit(new Toolkit()).workspace(ws)
                .maxIters(8)
                .planMode(enabled("plans"))
                .build();

        agent.enterPlanMode(SID);
        agent.stream(user("write the plan"), SID).blockLast(Duration.ofSeconds(30));

        // The plan file is written under the workspace (plan-dir is workspace-relative); locate it
        // robustly and assert it carries the written content.
        Path planFile = findPlanFile(ws);
        assertThat(planFile).as("plan_write created a PLAN.md under the workspace").isNotNull();
        assertThat(Files.readString(planFile)).contains(PLAN_TEXT);
        agent.close();
    }

    private static Path findPlanFile(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().equals("PLAN.md"))
                    .findFirst().orElse(null);
        }
    }

    // ---- F: plan_exit is HITL-gated — approve exits, reject stays ----------------------------------

    /** Requests plan_exit; after the exit resolves (allowed or denied) answers with text. */
    static final class PlanExitModel implements Model {
        final AtomicInteger exitCalls = new AtomicInteger();
        @Override public String getModelName() { return "fake-exit"; }
        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            if (toolResultCount(messages) == 0) {
                exitCalls.incrementAndGet();
                return Flux.just(toolCall("c-exit", "plan_exit", Map.of("rationale", "plan is ready")));
            }
            return Flux.just(text("post-exit"));
        }
    }

    @Test
    void planExitApproveExitsPlanMode(@TempDir Path ws) {
        PigAgent agent = buildExitAgent(ws);
        agent.enterPlanMode(SID);

        List<AgentEvent> events =
                agent.stream(user("exit plan mode"), SID).collectList().block(Duration.ofSeconds(30));
        RequireUserConfirmEvent ask = firstConfirm(events);
        assertThat(ask).as("plan_exit is HITL-gated — a confirm is required").isNotNull();

        // Approve → resume → Plan Mode ends (execution may proceed).
        agent.stream(resume(ask, true), SID).blockLast(Duration.ofSeconds(30));
        assertThat(agent.isPlanModeActive(SID)).as("approving plan_exit exits Plan Mode").isFalse();
        agent.close();
    }

    @Test
    void planExitRejectStaysInPlanMode(@TempDir Path ws) {
        PigAgent agent = buildExitAgent(ws);
        agent.enterPlanMode(SID);

        List<AgentEvent> events =
                agent.stream(user("exit plan mode"), SID).collectList().block(Duration.ofSeconds(30));
        RequireUserConfirmEvent ask = firstConfirm(events);
        assertThat(ask).isNotNull();

        // Reject → resume → still in the plan phase (the agent cannot unilaterally start executing).
        agent.stream(resume(ask, false), SID).blockLast(Duration.ofSeconds(30));
        assertThat(agent.isPlanModeActive(SID)).as("rejecting plan_exit keeps Plan Mode active").isTrue();
        agent.close();
    }

    private static PigAgent buildExitAgent(Path ws) {
        return PigAgent.builder()
                .name("planner").sysPrompt("sp").model(new PlanExitModel())
                .toolkit(new Toolkit()).workspace(ws)
                .maxIters(8)
                .planMode(enabled("plans"))
                .build();
    }

    private static RequireUserConfirmEvent firstConfirm(List<AgentEvent> events) {
        if (events == null) {
            return null;
        }
        for (AgentEvent e : events) {
            if (e instanceof RequireUserConfirmEvent c) {
                return c;
            }
        }
        return null;
    }

    private static Msg resume(RequireUserConfirmEvent ask, boolean approve) {
        List<ConfirmResult> results = new ArrayList<>();
        for (ToolUseBlock call : ask.getToolCalls()) {
            results.add(new ConfirmResult(approve, call));
        }
        Map<String, Object> meta = new HashMap<>();
        meta.put(Msg.METADATA_CONFIRM_RESULTS, results);
        return Msg.builder().name("user").role(MsgRole.USER)
                .textContent("decision").metadata(meta).build();
    }
}
