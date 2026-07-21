package io.pigagent.core.metrics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Thread-safe, process-in-memory aggregation of per-tool call metrics — the observability backing of
 * {@code tools-observability} (T3). Keyed <b>only by tool name</b>: it records a call count, cumulative
 * latency and an error count per tool, from which it derives the average latency and error rate. It
 * MUST NOT hold any tool argument value, tool result content, or credential — the key is the tool name
 * and the values are pure counters, so a secret can never leak through metrics.
 *
 * <p>Fed by {@link io.pigagent.core.middleware.ToolMetricsMiddleware} (a purely observational
 * middleware) and read by the {@code /tools} operator command / the kernel tool inventory. Metrics are
 * ephemeral (reset on restart / {@link #reset()}); they are a live troubleshooting aid, not a
 * persisted audit log.
 *
 * <p>Concurrency: counters live in a {@link ConcurrentHashMap} of {@link LongAdder}s, so the hot path
 * (record on every tool dispatch) is lock-free and cheap.
 */
public final class ToolMetricsRegistry {

    /**
     * An immutable snapshot of one tool's aggregated metrics.
     *
     * @param toolName            the tool name (the only identity kept — never an argument/credential)
     * @param calls               number of dispatches observed (success + error)
     * @param errors              number of dispatches that ended in error
     * @param totalLatencyMillis  cumulative wall latency across all dispatches
     */
    public record ToolMetrics(String toolName, long calls, long errors, long totalLatencyMillis) {

        /** Mean latency per call in ms (0 when there were no calls). */
        public long avgLatencyMillis() {
            return calls <= 0 ? 0L : totalLatencyMillis / calls;
        }

        /** Fraction of calls that errored, in {@code [0.0, 1.0]} (0 when there were no calls). */
        public double errorRate() {
            return calls <= 0 ? 0.0 : (double) errors / (double) calls;
        }
    }

    private static final class Counters {
        private final LongAdder calls = new LongAdder();
        private final LongAdder errors = new LongAdder();
        private final LongAdder totalLatencyMillis = new LongAdder();
    }

    private final Map<String, Counters> byTool = new ConcurrentHashMap<>();

    /**
     * Record one completed dispatch of {@code toolName} with its wall latency. A blank name is ignored;
     * a negative latency is clamped to 0 (defensive — the middleware already clamps).
     */
    public void recordCall(String toolName, long latencyMillis) {
        if (toolName == null || toolName.isBlank()) {
            return;
        }
        Counters c = byTool.computeIfAbsent(toolName, k -> new Counters());
        c.calls.increment();
        c.totalLatencyMillis.add(Math.max(0L, latencyMillis));
    }

    /** Record that one dispatch of {@code toolName} ended in error. A blank name is ignored. */
    public void recordError(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return;
        }
        byTool.computeIfAbsent(toolName, k -> new Counters()).errors.increment();
    }

    /** The metrics for one tool, or empty if it has never been observed. */
    public Optional<ToolMetrics> get(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return Optional.empty();
        }
        Counters c = byTool.get(toolName);
        return c == null ? Optional.empty() : Optional.of(toMetrics(toolName, c));
    }

    /** A snapshot of every observed tool's metrics, most-called first (stable tie-break by name). */
    public List<ToolMetrics> snapshot() {
        List<ToolMetrics> out = new ArrayList<>(byTool.size());
        byTool.forEach((name, c) -> out.add(toMetrics(name, c)));
        out.sort(Comparator.comparingLong(ToolMetrics::calls).reversed()
                .thenComparing(ToolMetrics::toolName));
        return out;
    }

    /** Clear all recorded metrics (e.g. an operator reset). */
    public void reset() {
        byTool.clear();
    }

    private static ToolMetrics toMetrics(String name, Counters c) {
        return new ToolMetrics(name, c.calls.sum(), c.errors.sum(), c.totalLatencyMillis.sum());
    }
}
