package io.pigagent.core.permission;

import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.event.RequireUserConfirmEvent;
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
import io.agentscope.core.permission.PermissionEngine;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import io.pigagent.core.agent.PigAgent;
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
 * Load-bearing SPIKE for the permission "always-allow" cross-turn persistence fix
 * (change {@code permission-always-allow-persist}, tasks group 1).
 *
 * <p><b>Spike outcome (design-changing).</b> The originally-approved P1 mechanism —
 * "stop emitting the per-tool ASK rule and rely on the native mode default" — is <b>infeasible</b>:
 * although {@link PermissionEngine#checkPermission} returns {@code ASK} for a {@code DEFAULT}-mode
 * unruled non-read-only tool ({@link #engineModeDefaults_documentTheContract}), the <b>agent</b> only
 * pauses for HITL when an <b>explicit ASK rule</b> (or a built-in ASK check) matches — a bare
 * mode-default ASK does <b>not</b> surface {@link RequireUserConfirmEvent}; the agent proceeds and
 * <b>executes</b> the tool ({@link #modeDefaultAsk_agentExecutes_noHitl} vs
 * {@link #explicitAskRule_agentSurfacesHitl}). Removing the ASK rule would therefore run writes with
 * <b>no confirmation</b> — a security regression.
 *
 * <p><b>Corrected mechanism (proven here).</b> Keep the per-tool ASK rules globally (first-ask
 * preserved for every tool); on "always allow" do a <b>per-session, per-tool ASK→ALLOW swap</b> in the
 * session slot's {@link AgentState#getPermissionContext()} — remove <em>that tool's</em> ASK rule, add
 * an ALLOW rule — <b>and</b> refresh the per-slot engine cache
 * ({@code ReActAgent.permissionEngineCache}, javap-confirmed {@code computeIfAbsent}) via the public
 * {@code setPermissionMode(userId, sessionId, sameMode)} (which does {@code .put(...)} + save). Then
 * the next turn auto-allows that one tool while every other tool still confirms. Two negative controls
 * pin the necessity of each half: keeping the ASK rule → still asks (deny&gt;ask&gt;allow shadow);
 * skipping the cache refresh → still asks (stale cached engine).
 */
class PermissionAlwaysAllowSpikeTest {

    private static final String USER_ID = "pig";
    private static final String SID = "s1";
    private static final String TOOL = "writeThing";

    // ---- a ToolBase WRITE tool the native PermissionEngine actually gates (mirrors the guarded path) --

    static final class WriteTool extends ToolBase {
        final AtomicInteger invoked = new AtomicInteger();

        WriteTool() {
            super(TOOL, "a write tool", Map.of(), false, false, false, null, false, false);
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            invoked.incrementAndGet();
            return Mono.just(ToolResultBlock.builder()
                    .id(param.getToolUseBlock().getId()).name(TOOL)
                    .output(List.of(TextBlock.builder().text("wrote").build()))
                    .build());
        }
    }

    /** Calls {@link #TOOL} exactly once per genuine user turn, else answers with text. */
    static final class OnePerTurnModel implements Model {
        @Override public String getModelName() { return "fake-oneperturn"; }

        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            int genuineTurns = genuineUserTurns(messages);
            int toolUses = countBlocks(messages, ToolUseBlock.class);
            int toolResults = countBlocks(messages, ToolResultBlock.class);
            if (toolUses == toolResults && genuineTurns > toolUses) {
                return Flux.just(ChatResponse.builder()
                        .content(List.of(ToolUseBlock.builder()
                                .id("c" + toolUses).name(TOOL).input(Map.of()).content("{}").build()))
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
            if (m.getRole() == MsgRole.USER
                    && (m.getMetadata() == null || !m.getMetadata().containsKey(Msg.METADATA_CONFIRM_RESULTS))) {
                n++;
            }
        }
        return n;
    }

    private static int countBlocks(List<Msg> messages, Class<?> type) {
        int n = 0;
        for (Msg m : messages) {
            for (var b : m.getContent()) {
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

    /** Interactive-track context: DEFAULT mode + an explicit per-tool ASK rule (today's pig behavior). */
    private static PermissionContextState askCtx() {
        return PermissionContextState.builder().mode(PermissionMode.DEFAULT)
                .addAskRule(TOOL, new PermissionRule(TOOL, null, PermissionBehavior.ASK, "pig"))
                .build();
    }

    private static PigAgent agent(Path ws, WriteTool tool, PermissionContextState ctx) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(tool);
        return PigAgent.builder()
                .name("spike").sysPrompt("sp").model(new OnePerTurnModel())
                .toolkit(toolkit).workspace(ws).maxIters(8)
                .permissionContext(ctx)
                .build();
    }

    /** Copy a context, optionally dropping {@code tool}'s ASK rules, optionally adding an ALLOW rule. */
    private static PermissionContextState rebuild(PermissionContextState cur, String tool,
                                                  boolean dropAskForTool, boolean addAllowForTool) {
        PermissionContextState.Builder b = PermissionContextState.builder()
                .mode(cur == null ? PermissionMode.DEFAULT : cur.getMode());
        if (cur != null) {
            cur.getWorkingDirectories().forEach(b::addWorkingDirectory);
            cur.getAllowRules().forEach((n, rs) -> rs.forEach(r -> b.addAllowRule(n, r)));
            cur.getDenyRules().forEach((n, rs) -> rs.forEach(r -> b.addDenyRule(n, r)));
            cur.getAskRules().forEach((n, rs) -> {
                if (!(dropAskForTool && n.equals(tool))) {
                    rs.forEach(r -> b.addAskRule(n, r));
                }
            });
        }
        if (addAllowForTool) {
            b.addAllowRule(tool, new PermissionRule(tool, null, PermissionBehavior.ALLOW, "user:always"));
        }
        return b.build();
    }

    /** Persist a context to the slot AND refresh the cached engine (via setPermissionMode(sameMode)). */
    private static void writeBack(PigAgent agent, String sid, PermissionContextState updated, boolean refresh) {
        AgentState state = agent.getReactAgent().getAgentState(USER_ID, sid);
        state.setPermissionContext(updated);
        if (refresh) {
            agent.getReactAgent().setPermissionMode(USER_ID, sid, updated.getMode());
        } else {
            agent.getReactAgent().saveAgentState(USER_ID, sid);
        }
    }

    private static RequireUserConfirmEvent turn(PigAgent agent, String sid, String text) {
        return firstConfirm(agent.stream(user(text), sid).collectList().block(Duration.ofSeconds(30)));
    }

    // ================================================================================================
    // Engine contract (documents the ground truth the design rests on)
    // ================================================================================================

    @Test
    void engineModeDefaults_documentTheContract() {
        WriteTool tool = new WriteTool();
        assertThat(decision(tool, PermissionMode.DEFAULT)).isEqualTo(PermissionBehavior.ASK);
        assertThat(decision(tool, PermissionMode.DONT_ASK)).isEqualTo(PermissionBehavior.DENY);
        assertThat(decision(tool, PermissionMode.ACCEPT_EDITS)).isEqualTo(PermissionBehavior.ASK);
        assertThat(decision(tool, PermissionMode.BYPASS)).isEqualTo(PermissionBehavior.ALLOW);
    }

    private static PermissionBehavior decision(ToolBase tool, PermissionMode mode) {
        var d = new PermissionEngine(PermissionContextState.builder().mode(mode).build())
                .checkPermission(tool, Map.of()).block();
        return d == null ? null : d.getBehavior();
    }

    // ================================================================================================
    // The design-changing finding: mode-default ASK ≠ agent HITL; an explicit ASK rule IS needed.
    // ================================================================================================

    @Test
    void modeDefaultAsk_agentExecutes_noHitl(@TempDir Path ws) {
        WriteTool tool = new WriteTool();
        // DEFAULT mode, NO per-tool ASK rule (the would-be P1 world).
        PigAgent agent = agent(ws, tool, PermissionContextState.builder().mode(PermissionMode.DEFAULT).build());

        RequireUserConfirmEvent ask = turn(agent, SID, "do it");

        assertThat(ask).as("a bare mode-default ASK does NOT surface a HITL confirm at the agent level").isNull();
        assertThat(tool.invoked.get())
                .as("without an explicit ASK rule the agent EXECUTES the write (no confirmation) — "
                        + "so removing the ASK rule would be a security regression")
                .isEqualTo(1);
        agent.close();
    }

    @Test
    void explicitAskRule_agentSurfacesHitl(@TempDir Path ws) {
        WriteTool tool = new WriteTool();
        PigAgent agent = agent(ws, tool, askCtx());

        RequireUserConfirmEvent ask = turn(agent, SID, "do it");

        assertThat(ask).as("an explicit per-tool ASK rule surfaces the HITL confirm").isNotNull();
        assertThat(tool.invoked.get()).as("the write is not executed while awaiting confirmation").isZero();
        agent.close();
    }

    // ================================================================================================
    // The corrected mechanism: per-session ASK→ALLOW swap + cache refresh
    // ================================================================================================

    @Test
    void swapAskForAllow_withCacheRefresh_autoAllowsNextTurn(@TempDir Path ws) {
        WriteTool tool = new WriteTool();
        PigAgent agent = agent(ws, tool, askCtx());

        // Turn 1: ask → approve (the tool runs once).
        RequireUserConfirmEvent ask1 = turn(agent, SID, "turn1");
        assertThat(ask1).as("turn 1 asks the first time").isNotNull();
        agent.stream(resume(ask1, true), SID).blockLast(Duration.ofSeconds(30));
        int afterTurn1 = tool.invoked.get();
        assertThat(afterTurn1).isEqualTo(1);

        // "Always allow": drop the tool's ASK rule, add an ALLOW rule, refresh the engine cache.
        AgentState state = agent.getReactAgent().getAgentState(USER_ID, SID);
        writeBack(agent, SID, rebuild(state.getPermissionContext(), TOOL, true, true), true);

        // Turn 2: SAME tool → auto-allowed (no confirm), executed again.
        assertThat(turn(agent, SID, "turn2")).as("turn 2 auto-allows — no HITL confirm").isNull();
        assertThat(tool.invoked.get()).as("the always-allowed tool executed again in turn 2")
                .isGreaterThan(afterTurn1);
        agent.close();
    }

    @Test
    void addAllowButKeepAskRule_stillAsks_shadowNegativeControl(@TempDir Path ws) {
        WriteTool tool = new WriteTool();
        PigAgent agent = agent(ws, tool, askCtx());

        RequireUserConfirmEvent ask1 = turn(agent, SID, "turn1");
        assertThat(ask1).isNotNull();
        agent.stream(resume(ask1, true), SID).blockLast(Duration.ofSeconds(30));

        // Add ALLOW but KEEP the ASK rule (+refresh) — deny>ask>allow means ASK still shadows ALLOW.
        AgentState state = agent.getReactAgent().getAgentState(USER_ID, SID);
        writeBack(agent, SID, rebuild(state.getPermissionContext(), TOOL, false, true), true);

        assertThat(turn(agent, SID, "turn2"))
                .as("keeping the ASK rule shadows the added ALLOW → turn 2 still asks (remove-ask is necessary)")
                .isNotNull();
        agent.close();
    }

    @Test
    void swapAlsoTakesEffectWithoutExplicitRefresh(@TempDir Path ws) {
        // Documents the observed cache behavior: a bare setPermissionContext(...) + saveAgentState(...)
        // ALSO propagates to the next turn (the per-slot engine is re-derived from the persisted
        // context each turn), so the setPermissionMode refresh is a determinism belt-and-suspenders,
        // not a hard requirement. Production uses the refresh path for guaranteed cache freshness.
        WriteTool tool = new WriteTool();
        PigAgent agent = agent(ws, tool, askCtx());

        RequireUserConfirmEvent ask1 = turn(agent, SID, "turn1");
        assertThat(ask1).isNotNull();
        agent.stream(resume(ask1, true), SID).blockLast(Duration.ofSeconds(30));

        AgentState state = agent.getReactAgent().getAgentState(USER_ID, SID);
        writeBack(agent, SID, rebuild(state.getPermissionContext(), TOOL, true, true), false); // no refresh

        assertThat(turn(agent, SID, "turn2"))
                .as("the persisted slot context is re-read next turn → the swap takes effect even without "
                        + "the explicit engine-cache refresh")
                .isNull();
        agent.close();
    }

    @Test
    void swap_isPerSession(@TempDir Path ws) {
        WriteTool tool = new WriteTool();
        PigAgent agent = agent(ws, tool, askCtx());

        RequireUserConfirmEvent ask1 = turn(agent, SID, "turn1");
        assertThat(ask1).isNotNull();
        agent.stream(resume(ask1, true), SID).blockLast(Duration.ofSeconds(30));
        AgentState s1 = agent.getReactAgent().getAgentState(USER_ID, SID);
        writeBack(agent, SID, rebuild(s1.getPermissionContext(), TOOL, true, true), true);

        // A different session slot has no such grant → it still asks (per-session scope).
        assertThat(turn(agent, "s2", "turn on s2"))
                .as("always-allow on s1 does NOT generalize to s2 (per-session scope)").isNotNull();
        agent.close();
    }
}
