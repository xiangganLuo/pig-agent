package io.pigagent.tool.permission;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Command-granular allowlist for the shell-execution tool (security item <b>M-1</b>, av2 Phase-6b).
 *
 * <p>Native permission precedence is <em>deny &gt; ask &gt; allow &gt; tool-check</em>: a per-tool ASK
 * rule short-circuits <em>before</em> a tool's own {@code checkPermissions} runs, so a tool-level
 * ALLOW can never override an ASK rule. Because pig would otherwise map {@code executeCommand} to an
 * ASK rule, a user's remembered command (e.g. {@code git} added via {@code /permission allow} →
 * {@code allowlist.commands}) was ignored and re-confirmed every time. This {@link ToolBase} decorator
 * closes that gap: it wraps the reflective {@code executeCommand} tool, delegates execution unchanged
 * (the exec-sandbox {@code CommandGuard} inside still runs <em>after</em> the permission decision), and
 * overrides {@link #checkPermissions} to return {@code ALLOW} when the command's normalized first token
 * ({@link CommandKeys#of}) is in the live {@code allowlist.commands}. A non-allowlisted command returns
 * {@code PASSTHROUGH}, deferring to the mode default (interactive ASK / non-interactive fail-closed
 * DENY). For this to take effect, {@link PermissionContextFactory} deliberately does <em>not</em> emit
 * an ASK rule for {@code executeCommand} (which would shadow this check).
 *
 * <p><b>Not bypassable:</b> {@link CommandKeys#of} takes the first whitespace-delimited token of the
 * raw {@code command} argument, so quoting / path-prefix / operator tricks
 * ({@code "git"…}, {@code /usr/bin/git}, {@code git;rm …}) yield a key that simply does not equal a
 * plain allowlisted command and therefore fall through to the mode default (never a spurious ALLOW).
 * A matched command is still subject to the exec-sandbox denylist (e.g. {@code git && rm -rf /} matches
 * {@code git} here but the sandbox blocks the catastrophic sub-command) — defense in depth.
 */
public final class CommandPermissionTool extends ToolBase {

    private final ToolBase delegate;
    private final Supplier<Set<String>> allowlistedCommands;

    /**
     * @param delegate            the wrapped {@code executeCommand} tool (its schema + execution are
     *                            reused unchanged)
     * @param allowlistedCommands supplies the current {@code allowlist.commands} on every check (read
     *                            live so {@code /permission allow}/{@code revoke} take effect without a
     *                            rebuild); never {@code null}
     */
    public CommandPermissionTool(ToolBase delegate, Supplier<Set<String>> allowlistedCommands) {
        super(Objects.requireNonNull(delegate, "delegate").getName(), delegate.getDescription(),
                delegate.getParameters(), delegate.isConcurrencySafe(), delegate.isReadOnly(),
                delegate.isExternalTool(), delegate.getMcpName(), delegate.isStateInjected(),
                delegate.isMcp());
        this.delegate = delegate;
        this.allowlistedCommands = Objects.requireNonNull(allowlistedCommands, "allowlistedCommands");
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return delegate.callAsync(param);
    }

    @Override
    public Mono<PermissionDecision> checkPermissions(Map<String, Object> toolInput, PermissionContextState context) {
        String key = CommandKeys.of(toolInput);
        if (key != null) {
            Set<String> allowed = allowlistedCommands.get();
            if (allowed != null && allowed.contains(key)) {
                return Mono.just(PermissionDecision.allow("command allowlisted: " + key));
            }
        }
        // Not allowlisted → defer to the mode default (ASK interactive / DENY fail-closed).
        return Mono.just(PermissionDecision.passthrough("command not allowlisted"));
    }

    /**
     * A command-scoped rule (its {@code ruleContent} being a normalized command key) matches only a
     * call whose command normalizes to that same key; a {@code null} {@code ruleContent} matches all
     * calls (native convention). Lets a persisted command-granular ALLOW/DENY rule target one command.
     */
    @Override
    public boolean matchRule(String ruleContent, Map<String, Object> toolInput) {
        if (ruleContent == null) {
            return true;
        }
        return ruleContent.equals(CommandKeys.of(toolInput));
    }
}
