package io.pigagent.plugin.builtin;

import io.pigagent.plugin.CollectingPluginContext;
import io.pigagent.tool.spi.ToolContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The Template-Method + Factory-Method skeleton: {@code register} is fixed and delegates the varying
 * "which tools" step to {@link AbstractToolPlugin#createTools}, and the id is stable.
 */
class AbstractToolPluginTest {

    /** A concrete plugin whose factory yields two marker tools. */
    private static final class TwoToolPlugin extends AbstractToolPlugin {
        private final Object a = new Object();
        private final Object b = new Object();

        TwoToolPlugin() {
            super("test:two");
        }

        @Override
        protected List<Object> createTools(ToolContext context) {
            return List.of(a, b);
        }
    }

    @Test
    void register_contributesFactoryToolsThroughContext() {
        // Arrange
        CollectingPluginContext ctx = new CollectingPluginContext(new ToolContext(null, null));
        TwoToolPlugin plugin = new TwoToolPlugin();

        // Act
        plugin.register(ctx);

        // Assert — both factory-produced tools were added via the fixed skeleton
        assertThat(ctx.tools()).containsExactly(plugin.a, plugin.b);
        assertThat(ctx.middlewares()).isEmpty();
    }

    @Test
    void id_isStableAndAsConstructed() {
        assertThat(new TwoToolPlugin().id()).isEqualTo("test:two");
    }

    @Test
    void blankIdIsRejected() {
        assertThatThrownBy(() -> new AbstractToolPlugin(" ") {
            @Override
            protected List<Object> createTools(ToolContext context) {
                return List.of();
            }
        }).isInstanceOf(IllegalArgumentException.class);
    }
}
