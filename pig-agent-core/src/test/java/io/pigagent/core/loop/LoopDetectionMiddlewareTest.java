package io.pigagent.core.loop;

import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.ReasoningInput;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Adapter tests for {@link LoopDetectionMiddleware} (av2 Phase 5a middleware port). The detector is
 * mocked so these assert the middleware's translation of decisions into the two reused mechanisms —
 * {@code onActing} veto-to-sentinel (rewriting a new {@link ActingInput}), {@code onReasoning}
 * ephemeral WARN injection, ignore-set / disabled bypass, and per-turn reset — independently of the
 * detector's counting logic (which {@code LoopDetectorTest} covers).
 */
class LoopDetectionMiddlewareTest {

    private static final Set<String> IGNORED = Set.of(LoopDetectionMiddleware.SENTINEL_TOOL_NAME);

    private static Msg userMsg(String text) {
        return Msg.builder().role(MsgRole.USER)
                .content(TextBlock.builder().text(text).build()).build();
    }

    private static ToolUseBlock toolUse(String id, String name) {
        return ToolUseBlock.builder().id(id).name(name).input(Map.of("cmd", "ls")).build();
    }

    private static ReasoningInput reasoning(List<Msg> messages) {
        return new ReasoningInput(messages, List.of(), null);
    }

    private static ActingInput acting(ToolUseBlock... calls) {
        return new ActingInput(List.of(calls));
    }

    private static Function<ReasoningInput, Flux<AgentEvent>> captureReasoning(AtomicReference<ReasoningInput> sink) {
        return in -> {
            sink.set(in);
            return Flux.empty();
        };
    }

    private static Function<ActingInput, Flux<AgentEvent>> captureActing(AtomicReference<ActingInput> sink) {
        return in -> {
            sink.set(in);
            return Flux.empty();
        };
    }

    /** Advance past the turn-start reasoning so a subsequent same-turn injection is not swallowed. */
    private static void establishTurn(LoopDetectionMiddleware mw) {
        mw.onReasoning(null, null, reasoning(List.of(userMsg("hi"))),
                captureReasoning(new AtomicReference<>())).blockLast();
    }

    @Test
    void stop_rewritesToolCallToSentinel_preservingId() {
        LoopDetector detector = mock(LoopDetector.class);
        when(detector.observe(any(), any())).thenReturn(LoopDecision.STOP);
        LoopDetectionMiddleware mw = new LoopDetectionMiddleware(detector, () -> true, IGNORED);
        establishTurn(mw);

        AtomicReference<ActingInput> seen = new AtomicReference<>();
        mw.onActing(null, null, acting(toolUse("call-1", "executeCommand")), captureActing(seen)).blockLast();

        ToolUseBlock out = seen.get().toolCalls().get(0);
        assertThat(out.getName()).isEqualTo(LoopDetectionMiddleware.SENTINEL_TOOL_NAME);
        assertThat(out.getId()).isEqualTo("call-1");
    }

    @Test
    void warn_injectsUserNudgeOnNextReasoning() {
        LoopDetector detector = mock(LoopDetector.class);
        when(detector.observe(any(), any())).thenReturn(LoopDecision.WARN);
        when(detector.warnThreshold()).thenReturn(3);
        LoopDetectionMiddleware mw = new LoopDetectionMiddleware(detector, () -> true, IGNORED);
        establishTurn(mw);

        // WARN on acting: the tool is NOT rewritten, but a nudge is queued.
        AtomicReference<ActingInput> actSeen = new AtomicReference<>();
        mw.onActing(null, null, acting(toolUse("call-1", "executeCommand")), captureActing(actSeen)).blockLast();
        assertThat(actSeen.get().toolCalls().get(0).getName()).isEqualTo("executeCommand");

        // Next reasoning (same user count → no reset): the nudge is injected.
        AtomicReference<ReasoningInput> reSeen = new AtomicReference<>();
        mw.onReasoning(null, null, reasoning(List.of(userMsg("hi"))), captureReasoning(reSeen)).blockLast();

        List<Msg> injected = reSeen.get().messages();
        assertThat(injected).hasSize(2);
        Msg nudge = injected.get(injected.size() - 1);
        assertThat(nudge.getRole()).isEqualTo(MsgRole.USER);
        assertThat(nudge.getTextContent()).contains("循环");
    }

    @Test
    void ignoredToolName_isNeverObservedOrRewritten() {
        LoopDetector detector = mock(LoopDetector.class);
        LoopDetectionMiddleware mw = new LoopDetectionMiddleware(detector, () -> true, IGNORED);
        establishTurn(mw);

        AtomicReference<ActingInput> seen = new AtomicReference<>();
        mw.onActing(null, null, acting(toolUse("x", LoopDetectionMiddleware.SENTINEL_TOOL_NAME)),
                captureActing(seen)).blockLast();

        verify(detector, never()).observe(any(), any());
        assertThat(seen.get().toolCalls().get(0).getName()).isEqualTo(LoopDetectionMiddleware.SENTINEL_TOOL_NAME);
    }

    @Test
    void disabled_bypassesDetectionEntirely() {
        LoopDetector detector = mock(LoopDetector.class);
        LoopDetectionMiddleware mw = new LoopDetectionMiddleware(detector, () -> false, IGNORED);
        establishTurn(mw);

        AtomicReference<ActingInput> seen = new AtomicReference<>();
        mw.onActing(null, null, acting(toolUse("x", "executeCommand")), captureActing(seen)).blockLast();

        verify(detector, never()).observe(any(), any());
        assertThat(seen.get().toolCalls().get(0).getName()).isEqualTo("executeCommand");
    }

    @Test
    void reasoning_resetsDetectorOnlyWhenUserMessageCountChanges() {
        LoopDetector detector = mock(LoopDetector.class);
        LoopDetectionMiddleware mw = new LoopDetectionMiddleware(detector, () -> true, IGNORED);

        // -1 → 1 : turn start, reset #1
        mw.onReasoning(null, null, reasoning(List.of(userMsg("u1"))),
                captureReasoning(new AtomicReference<>())).blockLast();
        // 1 → 1 : same turn, no reset
        mw.onReasoning(null, null, reasoning(List.of(userMsg("u1"))),
                captureReasoning(new AtomicReference<>())).blockLast();
        // 1 → 2 : new user turn, reset #2
        mw.onReasoning(null, null, reasoning(List.of(userMsg("u1"), userMsg("u2"))),
                captureReasoning(new AtomicReference<>())).blockLast();

        verify(detector, times(2)).reset();
    }
}
