package io.pigagent.core.agent;

import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.event.RequireUserConfirmEvent;
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
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PigAgent#allowToolForSession(String, String)} — the D3 write-back that makes a user's
 * "always allow" ({@code a}) stick across turns (change {@code permission-always-allow-persist}).
 *
 * <p>Drives the REAL method (not the spike's raw sequence) through an offline scripted agent and
 * asserts both the resulting session-slot context (ASK→ALLOW swap, idempotent, other tools/deny
 * intact) and the observable behavior (turn-2 auto-allows the tool; a different tool, a first-ever
 * call, and a different session still ask; a non-interactive DONT_ASK agent stays fail-closed).
 */
class PigAgentAllowToolForSessionTest {

    private static final String USER_ID = "pig";
    private static final String SID = "s1";
    private static final Duration T = Duration.ofSeconds(30);

    static final class WriteTool extends ToolBase {
        final AtomicInteger invoked = new AtomicInteger();
        private final String name;

        WriteTool(String name) {
            super(name, "a write tool", Map.of(), false, false, false, null, false, false);
            this.name = name;
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            invoked.incrementAndGet();
            return Mono.just(ToolResultBlock.builder()
                    .id(param.getToolUseBlock().getId()).name(name)
                    .output(List.of(TextBlock.builder().text("wrote").build())).build());
        }
    }

    /** Fires, once per genuine user turn, the tool named in that turn's user text (writeA / writeB). */
    static final class NamedToolModel implements Model {
        @Override public String getModelName() { return "fake-named"; }

        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            int turns = genuineUserTurns(messages);
            int uses = count(messages, ToolUseBlock.class);
            int results = count(messages, ToolResultBlock.class);
            if (uses == results && turns > uses) {
                String tool = lastGenuineUserText(messages).contains("writeB") ? "writeB" : "writeA";
                return Flux.just(ChatResponse.builder()
                        .content(List.of(ToolUseBlock.builder().id("c" + uses).name(tool)
                                .input(Map.of()).content("{}").build()))
                        .finishReason("tool_calls").build());
            }
            return Flux.just(ChatResponse.builder()
                    .content(List.of(TextBlock.builder().text("done").build())).finishReason("stop").build());
        }
    }

    // ---- helpers -----------------------------------------------------------------------------------

    private static int genuineUserTurns(List<Msg> messages) {
        int n = 0;
        for (Msg m : messages) {
            if (isGenuineUser(m)) {
                n++;
            }
        }
        return n;
    }

    private static boolean isGenuineUser(Msg m) {
        return m.getRole() == MsgRole.USER
                && (m.getMetadata() == null || !m.getMetadata().containsKey(Msg.METADATA_CONFIRM_RESULTS));
    }

    private static String lastGenuineUserText(List<Msg> messages) {
        String txt = "";
        for (Msg m : messages) {
            if (isGenuineUser(m)) {
                StringBuilder sb = new StringBuilder();
                for (ContentBlock b : m.getContent()) {
                    if (b instanceof TextBlock t) {
                        sb.append(t.getText());
                    }
                }
                txt = sb.toString();
            }
        }
        return txt;
    }

    private static int count(List<Msg> messages, Class<?> type) {
        int n = 0;
        for (Msg m : messages) {
            for (ContentBlock b : m.getContent()) {
                if (type.isInstance(b)) {
                    n++;
                }
            }
        }
        return n;
    }

    private static Msg user(String text) {
        return Msg.builder().name("user").role(MsgRole.USER)
                .content(TextBlock.builder().text(text).build()).build();
    }

    private static Msg resume(RequireUserConfirmEvent ask, boolean approve) {
        List<ConfirmResult> results = new ArrayList<>();
        for (ToolUseBlock call : ask.getToolCalls()) {
            results.add(new ConfirmResult(approve, call));
        }
        Map<String, Object> meta = new HashMap<>();
        meta.put(Msg.METADATA_CONFIRM_RESULTS, results);
        return Msg.builder().name("user").role(MsgRole.USER).textContent("decision").metadata(meta).build();
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

    private static RequireUserConfirmEvent turn(PigAgent agent, String sid, String text) {
        return firstConfirm(agent.stream(user(text), sid).collectList().block(T));
    }

    /** Run a turn to its first ASK and approve it (seeds the slot + runs the tool once). */
    private static void askApprove(PigAgent agent, String sid, String text) {
        RequireUserConfirmEvent ask = turn(agent, sid, text);
        if (ask != null) {
            agent.stream(resume(ask, true), sid).blockLast(T);
        }
    }

    private static PermissionContextState askCtx() {
        return PermissionContextState.builder().mode(PermissionMode.DEFAULT)
                .addAskRule("writeA", new PermissionRule("writeA", null, PermissionBehavior.ASK, "pig"))
                .addAskRule("writeB", new PermissionRule("writeB", null, PermissionBehavior.ASK, "pig"))
                .build();
    }

    private static PigAgent agent(Path ws, PermissionContextState ctx, WriteTool... tools) {
        Toolkit toolkit = new Toolkit();
        for (WriteTool t : tools) {
            toolkit.registerAgentTool(t);
        }
        return PigAgent.builder()
                .name("t").sysPrompt("sp").model(new NamedToolModel())
                .toolkit(toolkit).workspace(ws).maxIters(8)
                .permissionContext(ctx)
                .build();
    }

    private static PermissionContextState slotCtx(PigAgent agent, String sid) {
        return agent.getReactAgent().getAgentState(USER_ID, sid).getPermissionContext();
    }

    // ---- behavior (real two-turn scenario) ---------------------------------------------------------

    @Test
    void turn2AutoAllowsSameTool(@TempDir Path ws) {
        WriteTool a = new WriteTool("writeA");
        PigAgent agent = agent(ws, askCtx(), a, new WriteTool("writeB"));

        askApprove(agent, SID, "call writeA");            // turn 1: ask → approve (ran once)
        assertThat(a.invoked.get()).isEqualTo(1);
        agent.allowToolForSession(SID, "writeA");         // user picked "a"

        assertThat(turn(agent, SID, "call writeA")).as("turn 2 auto-allows the same tool").isNull();
        assertThat(a.invoked.get()).as("the always-allowed tool ran again in turn 2").isEqualTo(2);
        agent.close();
    }

    @Test
    void otherToolStillAsksAfterAllow(@TempDir Path ws) {
        WriteTool a = new WriteTool("writeA");
        WriteTool b = new WriteTool("writeB");
        PigAgent agent = agent(ws, askCtx(), a, b);

        askApprove(agent, SID, "call writeA");
        agent.allowToolForSession(SID, "writeA");

        assertThat(turn(agent, SID, "call writeB"))
                .as("a DIFFERENT tool still asks (its ASK rule is untouched)").isNotNull();
        assertThat(b.invoked.get()).isZero();
        agent.close();
    }

    @Test
    void firstEverCallStillAsks(@TempDir Path ws) {
        WriteTool a = new WriteTool("writeA");
        PigAgent agent = agent(ws, askCtx(), a, new WriteTool("writeB"));

        assertThat(turn(agent, SID, "call writeA"))
                .as("a first-ever call still asks (before any 'a')").isNotNull();
        assertThat(a.invoked.get()).isZero();
        agent.close();
    }

    @Test
    void allowIsPerSession(@TempDir Path ws) {
        WriteTool a = new WriteTool("writeA");
        PigAgent agent = agent(ws, askCtx(), a, new WriteTool("writeB"));

        askApprove(agent, SID, "call writeA");
        agent.allowToolForSession(SID, "writeA");

        assertThat(turn(agent, "s2", "call writeA"))
                .as("always-allow on s1 does NOT generalize to another session s2").isNotNull();
        agent.close();
    }

    @Test
    void nonInteractiveDontAskStaysFailClosed(@TempDir Path ws) {
        // A channel/autonomous agent (DONT_ASK, no confirmer) never gets the 'a' path — it stays
        // fail-closed on a write tool regardless of this feature.
        WriteTool a = new WriteTool("writeA");
        PigAgent agent = agent(ws, PermissionContextState.builder().mode(PermissionMode.DONT_ASK).build(),
                a, new WriteTool("writeB"));

        assertThat(turn(agent, SID, "call writeA")).as("DONT_ASK never asks (no HITL)").isNull();
        assertThat(a.invoked.get()).as("DONT_ASK denies the unruled write (fail-closed)").isZero();
        agent.close();
    }

    // ---- resulting session-slot context (the swap) ------------------------------------------------

    @Test
    void swapsAskForAllow_leavingOtherToolsIntact(@TempDir Path ws) {
        PigAgent agent = agent(ws, askCtx(), new WriteTool("writeA"), new WriteTool("writeB"));
        askApprove(agent, SID, "call writeA"); // seed the slot with the ask rules

        agent.allowToolForSession(SID, "writeA");

        PermissionContextState ctx = slotCtx(agent, SID);
        assertThat(ctx.getAskRules().getOrDefault("writeA", List.of()))
                .as("the allowed tool's ASK rule is removed").isEmpty();
        assertThat(ctx.getAllowRules().getOrDefault("writeA", List.of()))
                .as("an ALLOW rule is added for the tool")
                .anyMatch(r -> r.behavior() == PermissionBehavior.ALLOW);
        assertThat(ctx.getAskRules().getOrDefault("writeB", List.of()))
                .as("another tool's ASK rule is preserved").isNotEmpty();
        agent.close();
    }

    @Test
    void idempotentAcrossRepeatedAllows(@TempDir Path ws) {
        PigAgent agent = agent(ws, askCtx(), new WriteTool("writeA"), new WriteTool("writeB"));
        askApprove(agent, SID, "call writeA");

        agent.allowToolForSession(SID, "writeA");
        agent.allowToolForSession(SID, "writeA");
        agent.allowToolForSession(SID, "writeA");

        PermissionContextState ctx = slotCtx(agent, SID);
        assertThat(ctx.getAllowRules().getOrDefault("writeA", List.of()))
                .as("repeated 'a' yields exactly one ALLOW rule (no stacking)").hasSize(1);
        assertThat(ctx.getAskRules().getOrDefault("writeA", List.of())).isEmpty();
        agent.close();
    }

    @Test
    void denyRuleIsPreserved(@TempDir Path ws) {
        PermissionContextState ctx = PermissionContextState.builder().mode(PermissionMode.DEFAULT)
                .addAskRule("writeA", new PermissionRule("writeA", null, PermissionBehavior.ASK, "pig"))
                .addDenyRule("writeB", new PermissionRule("writeB", null, PermissionBehavior.DENY, "pig"))
                .build();
        PigAgent agent = agent(ws, ctx, new WriteTool("writeA"), new WriteTool("writeB"));
        askApprove(agent, SID, "call writeA");

        agent.allowToolForSession(SID, "writeA");

        assertThat(slotCtx(agent, SID).getDenyRules().getOrDefault("writeB", List.of()))
                .as("a deny rule is never relaxed by an always-allow").isNotEmpty();
        agent.close();
    }

    @Test
    void blankToolNameIsNoOp(@TempDir Path ws) {
        PigAgent agent = agent(ws, askCtx(), new WriteTool("writeA"), new WriteTool("writeB"));
        askApprove(agent, SID, "call writeA");

        agent.allowToolForSession(SID, "  "); // must not throw, must not add anything

        assertThat(slotCtx(agent, SID).getAllowRules().getOrDefault("writeA", List.of()))
                .as("a blank tool name is a no-op").isEmpty();
        agent.close();
    }
}
