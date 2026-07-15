package io.pigagent.core.loop;

import io.agentscope.core.hook.PreActingEvent;
import io.agentscope.core.hook.PreReasoningEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Adapter tests for {@link LoopDetectionHook}. The detector is mocked so these assert the
 * hook's translation of decisions into events (veto-to-sentinel, ephemeral WARN injection,
 * ignore-set / disabled bypass, per-turn reset) independently of the detector's counting logic
 * (which {@link LoopDetectorTest} covers).
 */
class LoopDetectionHookTest {

    private static final Set<String> IGNORED = Set.of(LoopDetectionHook.SENTINEL_TOOL_NAME, "permissionDenied");

    private static Msg userMsg(String text) {
        return Msg.builder().role(MsgRole.USER)
                .content(TextBlock.builder().text(text).build()).build();
    }

    private static ToolUseBlock toolUse(String id, String name) {
        return ToolUseBlock.builder().id(id).name(name).input(Map.of("cmd", "ls")).build();
    }

    /** Advance the hook past the turn-start PreReasoning so subsequent injections are not swallowed. */
    private static void establishTurn(LoopDetectionHook hook) {
        PreReasoningEvent start = mock(PreReasoningEvent.class);
        when(start.getInputMessages()).thenReturn(List.of(userMsg("hi")));
        hook.onEvent(start).block();
    }

    @Test
    void stop_rewritesToolUseToSentinel_preservingId() {
        LoopDetector detector = mock(LoopDetector.class);
        when(detector.observe(any(), any())).thenReturn(LoopDecision.STOP);
        LoopDetectionHook hook = new LoopDetectionHook(detector, () -> true, IGNORED);
        establishTurn(hook);

        PreActingEvent acting = mock(PreActingEvent.class);
        when(acting.getToolUse()).thenReturn(toolUse("call-1", "executeCommand"));
        hook.onEvent(acting).block();

        ArgumentCaptor<ToolUseBlock> captor = ArgumentCaptor.forClass(ToolUseBlock.class);
        verify(acting).setToolUse(captor.capture());
        assertThat(captor.getValue().getName()).isEqualTo(LoopDetectionHook.SENTINEL_TOOL_NAME);
        assertThat(captor.getValue().getId()).isEqualTo("call-1");
    }

    @Test
    void warn_injectsUserNudgeOnNextReasoning() {
        LoopDetector detector = mock(LoopDetector.class);
        when(detector.observe(any(), any())).thenReturn(LoopDecision.WARN);
        when(detector.warnThreshold()).thenReturn(3);
        LoopDetectionHook hook = new LoopDetectionHook(detector, () -> true, IGNORED);
        establishTurn(hook);

        // WARN on acting: tool is NOT vetoed, but a nudge is queued.
        PreActingEvent acting = mock(PreActingEvent.class);
        when(acting.getToolUse()).thenReturn(toolUse("call-1", "executeCommand"));
        hook.onEvent(acting).block();
        verify(acting, never()).setToolUse(any());

        // Next reasoning (same user count → no reset): the nudge is injected.
        PreReasoningEvent reasoning = mock(PreReasoningEvent.class);
        when(reasoning.getInputMessages()).thenReturn(List.of(userMsg("hi")));
        hook.onEvent(reasoning).block();

        ArgumentCaptor<List<Msg>> captor = ArgumentCaptor.forClass(List.class);
        verify(reasoning).setInputMessages(captor.capture());
        List<Msg> injected = captor.getValue();
        assertThat(injected).hasSize(2);
        Msg nudge = injected.get(injected.size() - 1);
        assertThat(nudge.getRole()).isEqualTo(MsgRole.USER);
        assertThat(nudge.getTextContent()).contains("循环");
    }

    @Test
    void ignoredToolName_isNeverObservedOrRewritten() {
        LoopDetector detector = mock(LoopDetector.class);
        LoopDetectionHook hook = new LoopDetectionHook(detector, () -> true, IGNORED);
        establishTurn(hook);

        PreActingEvent acting = mock(PreActingEvent.class);
        when(acting.getToolUse()).thenReturn(toolUse("x", "permissionDenied"));
        hook.onEvent(acting).block();

        verify(detector, never()).observe(any(), any());
        verify(acting, never()).setToolUse(any());
    }

    @Test
    void disabled_bypassesDetectionEntirely() {
        LoopDetector detector = mock(LoopDetector.class);
        LoopDetectionHook hook = new LoopDetectionHook(detector, () -> false, IGNORED);
        establishTurn(hook);

        PreActingEvent acting = mock(PreActingEvent.class);
        when(acting.getToolUse()).thenReturn(toolUse("x", "executeCommand"));
        hook.onEvent(acting).block();

        verify(detector, never()).observe(any(), any());
        verify(acting, never()).setToolUse(any());
    }

    @Test
    void reasoning_resetsDetectorOnlyWhenUserMessageCountChanges() {
        LoopDetector detector = mock(LoopDetector.class);
        LoopDetectionHook hook = new LoopDetectionHook(detector, () -> true, IGNORED);

        // -1 → 1 : turn start, reset #1
        PreReasoningEvent r1 = mock(PreReasoningEvent.class);
        when(r1.getInputMessages()).thenReturn(List.of(userMsg("u1")));
        hook.onEvent(r1).block();

        // 1 → 1 : same turn, no reset
        PreReasoningEvent r2 = mock(PreReasoningEvent.class);
        when(r2.getInputMessages()).thenReturn(List.of(userMsg("u1")));
        hook.onEvent(r2).block();

        // 1 → 2 : new user turn, reset #2
        PreReasoningEvent r3 = mock(PreReasoningEvent.class);
        when(r3.getInputMessages()).thenReturn(List.of(userMsg("u1"), userMsg("u2")));
        hook.onEvent(r3).block();

        verify(detector, times(2)).reset();
    }

    @Test
    void priority_isAfterPermissionVeto() {
        LoopDetectionHook hook = new LoopDetectionHook(mock(LoopDetector.class), () -> true, IGNORED);

        // Permission hook has priority()=0; loop detection must run after it.
        assertThat(hook.priority()).isGreaterThan(0);
    }
}
