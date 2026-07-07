package io.pigagent.tool.contract;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

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
 * <p>Schema methods are delegated unchanged so the tool's name/description/parameters are preserved;
 * only {@link #callAsync} is guarded. Every caught failure is logged at {@code warn} (with the
 * stack for diagnosis) using the credential-redacted message.
 */
public final class GuardedAgentTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(GuardedAgentTool.class);

    private final AgentTool delegate;

    public GuardedAgentTool(AgentTool delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    /** The wrapped tool (exposed so callers can avoid double-wrapping). */
    public AgentTool delegate() {
        return delegate;
    }

    @Override
    public String getName() {
        return delegate.getName();
    }

    @Override
    public String getDescription() {
        return delegate.getDescription();
    }

    @Override
    public Map<String, Object> getParameters() {
        return delegate.getParameters();
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
}
