package io.pigagent.cli;

import io.agentscope.core.middleware.MiddlewareBase;
import io.pigagent.core.metrics.ToolMetricsRegistry;
import io.pigagent.core.middleware.LoggingMiddleware;
import io.pigagent.core.middleware.ToolMetricsMiddleware;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Default-safe wiring guard ({@code tools-observability}, T3): the per-tool metrics observer is
 * <b>additive</b> — {@link AgentBootstrap#peerMiddlewares} adds a {@link ToolMetricsMiddleware} only
 * when metrics are enabled (a non-null registry), and never otherwise, while the existing logging
 * middlewares are always present regardless.
 */
class AgentBootstrapMetricsWiringTest {

    @Test
    void peerMiddlewares_withoutMetrics_hasNoMetricsMiddleware() {
        List<MiddlewareBase> mws = AgentBootstrap.peerMiddlewares(mock(MiddlewareBase.class), null);

        assertThat(mws).noneMatch(m -> m instanceof ToolMetricsMiddleware);
        assertThat(mws).anyMatch(m -> m instanceof LoggingMiddleware);
    }

    @Test
    void peerMiddlewares_withMetrics_addsExactlyOneMetricsMiddleware() {
        List<MiddlewareBase> mws =
                AgentBootstrap.peerMiddlewares(mock(MiddlewareBase.class), new ToolMetricsRegistry());

        assertThat(mws).filteredOn(m -> m instanceof ToolMetricsMiddleware).hasSize(1);
        assertThat(mws).anyMatch(m -> m instanceof LoggingMiddleware);
    }
}
