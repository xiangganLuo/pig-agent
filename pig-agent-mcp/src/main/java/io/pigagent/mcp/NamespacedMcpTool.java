package io.pigagent.mcp;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Namespacing decorator for an MCP-server tool: it exposes a namespaced name
 * {@code mcp__<server>__<tool>} to the model/{@code Toolkit} while delegating execution (and thus the
 * actual MCP {@code callTool}) to the wrapped raw {@link AgentTool} — whose own {@code getName()}
 * stays the raw tool name the server understands. This lets two MCP servers that expose the same tool
 * name coexist (T4, {@code mcp-namespace-and-capability-groups}), replacing the old
 * "collision ⇒ reject the whole server" behavior.
 *
 * <p><b>Why pig implements namespacing (load-bearing spike):</b> AgentScope 2.0.0 does <em>not</em>
 * namespace MCP tools — {@code McpClientManager} registers each tool by its raw {@code getName()} and
 * {@code McpTool.callAsync} calls the server with that same raw name (verified against the 2.0.0
 * sources). So namespacing must be a pig decorator: the namespaced name is a local presentation only,
 * never leaking into the protocol call.
 *
 * <p><b>Extends {@link ToolBase} (P0, security-critical):</b> the native {@code PermissionEngine} only
 * gates a tool resolved to a {@code ToolBase} (a non-{@code ToolBase} tool is auto-ALLOWed). Mirroring
 * {@link io.pigagent.tool.contract.GuardedAgentTool GuardedAgentTool}, this decorator is a
 * {@code ToolBase} and forwards the built-in permission hooks
 * ({@link #checkPermissions}/{@link #matchRule}/{@link #generateSuggestions}) to the wrapped tool, so a
 * read-only MCP tool's own {@code readOnlyHint}-based allow (and any pig rule keyed by the full
 * namespaced name) still governs. {@code isReadOnly()}/{@code isMcp()}/{@code getMcpName()} are
 * preserved from the delegate so risk classification and native read-only handling stay correct.
 *
 * <p><b>Hot-removal:</b> because this decorator is registered via {@code registration().agentTool(...)}
 * (not {@code .mcpClient(...)}), the native registration carries <em>no</em> {@code mcpClientName}, so
 * {@code Toolkit.removeMcpClient(server)} cannot find it. {@link McpManager} therefore tracks each
 * namespaced tool's name and removes it explicitly via {@code Toolkit.removeTool(namespacedName)}.
 */
public final class NamespacedMcpTool extends ToolBase {

    /** Namespace prefix identifying an MCP-server tool that pig has namespaced. */
    public static final String NAMESPACE_PREFIX = "mcp__";

    /** Separator between the server name and the raw tool name. */
    public static final String NAMESPACE_SEPARATOR = "__";

    private final AgentTool delegate;

    public NamespacedMcpTool(AgentTool delegate, String serverName) {
        // Use the builder (not the positional ctor) so the boolean flags map unambiguously: preserve the
        // delegate's readOnly/concurrencySafe/externalTool/stateInjected and mark this an MCP tool named
        // after the server. mcp(serverName) sets both isMcp()=true and getMcpName()=serverName.
        super(ToolBase.builder()
                .name(namespacedName(serverName, requireDelegate(delegate).getName()))
                .description(delegate.getDescription())
                .inputSchema(delegate.getParameters())
                .readOnly(delegate.isReadOnly())
                .concurrencySafe(concurrencySafe(delegate))
                .externalTool(externalTool(delegate))
                .stateInjected(stateInjected(delegate))
                .mcp(requireServer(serverName)));
        this.delegate = delegate;
    }

    /**
     * Build the namespaced name {@code mcp__<server>__<tool>} for a server + raw tool name. This is the
     * single source of truth for the naming scheme (used by both registration and removal tracking).
     */
    public static String namespacedName(String serverName, String rawToolName) {
        return NAMESPACE_PREFIX + requireServer(serverName) + NAMESPACE_SEPARATOR + rawToolName;
    }

    /** Whether a tool name is a pig-namespaced MCP tool ({@code mcp__server__tool}). */
    public static boolean isNamespaced(String toolName) {
        return toolName != null && toolName.startsWith(NAMESPACE_PREFIX);
    }

    /** The wrapped raw tool (its {@code getName()} is the raw name the MCP server understands). */
    public AgentTool delegate() {
        return delegate;
    }

    /** The raw tool name (what the MCP server is actually called with). */
    public String rawToolName() {
        return delegate.getName();
    }

    @Override
    public Map<String, Object> getOutputSchema() {
        return delegate.getOutputSchema();
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        // Delegate to the wrapped raw tool: its own getName() (the raw name) is what reaches the MCP
        // server, so the namespace never leaks into the protocol call.
        return delegate.callAsync(param);
    }

    @Override
    public Mono<PermissionDecision> checkPermissions(Map<String, Object> toolInput, PermissionContextState context) {
        if (delegate instanceof ToolBase tb) {
            return tb.checkPermissions(toolInput, context);
        }
        return super.checkPermissions(toolInput, context);
    }

    @Override
    public boolean matchRule(String ruleContent, Map<String, Object> toolInput) {
        if (delegate instanceof ToolBase tb) {
            return tb.matchRule(ruleContent, toolInput);
        }
        return super.matchRule(ruleContent, toolInput);
    }

    @Override
    public List<PermissionRule> generateSuggestions(Map<String, Object> toolInput) {
        if (delegate instanceof ToolBase tb) {
            return tb.generateSuggestions(toolInput);
        }
        return super.generateSuggestions(toolInput);
    }

    private static AgentTool requireDelegate(AgentTool delegate) {
        return Objects.requireNonNull(delegate, "delegate");
    }

    private static String requireServer(String serverName) {
        if (serverName == null || serverName.isBlank()) {
            throw new IllegalArgumentException("serverName must not be blank");
        }
        return serverName;
    }

    private static boolean concurrencySafe(AgentTool d) {
        return d instanceof ToolBase tb && tb.isConcurrencySafe();
    }

    private static boolean externalTool(AgentTool d) {
        return d instanceof ToolBase tb && tb.isExternalTool();
    }

    private static boolean stateInjected(AgentTool d) {
        return d instanceof ToolBase tb && tb.isStateInjected();
    }
}
