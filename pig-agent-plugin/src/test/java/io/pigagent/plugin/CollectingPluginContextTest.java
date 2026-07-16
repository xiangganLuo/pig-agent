package io.pigagent.plugin;

import io.agentscope.core.middleware.MiddlewareBase;
import io.pigagent.tool.spi.ToolContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link CollectingPluginContext}: it accumulates a plugin's tool/middleware
 * contributions in order, is null-safe, and passes the shared {@link ToolContext} through unchanged.
 */
class CollectingPluginContextTest {

    @Test
    void accumulatesToolsAndMiddlewaresInOrder() {
        // Arrange
        CollectingPluginContext ctx = new CollectingPluginContext(new ToolContext(null, null));
        Object toolA = new Object();
        Object toolB = new Object();
        MiddlewareBase middleware = new NoopMiddleware();

        // Act
        ctx.addTool(toolA).addTools(List.of(toolB)).addMiddleware(middleware);

        // Assert
        assertThat(ctx.tools()).containsExactly(toolA, toolB);
        assertThat(ctx.middlewares()).containsExactly(middleware);
    }

    @Test
    void ignoresNullContributions() {
        // Arrange
        CollectingPluginContext ctx = new CollectingPluginContext(new ToolContext(null, null));

        // Act
        ctx.addTool(null).addTools(null).addMiddleware(null).addMiddlewares(null);

        // Assert
        assertThat(ctx.tools()).isEmpty();
        assertThat(ctx.middlewares()).isEmpty();
    }

    @Test
    void exposesTheSameToolContext() {
        // Arrange
        ToolContext toolContext = new ToolContext(null, null);
        CollectingPluginContext ctx = new CollectingPluginContext(toolContext);

        // Act + Assert
        assertThat(ctx.toolContext()).isSameAs(toolContext);
    }

    /** A do-nothing middleware usable as a contribution fixture (all MiddlewareBase hooks default). */
    static final class NoopMiddleware implements MiddlewareBase {
    }
}
