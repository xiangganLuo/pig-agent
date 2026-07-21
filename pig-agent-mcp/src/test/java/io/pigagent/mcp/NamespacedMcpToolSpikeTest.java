package io.pigagent.mcp;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Group-1 load-bearing spike for T4 ({@code mcp-namespace-and-capability-groups}). Proves — offline,
 * no network — the four mechanisms the namespacing design rests on:
 * <ol>
 *   <li><b>Decorator:</b> {@link NamespacedMcpTool} exposes {@code mcp__<server>__<tool>} while the
 *       wrapped delegate keeps the raw name (so the raw name reaches the MCP server), and preserves
 *       {@code isReadOnly()}/{@code isMcp()}/{@code getMcpName()}/description/params.</li>
 *   <li><b>Delegation:</b> {@code callAsync} forwards to the wrapped raw tool.</li>
 *   <li><b>Removal safety:</b> a namespaced tool registered via {@code agentTool(...)} carries no
 *       {@code mcpClientName}, so {@code removeMcpClient} cannot remove it — only
 *       {@code removeTool(namespacedName)} can (⇒ McpManager must self-manage removal).</li>
 *   <li><b>Schema-neutral grouping:</b> a tool in an ACTIVE group is schema-visible; deactivating the
 *       group hides it (the capability-pack toggle) while it stays registered.</li>
 * </ol>
 */
class NamespacedMcpToolSpikeTest {

    /** A minimal concrete {@link ToolBase} standing in for a raw MCP tool; records call delegation. */
    static final class RecordingTool extends ToolBase {
        final AtomicBoolean invoked = new AtomicBoolean(false);

        RecordingTool(String name, boolean readOnly) {
            super(ToolBase.builder().name(name).description("desc:" + name)
                    .inputSchema(Map.of("type", "object", "properties", Map.of("q", Map.of("type", "string"))))
                    .readOnly(readOnly).concurrencySafe(true));
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            invoked.set(true);
            return Mono.just(ToolResultBlock.text("ok:" + getName()));
        }
    }

    private static Set<String> schemaNames(Toolkit toolkit) {
        return toolkit.getToolSchemas().stream().map(ToolSchema::getName).collect(Collectors.toSet());
    }

    // 1.1 — decorator: namespaced name outward, raw name on the delegate (raw reaches the server).
    @Test
    void exposesNamespacedNameButKeepsDelegateRaw() {
        RecordingTool raw = new RecordingTool("search", true);

        NamespacedMcpTool ns = new NamespacedMcpTool(raw, "serverB");

        assertThat(ns.getName()).isEqualTo("mcp__serverB__search");
        assertThat(ns.rawToolName()).isEqualTo("search");
        assertThat(ns.delegate().getName()).isEqualTo("search");
        assertThat(NamespacedMcpTool.namespacedName("serverB", "search")).isEqualTo("mcp__serverB__search");
        assertThat(NamespacedMcpTool.isNamespaced("mcp__serverB__search")).isTrue();
        assertThat(NamespacedMcpTool.isNamespaced("search")).isFalse();
    }

    // 1.1 — read-only + MCP metadata preserved (for risk classification + native read-only allow).
    @Test
    void preservesReadOnlyAndMcpMetadata() {
        RecordingTool raw = new RecordingTool("search", true);

        NamespacedMcpTool ns = new NamespacedMcpTool(raw, "serverB");

        assertThat(ns.isReadOnly()).isTrue();
        assertThat(ns.isMcp()).isTrue();
        assertThat(ns.getMcpName()).isEqualTo("serverB");
        assertThat(ns.getDescription()).isEqualTo("desc:search");
        assertThat(ns.getParameters()).isEqualTo(raw.getParameters());
    }

    // 1.1 — a mutating (non-read-only) MCP tool MUST NOT become read-only via namespacing.
    @Test
    void nonReadOnlyDelegateStaysNonReadOnly() {
        NamespacedMcpTool ns = new NamespacedMcpTool(new RecordingTool("deleteAll", false), "fs");

        assertThat(ns.getName()).isEqualTo("mcp__fs__deleteAll");
        assertThat(ns.isReadOnly()).isFalse();
    }

    // 1.2 — callAsync delegates to the wrapped raw tool.
    @Test
    void callAsyncDelegatesToRawTool() {
        RecordingTool raw = new RecordingTool("search", true);
        NamespacedMcpTool ns = new NamespacedMcpTool(raw, "serverB");
        ToolCallParam param = ToolCallParam.builder().input(Map.of("q", "x")).build();

        ToolResultBlock result = ns.callAsync(param).block();

        assertThat(raw.invoked).isTrue();
        assertThat(result).isNotNull();
    }

    // 1.3 — removal safety: removeMcpClient can't reach a namespaced tool; removeTool can.
    @Test
    void removeMcpClientCannotRemoveNamespacedTool_butRemoveToolCan() {
        Toolkit toolkit = new Toolkit();
        toolkit.createToolGroup("mcp:serverB", "MCP server: serverB", true);
        NamespacedMcpTool ns = new NamespacedMcpTool(new RecordingTool("search", true), "serverB");
        toolkit.registration().agentTool(ns).group("mcp:serverB").apply();
        assertThat(toolkit.getToolNames()).contains("mcp__serverB__search");

        // Native hot-removal keys on the registration's mcpClientName (absent for the agentTool path),
        // so it silently no-ops on a pig-namespaced tool.
        toolkit.removeMcpClient("serverB").block();
        assertThat(toolkit.getToolNames()).contains("mcp__serverB__search");

        // Pig-managed removal by the tracked namespaced name works.
        toolkit.removeTool("mcp__serverB__search");
        assertThat(toolkit.getToolNames()).doesNotContain("mcp__serverB__search");
    }

    // 1.4 — schema-neutral grouping / capability-pack toggle.
    @Test
    void activeGroupIsSchemaVisible_deactivateHides_reactivateRestores() {
        Toolkit toolkit = new Toolkit();
        toolkit.createToolGroup("mcp:serverB", "MCP server: serverB", true);
        NamespacedMcpTool ns = new NamespacedMcpTool(new RecordingTool("search", true), "serverB");
        toolkit.registration().agentTool(ns).group("mcp:serverB").apply();
        assertThat(schemaNames(toolkit)).contains("mcp__serverB__search");

        // Deactivate the pack → hidden from schema, still registered (callable once re-activated).
        toolkit.updateToolGroups(java.util.List.of("mcp:serverB"), false);
        assertThat(schemaNames(toolkit)).doesNotContain("mcp__serverB__search");
        assertThat(toolkit.getToolNames()).contains("mcp__serverB__search");

        // Re-activate → back in schema.
        toolkit.updateToolGroups(java.util.List.of("mcp:serverB"), true);
        assertThat(schemaNames(toolkit)).contains("mcp__serverB__search");
    }
}
