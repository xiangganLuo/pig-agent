package io.pigagent.core.middleware;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.pigagent.core.metrics.ToolMetricsRegistry;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Purely observational middleware ({@code tools-observability}, T3) that aggregates per-tool call
 * metrics — call count, latency and error count — into a shared {@link ToolMetricsRegistry}. It is a
 * sibling of {@link ToolCallLoggingMiddleware} (which only logs): the two coexist as independent
 * {@code onActing} stages, so adding metrics does <b>not</b> change the logging behavior.
 *
 * <p><b>Non-invasive by contract.</b> {@code onActing} always forwards the <em>same</em>
 * {@link ActingInput} to {@code next} (never a rewritten one) and never alters the returned event
 * stream — it only attaches side-effect-only {@code doOnError}/{@code doFinally} probes:
 * <ul>
 *   <li>on any terminal signal (complete / error / cancel) it records one <em>call</em> with the wall
 *       latency of the acting phase for each tool named in the input;</li>
 *   <li>on an error signal it additionally records one <em>error</em> for each of those tools.</li>
 * </ul>
 * So a call that errors increments both {@code calls} and {@code errors} (error rate = errors/calls).
 *
 * <p>Metrics are keyed by tool name only — the input's argument values and the result content are never
 * read, so no credential can leak into metrics. When an acting phase carries several tool calls the
 * measured latency is the whole phase's wall time attributed to each named tool (an accepted
 * approximation; single-tool acting steps, the common case, are exact).
 */
public final class ToolMetricsMiddleware implements MiddlewareBase {

    private final ToolMetricsRegistry registry;

    public ToolMetricsMiddleware(ToolMetricsRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    @Override
    public Flux<AgentEvent> onActing(Agent agent, RuntimeContext ctx, ActingInput input,
                                     Function<ActingInput, Flux<AgentEvent>> next) {
        List<String> names = toolNames(input == null ? null : input.toolCalls());
        if (names.isEmpty()) {
            return next.apply(input);
        }
        long start = System.nanoTime();
        return next.apply(input)
                .doOnError(e -> names.forEach(registry::recordError))
                .doFinally(sig -> {
                    long ms = Math.max(0L, (System.nanoTime() - start) / 1_000_000L);
                    names.forEach(n -> registry.recordCall(n, ms));
                });
    }

    /** The non-blank tool names in this acting phase (skipping null blocks / blank names). */
    private static List<String> toolNames(List<ToolUseBlock> calls) {
        if (calls == null || calls.isEmpty()) {
            return List.of();
        }
        List<String> names = new ArrayList<>(calls.size());
        for (ToolUseBlock tu : calls) {
            if (tu == null) {
                continue;
            }
            String name = tu.getName();
            if (name != null && !name.isBlank()) {
                names.add(name);
            }
        }
        return names;
    }
}
