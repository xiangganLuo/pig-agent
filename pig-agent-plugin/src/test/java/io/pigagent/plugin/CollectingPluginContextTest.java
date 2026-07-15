package io.pigagent.plugin;

import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.pigagent.tool.spi.ToolContext;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link CollectingPluginContext}: it accumulates a plugin's tool/hook contributions
 * in order, is null-safe, and passes the shared {@link ToolContext} through unchanged.
 */
class CollectingPluginContextTest {

    @Test
    void accumulatesToolsAndHooksInOrder() {
        // Arrange
        CollectingPluginContext ctx = new CollectingPluginContext(new ToolContext(null, null));
        Object toolA = new Object();
        Object toolB = new Object();
        Hook hook = new NoopHook();

        // Act
        ctx.addTool(toolA).addTools(List.of(toolB)).addHook(hook);

        // Assert
        assertThat(ctx.tools()).containsExactly(toolA, toolB);
        assertThat(ctx.hooks()).containsExactly(hook);
    }

    @Test
    void ignoresNullContributions() {
        // Arrange
        CollectingPluginContext ctx = new CollectingPluginContext(new ToolContext(null, null));

        // Act
        ctx.addTool(null).addTools(null).addHook(null).addHooks(null);

        // Assert
        assertThat(ctx.tools()).isEmpty();
        assertThat(ctx.hooks()).isEmpty();
    }

    @Test
    void exposesTheSameToolContext() {
        // Arrange
        ToolContext toolContext = new ToolContext(null, null);
        CollectingPluginContext ctx = new CollectingPluginContext(toolContext);

        // Act + Assert
        assertThat(ctx.toolContext()).isSameAs(toolContext);
    }

    /** A do-nothing hook usable as a contribution fixture. */
    static final class NoopHook implements Hook {
        @Override
        public <T extends HookEvent> Mono<T> onEvent(T event) {
            return Mono.just(event);
        }
    }
}
