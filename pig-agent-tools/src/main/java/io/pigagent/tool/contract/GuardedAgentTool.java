package io.pigagent.tool.contract;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Dispatch-layer safety net that wraps a delegate {@link AgentTool} so a tool can never break the
 * turn: whatever the delegate does — throw synchronously, return a failing {@code Mono}, or return
 * {@code null} — this decorator converts it into a canonical {@code {"error":...}}
 * ({@link ToolErrors}) result. This is the outer of the two contract layers (the inner being each
 * tool returning canonical results itself); either alone stops an exception from propagating, so
 * even a tool that violates the contract stays safe.
 *
 * <p><b>av2 Phase-6b — the guard is itself a {@link ToolBase} (security-critical).</b> The native
 * {@code PermissionEngine} only gates a tool when the toolkit resolves it to a {@code ToolBase}; the
 * ReAct acting phase <em>auto-ALLOWs</em> any resolved tool that is not a {@code ToolBase}. An earlier
 * revision implemented only {@link AgentTool}, so wrapping every tool with this guard silently
 * disabled pig's native permission enforcement in production (a bypass — guarded tools were never
 * permission-checked). Extending {@code ToolBase} and <em>delegating</em> the built-in permission
 * hooks ({@link #checkPermissions}/{@link #matchRule}/{@link #generateSuggestions}) to the wrapped
 * tool restores enforcement: a guarded tool is still a {@code ToolBase}, so deny/ask/allow rules and
 * the tool's own {@code checkPermissions} (e.g. the command-granular allowlist) all apply. The
 * schema-defining fields (name/description/parameters/readOnly/…) are snapshotted from the delegate at
 * construction and exposed via {@code ToolBase}'s final getters; only {@link #callAsync} is guarded.
 * Every caught failure is logged at {@code warn} (with the stack) using the credential-redacted message.
 */
public final class GuardedAgentTool extends ToolBase {

    private static final Logger log = LoggerFactory.getLogger(GuardedAgentTool.class);

    private final AgentTool delegate;

    public GuardedAgentTool(AgentTool delegate) {
        super(baseBuilder(Objects.requireNonNull(delegate, "delegate")));
        this.delegate = delegate;
    }

    /**
     * Snapshot the delegate's schema + {@code ToolBase} flags through the <b>named builder</b> so every
     * flag lands in the right field. The 9-arg positional {@code ToolBase(...)} ctor orders its booleans
     * {@code readOnly, concurrencySafe, mcp, mcpName, externalTool, stateInjected}; an earlier revision
     * called it in the wrong order, so {@code guarded.isReadOnly()} actually returned the delegate's
     * {@code concurrencySafe} and {@code isMcp()} returned {@code externalTool} — a P0 correctness bug
     * because the plan/EXPLORE read-only gate keys on {@code isReadOnly()}. The builder is order-safe.
     */
    private static ToolBase.Builder baseBuilder(AgentTool d) {
        ToolBase.Builder b = ToolBase.builder()
                .name(d.getName())
                .description(d.getDescription())
                .inputSchema(d.getParameters())
                .readOnly(d.isReadOnly())
                .concurrencySafe(concurrencySafe(d))
                .externalTool(externalTool(d))
                .stateInjected(stateInjected(d));
        // Guard is installed before MCP servers attach, so this never triggers in practice; preserved
        // defensively so an (unexpected) MCP delegate keeps its identity.
        if (mcp(d) && mcpName(d) != null) {
            b.mcp(mcpName(d));
        }
        return b;
    }

    /** The wrapped tool (exposed so callers can avoid double-wrapping). */
    public AgentTool delegate() {
        return delegate;
    }

    @Override
    public Map<String, Object> getOutputSchema() {
        return delegate.getOutputSchema();
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        try {
            Mono<ToolResultBlock> result = delegate.callAsync(param);
            if (result == null) {
                return Mono.just(fallback(param, new IllegalStateException("tool returned null")));
            }
            return result.onErrorResume(t -> Mono.just(fallback(param, t)));
        } catch (Throwable t) { // delegate threw before returning a Mono — still must not escape
            return Mono.just(fallback(param, t));
        }
    }

    /**
     * Delegate the built-in permission check to the wrapped tool when it is a {@code ToolBase} (so a
     * tool's own {@code checkPermissions} — e.g. the command-granular allowlist, or a native
     * dangerous-path guard — keeps working through the guard); otherwise fall back to the default.
     */
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

    /** Log the escaped failure (stack for diagnosis, redacted message) and build the canonical error. */
    private ToolResultBlock fallback(ToolCallParam param, Throwable t) {
        String name = toolName(param);
        log.warn("Tool '{}' failed; returning canonical error to the model: {}",
                name, CredentialSanitizer.sanitize(String.valueOf(t.getMessage())), t);
        String body = ToolErrors.message(t.getMessage());
        String id = toolId(param);
        return (id != null || name != null)
                ? ToolResultBlock.text(body).withIdAndName(id, name)
                : ToolResultBlock.text(body);
    }

    private String toolName(ToolCallParam param) {
        ToolUseBlock use = param == null ? null : param.getToolUseBlock();
        if (use != null && use.getName() != null) {
            return use.getName();
        }
        return delegate.getName();
    }

    private static String toolId(ToolCallParam param) {
        ToolUseBlock use = param == null ? null : param.getToolUseBlock();
        return use == null ? null : use.getId();
    }

    // Snapshot the ToolBase-only attributes from the delegate when it is one; safe defaults otherwise.
    // The guard is installed before MCP servers attach, so a guarded tool is never itself an MCP tool.
    private static boolean concurrencySafe(AgentTool d) {
        return d instanceof ToolBase tb && tb.isConcurrencySafe();
    }

    private static boolean externalTool(AgentTool d) {
        return d instanceof ToolBase tb && tb.isExternalTool();
    }

    private static boolean stateInjected(AgentTool d) {
        return d instanceof ToolBase tb && tb.isStateInjected();
    }

    private static boolean mcp(AgentTool d) {
        return d instanceof ToolBase tb && tb.isMcp();
    }

    private static String mcpName(AgentTool d) {
        return d instanceof ToolBase tb ? tb.getMcpName() : null;
    }
}
