package io.pigagent.core.metrics;

import io.pigagent.core.metrics.ToolMetricsRegistry.ToolMetrics;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Unit tests for {@link ToolMetricsRegistry}: per-tool aggregation of calls/latency/errors, derived
 * avg latency + error rate, blank-name guarding, snapshot ordering, and reset.
 */
class ToolMetricsRegistryTest {

    @Test
    void recordCall_accumulatesCountAndLatency() {
        // Arrange
        ToolMetricsRegistry registry = new ToolMetricsRegistry();

        // Act
        registry.recordCall("readFile", 100);
        registry.recordCall("readFile", 300);

        // Assert
        ToolMetrics m = registry.get("readFile").orElseThrow();
        assertThat(m.calls()).isEqualTo(2);
        assertThat(m.errors()).isZero();
        assertThat(m.totalLatencyMillis()).isEqualTo(400);
        assertThat(m.avgLatencyMillis()).isEqualTo(200);
        assertThat(m.errorRate()).isEqualTo(0.0);
    }

    @Test
    void recordError_incrementsErrorCountAndErrorRate() {
        // Arrange
        ToolMetricsRegistry registry = new ToolMetricsRegistry();

        // Act: two calls, one of which errored
        registry.recordCall("webSearch", 50);
        registry.recordError("webSearch");
        registry.recordCall("webSearch", 50);

        // Assert
        ToolMetrics m = registry.get("webSearch").orElseThrow();
        assertThat(m.calls()).isEqualTo(2);
        assertThat(m.errors()).isEqualTo(1);
        assertThat(m.errorRate()).isCloseTo(0.5, within(1e-9));
    }

    @Test
    void unknownTool_hasNoMetrics() {
        assertThat(new ToolMetricsRegistry().get("nope")).isEmpty();
    }

    @Test
    void blankOrNullToolName_isIgnored() {
        // Arrange
        ToolMetricsRegistry registry = new ToolMetricsRegistry();

        // Act
        registry.recordCall("", 10);
        registry.recordCall(null, 10);
        registry.recordError(" ");

        // Assert
        assertThat(registry.snapshot()).isEmpty();
    }

    @Test
    void negativeLatency_isClampedToZero() {
        // Arrange
        ToolMetricsRegistry registry = new ToolMetricsRegistry();

        // Act
        registry.recordCall("t", -5);

        // Assert
        assertThat(registry.get("t").orElseThrow().totalLatencyMillis()).isZero();
    }

    @Test
    void snapshot_isOrderedByCallsDescendingThenName() {
        // Arrange
        ToolMetricsRegistry registry = new ToolMetricsRegistry();
        registry.recordCall("a", 1);
        registry.recordCall("b", 1);
        registry.recordCall("b", 1);
        registry.recordCall("c", 1);

        // Act
        List<ToolMetrics> snap = registry.snapshot();

        // Assert: b (2 calls) first, then a, c (1 call each) by name
        assertThat(snap).extracting(ToolMetrics::toolName).containsExactly("b", "a", "c");
    }

    @Test
    void reset_clearsAllMetrics() {
        // Arrange
        ToolMetricsRegistry registry = new ToolMetricsRegistry();
        registry.recordCall("x", 1);

        // Act
        registry.reset();

        // Assert
        assertThat(registry.snapshot()).isEmpty();
        assertThat(registry.get("x")).isEmpty();
    }
}
