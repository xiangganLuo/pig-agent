package io.pigagent.mcp;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * T4 命名空间共存的确定性单测（不触网）：撞名服务器的命名空间注册（{@code mcp__server__tool}）、能力包
 * 分组（{@code mcp:<server>}）、以及命名空间工具的 pig 自管移除（{@code removeTool}，非
 * {@code removeMcpClient}）。attach 的「无撞名扁平 vs 撞名命名空间」决策依赖真实 client，随既有
 * {@link McpManagerTest} 约定在手动冒烟/IT 验证；此处以简单委托工具驱动被提取的注册/移除单元。
 */
class McpManagerNamespaceTest {

    private Toolkit toolkit;
    private McpManager mgr;

    /** A minimal concrete {@link ToolBase} standing in for a raw MCP tool. */
    static final class RawTool extends ToolBase {
        RawTool(String name, boolean readOnly) {
            super(ToolBase.builder().name(name).description("d:" + name)
                    .inputSchema(Map.of("type", "object")).readOnly(readOnly));
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            return Mono.just(ToolResultBlock.text("ok:" + getName()));
        }
    }

    private static Set<String> schemaNames(Toolkit toolkit) {
        return toolkit.getToolSchemas().stream().map(ToolSchema::getName).collect(Collectors.toSet());
    }

    @BeforeEach
    void setUp() {
        McpStore store = mock(McpStore.class);
        when(store.findAll()).thenReturn(List.of());
        toolkit = new Toolkit();
        mgr = new McpManager();
        mgr.initialize(store, toolkit, null);
    }

    @Test
    void namespacedServersEmptyByDefault() {
        assertThat(mgr.namespacedServers()).isEmpty();
    }

    @Test
    void registersToolsUnderNamespacedNamesInServerCapabilityPack() {
        mgr.registerNamespacedTools("srvB",
                List.of(new RawTool("search", true), new RawTool("deleteAll", false)));

        // Namespaced names coexist (they no longer collide with another server's flat "search").
        assertThat(toolkit.getToolNames()).contains("mcp__srvB__search", "mcp__srvB__deleteAll");
        assertThat(schemaNames(toolkit)).contains("mcp__srvB__search", "mcp__srvB__deleteAll");
        // Grouped into the server's active capability pack (schema-neutral) + tracked for self-removal.
        assertThat(mgr.managedToolGroups()).contains("mcp:srvB");
        assertThat(mgr.namespacedServers()).containsExactly("srvB");
    }

    @Test
    void twoServersWithSameToolNameCoexistWhenNamespaced() {
        // Server A registered a flat "search" (native path, simulated by a direct registration here);
        // server B collides, so B is namespaced — both coexist.
        toolkit.registration().tool(new Object() {
            @io.agentscope.core.tool.Tool(name = "search", description = "flat search", readOnly = true)
            public String search() {
                return "a";
            }
        }).apply();
        assertThat(toolkit.getToolNames()).contains("search");

        mgr.registerNamespacedTools("srvB", List.of(new RawTool("search", true)));

        assertThat(toolkit.getToolNames()).contains("search", "mcp__srvB__search");
    }

    @Test
    void namespacedServerToolsAreRemovedBySelfManagedRemoveTool() {
        mgr.registerNamespacedTools("srvB", List.of(new RawTool("search", true)));
        assertThat(toolkit.getToolNames()).contains("mcp__srvB__search");

        mgr.unregisterServerTools("srvB");

        assertThat(toolkit.getToolNames()).doesNotContain("mcp__srvB__search");
        assertThat(mgr.namespacedServers()).isEmpty();
    }

    @Test
    void unregisterNonNamespacedServerUsesNativePathAndIsSafe() {
        // A server not in the namespaced-tracking map falls to the native removeMcpClient path;
        // on an empty toolkit it is a harmless no-op and MUST NOT touch namespaced tracking.
        mgr.unregisterServerTools("neverRegistered");

        assertThat(mgr.namespacedServers()).isEmpty();
    }
}
