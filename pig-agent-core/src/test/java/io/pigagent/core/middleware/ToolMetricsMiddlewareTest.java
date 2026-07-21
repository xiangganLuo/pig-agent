package io.pigagent.core.middleware;

import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.pigagent.core.metrics.ToolMetricsRegistry;
import io.pigagent.core.metrics.ToolMetricsRegistry.ToolMetrics;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Adapter tests for {@link ToolMetricsMiddleware}: it aggregates per-tool calls/errors into the shared
 * registry as a pure observer — never rewriting the {@link ActingInput}, never altering the event
 * stream, and coexisting with {@link ToolCallLoggingMiddleware} without changing its behavior (SP1).
 */
class ToolMetricsMiddlewareTest {

    private static ToolUseBlock toolUse(String name) {
        return ToolUseBlock.builder().id("id-" + name).name(name).input(Map.of("cmd", "ls")).build();
    }

    private static ActingInput acting(ToolUseBlock... calls) {
        return new ActingInput(List.of(calls));
    }

    /** A {@code next} that records the exact input it received and completes normally. */
    private static Function<ActingInput, Flux<AgentEvent>> captureSuccess(AtomicReference<ActingInput> seen) {
        return in -> {
            seen.set(in);
            return Flux.empty();
        };
    }

    @Test
    void successfulCall_recordsCallAndLatencyNotError() {
        // Arrange
        ToolMetricsRegistry registry = new ToolMetricsRegistry();
        ToolMetricsMiddleware mw = new ToolMetricsMiddleware(registry);

        // Act
        mw.onActing(null, null, acting(toolUse("readFile")), in -> Flux.empty()).blockLast();

        // Assert
        ToolMetrics m = registry.get("readFile").orElseThrow();
        assertThat(m.calls()).isEqualTo(1);
        assertThat(m.errors()).isZero();
        assertThat(m.totalLatencyMillis()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void erroredCall_recordsBothCallAndError() {
        // Arrange
        ToolMetricsRegistry registry = new ToolMetricsRegistry();
        ToolMetricsMiddleware mw = new ToolMetricsMiddleware(registry);

        // Act: next errors — swallow downstream so the probes (upstream) still fire
        mw.onActing(null, null, acting(toolUse("executeCommand")),
                        in -> Flux.<AgentEvent>error(new RuntimeException("boom")))
                .onErrorResume(e -> Flux.empty())
                .blockLast();

        // Assert
        ToolMetrics m = registry.get("executeCommand").orElseThrow();
        assertThat(m.calls()).isEqualTo(1);
        assertThat(m.errors()).isEqualTo(1);
        assertThat(m.errorRate()).isEqualTo(1.0);
    }

    @Test
    void doesNotRewriteInputNorAlterStream() {
        // Arrange
        ToolMetricsRegistry registry = new ToolMetricsRegistry();
        ToolMetricsMiddleware mw = new ToolMetricsMiddleware(registry);
        ActingInput input = acting(toolUse("readFile"));
        AtomicReference<ActingInput> seen = new AtomicReference<>();

        // Act
        mw.onActing(null, null, input, captureSuccess(seen)).blockLast();

        // Assert: next received the SAME input instance (no rewrite)
        assertThat(seen.get()).isSameAs(input);
    }

    @Test
    void emptyToolCalls_isPassThroughWithoutRecording() {
        // Arrange
        ToolMetricsRegistry registry = new ToolMetricsRegistry();
        ToolMetricsMiddleware mw = new ToolMetricsMiddleware(registry);
        ActingInput input = acting();
        AtomicReference<ActingInput> seen = new AtomicReference<>();

        // Act
        mw.onActing(null, null, input, captureSuccess(seen)).blockLast();

        // Assert
        assertThat(seen.get()).isSameAs(input);
        assertThat(registry.snapshot()).isEmpty();
    }

    @Test
    void coexistsWithLoggingMiddleware_withoutChangingItsPassThrough() {
        // SP1: metrics + logging middleware chained — logging still forwards the SAME input unchanged
        // and the stream is intact; metrics records the call. Neither modifies acting input/result.
        ToolMetricsRegistry registry = new ToolMetricsRegistry();
        ToolMetricsMiddleware metrics = new ToolMetricsMiddleware(registry);
        ToolCallLoggingMiddleware logging = new ToolCallLoggingMiddleware();
        ActingInput input = acting(toolUse("readFile"));
        AtomicReference<ActingInput> innermostSaw = new AtomicReference<>();

        // Chain: metrics -> logging -> terminal (as the builder would order them)
        metrics.onActing(null, null, input,
                mIn -> logging.onActing(null, null, mIn, lIn -> {
                    innermostSaw.set(lIn);
                    return Flux.empty();
                })).blockLast();

        // Assert: the innermost terminal saw the ORIGINAL input (neither middleware rewrote it),
        // and metrics still recorded exactly one call.
        assertThat(innermostSaw.get()).isSameAs(input);
        assertThat(registry.get("readFile").orElseThrow().calls()).isEqualTo(1);
    }
}
