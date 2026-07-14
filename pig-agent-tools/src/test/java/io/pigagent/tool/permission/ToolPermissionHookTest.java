package io.pigagent.tool.permission;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.hook.PreActingEvent;
import io.agentscope.core.message.ToolUseBlock;
import io.pigagent.config.PermissionMode;
import io.pigagent.config.PigAgentConfig.PermissionConfig;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Adapter-level coverage for {@link ToolPermissionHook}: it reads the effective mode (global /
 * channel / per-agent override), delegates the decision to {@link PermissionResolver}, and — on a
 * veto — rewrites the pending {@link ToolUseBlock} to the {@link PermissionDeniedTool} sentinel
 * (preserving the id) while notifying an optional denial listener. Allowed calls and the sentinel
 * itself are left untouched. Pure resolver logic is covered separately in {@code PermissionResolverTest}.
 */
class ToolPermissionHookTest {

    private static final PermissionConfirmer THROWING = p -> {
        throw new AssertionError("confirmer must not be consulted here");
    };

    private static PermissionConfig cfg(String mode) {
        PermissionConfig c = new PermissionConfig();
        c.setMode(mode);
        return c;
    }

    /** A minimal event carrying a pending tool call; the hook never touches agent/toolkit. */
    private static PreActingEvent event(String toolName, Map<String, Object> input) {
        ToolUseBlock tu = ToolUseBlock.builder().id("call-1").name(toolName).input(input).build();
        return new PreActingEvent(mock(Agent.class), null, tu);
    }

    private static String run(ToolPermissionHook hook, PreActingEvent event) {
        PreActingEvent out = hook.onEvent(event).block();
        return out.getToolUse() == null ? null : out.getToolUse().getName();
    }

    @Test
    void priorityIsHighest() {
        ToolPermissionHook hook = new ToolPermissionHook(() -> cfg("ask"), THROWING, null);

        assertThat(hook.priority()).isZero();
    }

    @Test
    void planMode_vetoesMutatingTool_rewritingToSentinelPreservingId() {
        ToolPermissionHook hook = new ToolPermissionHook(() -> cfg("plan"), THROWING, null);
        PreActingEvent event = event("writeFile", Map.of("path", "a.txt"));

        PreActingEvent out = hook.onEvent(event).block();

        assertThat(out.getToolUse().getName()).isEqualTo(PermissionDeniedTool.TOOL_NAME);
        assertThat(out.getToolUse().getId()).isEqualTo("call-1"); // id preserved for the tool result
    }

    @Test
    void bypassMode_leavesToolUntouched() {
        ToolPermissionHook hook = new ToolPermissionHook(() -> cfg("bypass"), THROWING, null);

        assertThat(run(hook, event("writeFile", Map.of("path", "a.txt")))).isEqualTo("writeFile");
    }

    @Test
    void sentinelToolIsNotReprocessed() {
        // An incoming call already pointing at the deny sentinel must be left alone (no loop).
        ToolPermissionHook hook = new ToolPermissionHook(() -> cfg("plan"), THROWING, null);

        assertThat(run(hook, event(PermissionDeniedTool.TOOL_NAME, Map.of())))
                .isEqualTo(PermissionDeniedTool.TOOL_NAME);
    }

    @Test
    void nullToolUse_isNoOp() {
        ToolPermissionHook hook = new ToolPermissionHook(() -> cfg("plan"), THROWING, null);

        assertThat(run(hook, new PreActingEvent(mock(Agent.class), null, null))).isNull();
    }

    @Test
    void modeOverride_takesPrecedenceOverGlobalMode() {
        // Global says bypass (allow-all), but the per-agent override forces PLAN → veto.
        Supplier<PermissionMode> override = () -> PermissionMode.PLAN;
        ToolPermissionHook hook = new ToolPermissionHook(
                () -> cfg("bypass"), THROWING, null, false, override);

        assertThat(run(hook, event("writeFile", Map.of("path", "a.txt"))))
                .isEqualTo(PermissionDeniedTool.TOOL_NAME);
    }

    @Test
    void nullModeOverride_fallsBackToGlobalMode() {
        Supplier<PermissionMode> override = () -> null;
        ToolPermissionHook hook = new ToolPermissionHook(
                () -> cfg("bypass"), THROWING, null, false, override);

        assertThat(run(hook, event("writeFile", Map.of("path", "a.txt")))).isEqualTo("writeFile");
    }

    @Test
    void channelMode_execFailsClosedWithoutConfirmer() {
        // channel=true → resolveChannelMode (default AUTO). AUTO still confirms EXEC; no confirmer
        // → fail-closed → veto.
        ToolPermissionHook hook = new ToolPermissionHook(
                () -> new PermissionConfig(), null, null, true);

        assertThat(run(hook, event("executeCommand", Map.of("command", "rm -rf /"))))
                .isEqualTo(PermissionDeniedTool.TOOL_NAME);
    }

    @Test
    void denialListener_isNotifiedOnVeto() {
        List<String> denied = new ArrayList<>();
        ToolPermissionHook.DenialListener listener = (tool, detail) -> denied.add(tool);
        ToolPermissionHook hook = new ToolPermissionHook(
                () -> cfg("plan"), THROWING, null, false, null, listener);

        run(hook, event("writeFile", Map.of("path", "a.txt")));

        assertThat(denied).containsExactly("writeFile");
    }

    @Test
    void denialListener_notNotifiedWhenAllowed() {
        List<String> denied = new ArrayList<>();
        ToolPermissionHook.DenialListener listener = (tool, detail) -> denied.add(tool);
        ToolPermissionHook hook = new ToolPermissionHook(
                () -> cfg("bypass"), THROWING, null, false, null, listener);

        run(hook, event("writeFile", Map.of("path", "a.txt")));

        assertThat(denied).isEmpty();
    }
}
