package io.pigagent.plugin;

import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.Toolkit;
import io.pigagent.tool.spi.ToolContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link PluginRegistry}: a plugin contributes tools/middlewares via one
 * {@code register} entrypoint; plugin tools compose with the existing {@code ToolRegistrar} de-dup
 * (built-in wins a name collision, first-wins); a throwing plugin is isolated (fail-safe) and its
 * partial contributions discarded; discovery de-duplicates by id and isolates a failing source; and
 * with no plugins the toolkit/middlewares are unchanged (backward-compat).
 */
class PluginRegistryTest {

    private ToolContext context() {
        return new ToolContext(null, null);
    }

    // --- happy path: single entrypoint contributes tools + middlewares ---

    @Test
    void plugin_contributesToolIntoToolkit_andMiddlewareIsCollected() {
        // Arrange
        Toolkit toolkit = new Toolkit();
        NoopMiddleware middleware = new NoopMiddleware();
        Plugin plugin = ctx -> ctx.addTool(new EchoTool()).addMiddleware(middleware);

        // Act
        PluginRegistry.Result result = PluginRegistry.loadAndRegister(
                List.of(source(plugin)), context(), toolkit);

        // Assert
        assertThat(result.discovered).isEqualTo(1);
        assertThat(result.loaded).hasSize(1);
        assertThat(result.toolsRegistered).contains("pluginEcho");
        assertThat(toolkit.getToolNames()).contains("pluginEcho");
        assertThat(result.middlewares).containsExactly(middleware);
        assertThat(result.toolInstances).hasSize(1);
    }

    // --- compose with ToolRegistrar de-dup: built-in wins (first-wins) ---

    @Test
    void pluginToolCollidingWithBuiltin_isSkipped_builtinWins() {
        // Arrange — a built-in-like tool named "greet" is already in the toolkit
        Toolkit toolkit = new Toolkit();
        toolkit.registration().tool(new BuiltinGreetTool()).apply();
        Plugin plugin = ctx -> ctx.addTool(new PluginGreetTool());

        // Act
        PluginRegistry.Result result = PluginRegistry.loadAndRegister(
                List.of(source(plugin)), context(), toolkit);

        // Assert — built-in kept, plugin's same-named tool skipped (not stacked)
        assertThat(result.toolsSkipped).contains("greet");
        assertThat(result.toolsRegistered).doesNotContain("greet");
        assertThat(toolkit.getToolNames()).contains("greet");
    }

    // --- fail-safe: a throwing plugin is isolated, its partial contributions discarded ---

    @Test
    void throwingPlugin_isIsolated_otherPluginsStillRegister() {
        // Arrange — a plugin that adds a tool then throws, between two healthy ones
        Toolkit toolkit = new Toolkit();
        Plugin healthy1 = ctx -> ctx.addTool(new EchoTool());
        Plugin throwing = ctx -> {
            ctx.addTool(new PartialTool()); // partial contribution — must be discarded
            throw new IllegalStateException("boom");
        };
        Plugin healthy2 = ctx -> ctx.addTool(new HealthyTool());

        // Act
        PluginRegistry.Result result = PluginRegistry.loadAndRegister(
                List.of(source(healthy1, throwing, healthy2)), context(), toolkit);

        // Assert — failure recorded, its partial tool discarded, healthy tools registered
        assertThat(result.failed).hasSize(1);
        assertThat(result.loaded).hasSize(2);
        assertThat(toolkit.getToolNames()).contains("pluginEcho", "healthyOp");
        assertThat(toolkit.getToolNames()).doesNotContain("partialTool");
        assertThat(result.toolsRegistered).doesNotContain("partialTool");
    }

    // --- discovery: id de-dup across sources (first-wins) ---

    @Test
    void duplicatePluginId_acrossSources_keepsFirst() {
        // Arrange — same id from two sources
        Plugin first = new IdentifiedPlugin("dup", new EchoTool());
        Plugin second = new IdentifiedPlugin("dup", new HealthyTool());

        // Act
        List<Plugin> discovered = PluginRegistry.discover(
                List.of(source(first), source(second)));

        // Assert — only the first survives
        assertThat(discovered).containsExactly(first);
    }

    // --- discovery: a failing source is isolated ---

    @Test
    void failingSource_isIsolated_otherSourcesStillDiscovered() {
        // Arrange
        Plugin good = ctx -> ctx.addTool(new EchoTool());
        PluginSource failing = () -> {
            throw new IllegalStateException("source down");
        };

        // Act
        List<Plugin> discovered = PluginRegistry.discover(List.of(failing, source(good)));

        // Assert
        assertThat(discovered).containsExactly(good);
    }

    // --- backward-compat: no plugins → no change ---

    @Test
    void noPlugins_leavesToolkitAndMiddlewaresUnchanged() {
        // Arrange
        Toolkit toolkit = new Toolkit();

        // Act
        PluginRegistry.Result result = PluginRegistry.loadAndRegister(List.of(), context(), toolkit);

        // Assert
        assertThat(result.discovered).isZero();
        assertThat(result.loaded).isEmpty();
        assertThat(result.toolsRegistered).isEmpty();
        assertThat(result.middlewares).isEmpty();
        assertThat(toolkit.getToolNames()).isEmpty();
    }

    @Test
    void nullSources_isTolerated() {
        // Arrange
        Toolkit toolkit = new Toolkit();

        // Act
        PluginRegistry.Result result = PluginRegistry.loadAndRegister(null, context(), toolkit);

        // Assert
        assertThat(result.discovered).isZero();
        assertThat(toolkit.getToolNames()).isEmpty();
    }

    // --- helpers ---

    private static PluginSource source(Plugin... plugins) {
        return () -> List.of(plugins);
    }

    // --- fixtures (public so AgentScope reflection can register the @Tool methods) ---

    public static final class EchoTool {
        @Tool(name = "pluginEcho", description = "echo")
        public String echo() {
            return "echo";
        }
    }

    public static final class HealthyTool {
        @Tool(name = "healthyOp", description = "healthy")
        public String op() {
            return "ok";
        }
    }

    public static final class PartialTool {
        @Tool(name = "partialTool", description = "should be discarded")
        public String op() {
            return "partial";
        }
    }

    public static final class BuiltinGreetTool {
        @Tool(name = "greet", description = "builtin greet")
        public String greet() {
            return "builtin";
        }
    }

    public static final class PluginGreetTool {
        @Tool(name = "greet", description = "plugin greet")
        public String greet() {
            return "plugin";
        }
    }

    /** A do-nothing middleware fixture (all MiddlewareBase hooks default). */
    static final class NoopMiddleware implements MiddlewareBase {
    }

    /** A plugin with a fixed id, contributing one tool. */
    static final class IdentifiedPlugin implements Plugin {
        private final String id;
        private final Object tool;

        IdentifiedPlugin(String id, Object tool) {
            this.id = id;
            this.tool = tool;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public void register(PluginContext ctx) {
            ctx.addTool(tool);
        }
    }
}
