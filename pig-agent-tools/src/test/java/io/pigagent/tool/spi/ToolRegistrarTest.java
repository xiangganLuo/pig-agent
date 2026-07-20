package io.pigagent.tool.spi;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.Toolkit;
import io.pigagent.task.TaskManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link ToolRegistrar}: SPI discovery of the full builtin set, discovery of a new
 * tool by "dropping a file", manual-override-with-audit, no-silent-duplicate, and fail-safe
 * isolation of a single failing provider/tool.
 */
class ToolRegistrarTest {

    private ToolContext context(Path skills) {
        return new ToolContext(mock(TaskManager.class), skills);
    }

    // --- SPI auto discovery (ServiceLoader path) ---

    @Test
    void registerAll_discoversFullCoreToolSet(@TempDir Path tmp) {
        // Arrange
        Toolkit toolkit = new Toolkit();

        // Act
        ToolRegistrar.Result result = ToolRegistrar.registerAll(toolkit, context(tmp), List.of());

        // Assert — every CORE builtin tool name is present (tools-core-slim): web search / web fetch /
        // checklist are no longer here — they moved to pig-agent-plugin-builtin as plugins.
        // NB: permissionDenied sentinel removed in the av2 native-permission re-architecture
        // (PermissionEngine + ToolResultState.DENIED replace the veto-to-sentinel mechanism).
        assertThat(toolkit.getToolNames()).contains(
                "executeCommand",
                "readFile", "writeFile", "editFile", "listDirectory",
                "searchFiles", "findFiles",
                "createTask", "listTasks", "updateTaskStatus",
                "listSkills", "loadSkill");
        assertThat(toolkit.getToolNames()).doesNotContain("permissionDenied");
        assertThat(toolkit.getToolNames()).doesNotContain("fetchUrl", "webSearch");
        assertThat(result.failures).isEmpty();
        assertThat(result.duplicatesSkipped).isEmpty();
    }

    @Test
    void registerAll_discoversNewExampleTool_withoutWiringEdit(@TempDir Path tmp) {
        // Arrange — ExampleToolProvider is declared only in the test-scoped services file
        Toolkit toolkit = new Toolkit();

        // Act
        ToolRegistrar.Result result = ToolRegistrar.registerAll(toolkit, context(tmp), List.of());

        // Assert — discovered purely by "dropping a file"
        assertThat(result.registered).contains("exampleEcho");
        assertThat(toolkit.getToolNames()).contains("exampleEcho");
    }

    // --- manual override + audit (D2) ---

    @Test
    void manualOverride_replacesAutoAndRecordsOverride() {
        // Arrange — auto provider registers "greet"; manual tool with the same name overrides it
        Toolkit toolkit = new Toolkit();
        List<ToolProvider> autoProviders = List.of(ctx -> new AutoGreetTool());

        // Act
        ToolRegistrar.Result result = ToolRegistrar.register(
                toolkit, autoProviders, null, List.of(new ManualGreetTool()));

        // Assert
        assertThat(result.overrides).contains("greet");
        assertThat(result.registered).contains("greet");
        assertThat(toolkit.getToolNames()).contains("greet");
    }

    // --- no silent duplicate (D2) ---

    @Test
    void duplicateAutoTools_keepFirstAndRecordSkip_noSilentStack() {
        // Arrange — two auto providers both contribute the "greet" tool name
        Toolkit toolkit = new Toolkit();
        List<ToolProvider> providers = List.of(
                ctx -> new AutoGreetTool(),
                ctx -> new ManualGreetTool()); // same tool name "greet", different class

        // Act
        ToolRegistrar.Result result = ToolRegistrar.register(toolkit, providers, null, List.of());

        // Assert — first kept, second skipped (not silently stacked)
        assertThat(result.registered).contains("greet");
        assertThat(result.duplicatesSkipped).contains("greet");
        assertThat(toolkit.getToolNames()).contains("greet");
    }

    // --- fail-safe isolation (D3) ---

    @Test
    void providerInstantiationFailure_isIsolated_othersStillRegister() {
        // Arrange — a throwing provider between two healthy ones
        Toolkit toolkit = new Toolkit();
        ToolProvider throwing = ctx -> {
            throw new IllegalStateException("boom");
        };
        List<ToolProvider> providers = List.of(
                ctx -> new AutoGreetTool(),
                throwing,
                ctx -> new HealthyTool());

        // Act
        ToolRegistrar.Result result = ToolRegistrar.register(toolkit, providers, null, List.of());

        // Assert — failure recorded, healthy tools still registered
        assertThat(result.failures).isNotEmpty();
        assertThat(toolkit.getToolNames()).contains("greet", "healthyOp");
    }

    @Test
    void toolWithoutToolMethods_isSkipped() {
        // Arrange
        Toolkit toolkit = new Toolkit();
        List<ToolProvider> providers = List.of(ctx -> new NotATool());

        // Act
        ToolRegistrar.Result result = ToolRegistrar.register(toolkit, providers, null, List.of());

        // Assert
        assertThat(result.registered).isEmpty();
        assertThat(toolkit.getToolNames()).isEmpty();
    }

    // --- registerTools (compose already-instantiated tools, e.g. from a plugin) ---

    @Test
    void registerTools_registersInstances_withNonOverrideDedup() {
        // Arrange — a tool already in the toolkit; then two instances via registerTools, one colliding
        Toolkit toolkit = new Toolkit();
        toolkit.registration().tool(new AutoGreetTool()).apply(); // occupies "greet"

        // Act
        ToolRegistrar.Result result = ToolRegistrar.registerTools(
                toolkit, List.of(new HealthyTool(), new ManualGreetTool()));

        // Assert — new name registered, colliding "greet" kept-first and skipped (not stacked)
        assertThat(result.registered).contains("healthyOp");
        assertThat(result.duplicatesSkipped).contains("greet");
        assertThat(toolkit.getToolNames()).contains("greet", "healthyOp");
    }

    @Test
    void registerTools_nullList_isNoOp() {
        // Arrange
        Toolkit toolkit = new Toolkit();

        // Act
        ToolRegistrar.Result result = ToolRegistrar.registerTools(toolkit, null);

        // Assert
        assertThat(result.registered).isEmpty();
        assertThat(toolkit.getToolNames()).isEmpty();
    }

    // --- test tool fixtures (public so AgentScope's reflection can register them) ---

    public static final class AutoGreetTool {
        @Tool(name = "greet", description = "auto greet")
        public String greetAuto() {
            return "auto";
        }
    }

    public static final class ManualGreetTool {
        @Tool(name = "greet", description = "manual greet")
        public String greetManual() {
            return "manual";
        }
    }

    public static final class HealthyTool {
        @Tool(name = "healthyOp", description = "healthy")
        public String healthyOp() {
            return "ok";
        }
    }

    public static final class NotATool {
        public String justAMethod() {
            return "no @Tool here";
        }
    }
}
