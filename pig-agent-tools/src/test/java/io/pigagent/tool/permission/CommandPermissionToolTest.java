package io.pigagent.tool.permission;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.permission.PermissionEngine;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.pigagent.tool.contract.GuardedAgentTool;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Command-granular allowlist (M-1, av2 Phase-6b): {@link CommandPermissionTool} ALLOWs a command whose
 * normalized first token is in {@code allowlist.commands}, and PASSTHROUGHs everything else (deferring
 * to the mode default). The normalization must not be bypassable: quoting / path-prefix / operator
 * tricks that don't literally start with an allowlisted token fall through (never a spurious ALLOW).
 */
class CommandPermissionToolTest {

    /** Minimal executeCommand-shaped delegate; execution is irrelevant to the permission tests. */
    private static final class Delegate extends ToolBase {
        Delegate() {
            super("executeCommand", "exec", Map.of("type", "object"), false, false, false, null, false, false);
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            return Mono.just(ToolResultBlock.text("ran"));
        }
    }

    private static CommandPermissionTool tool(Set<String> allowed) {
        return new CommandPermissionTool(new Delegate(), () -> allowed);
    }

    private static PermissionBehavior decide(CommandPermissionTool t, String command) {
        return t.checkPermissions(Map.of("command", command), (PermissionContextState) null).block().getBehavior();
    }

    @Test
    void allowlistedCommandFirstTokenIsAllowed() {
        assertThat(decide(tool(Set.of("git")), "git status")).isEqualTo(PermissionBehavior.ALLOW);
        assertThat(decide(tool(Set.of("mvn")), "mvn test")).isEqualTo(PermissionBehavior.ALLOW);
        assertThat(decide(tool(Set.of("ls")), "ls")).isEqualTo(PermissionBehavior.ALLOW);
    }

    @Test
    void nonAllowlistedCommandPassesThrough() {
        assertThat(decide(tool(Set.of("git")), "rm -rf x")).isEqualTo(PermissionBehavior.PASSTHROUGH);
        assertThat(decide(tool(Set.of()), "git status")).isEqualTo(PermissionBehavior.PASSTHROUGH);
    }

    @Test
    void normalizationIsNotBypassable_pathQuoteOperatorTricks() {
        CommandPermissionTool t = tool(Set.of("git"));
        // Path-prefixed, quoted, or ;-glued first tokens do NOT equal "git" → no spurious ALLOW.
        assertThat(decide(t, "/usr/bin/git push")).isEqualTo(PermissionBehavior.PASSTHROUGH);
        assertThat(decide(t, "\"git\" push")).isEqualTo(PermissionBehavior.PASSTHROUGH);
        assertThat(decide(t, "git;rm -rf /")).isEqualTo(PermissionBehavior.PASSTHROUGH);
        assertThat(decide(t, "GIT status")).isEqualTo(PermissionBehavior.PASSTHROUGH);
    }

    @Test
    void emptyOrMissingCommandPassesThrough() {
        assertThat(decide(tool(Set.of("git")), "   ")).isEqualTo(PermissionBehavior.PASSTHROUGH);
        assertThat(tool(Set.of("git")).checkPermissions(Map.of(), (PermissionContextState) null)
                .block().getBehavior()).isEqualTo(PermissionBehavior.PASSTHROUGH);
    }

    @Test
    void allowlistIsReadLivePerCall() {
        Set<String> live = new HashSet<>();
        CommandPermissionTool t = new CommandPermissionTool(new Delegate(), () -> live);
        assertThat(decide(t, "git status")).isEqualTo(PermissionBehavior.PASSTHROUGH);
        live.add("git"); // e.g. user runs /permission allow git
        assertThat(decide(t, "git status")).isEqualTo(PermissionBehavior.ALLOW);
    }

    @Test
    void allowlistedCommandIsAllowedThroughTheContractGuardViaRealEngine() {
        // Production wraps executeCommand as: reflective ToolBase -> CommandPermissionTool ->
        // GuardedAgentTool. The native engine must reach the command check THROUGH the guard (the guard
        // delegates checkPermissions), and (per PermissionContextFactory) there is NO executeCommand ask
        // rule to shadow it. This is the genuine end-to-end M-1 proof.
        ToolBase cmdTool = new CommandPermissionTool(new Delegate(), () -> Set.of("git"));
        GuardedAgentTool guarded = new GuardedAgentTool(cmdTool);
        PermissionEngine engine = new PermissionEngine(
                PermissionContextState.builder().mode(PermissionMode.DEFAULT).build());

        PermissionDecision allowlisted =
                engine.checkPermission(guarded, Map.of("command", "git status")).block();
        assertThat(allowlisted.getBehavior()).isEqualTo(PermissionBehavior.ALLOW);

        // A non-allowlisted command falls through to the mode default (DEFAULT → ASK), not a spurious ALLOW.
        PermissionDecision other =
                engine.checkPermission(guarded, Map.of("command", "rm -rf x")).block();
        assertThat(other.getBehavior()).isEqualTo(PermissionBehavior.ASK);
    }

    @Test
    void matchRuleMatchesByNormalizedCommandKey() {
        CommandPermissionTool t = tool(Set.of());
        assertThat(t.matchRule("git", Map.of("command", "git status"))).isTrue();
        assertThat(t.matchRule("git", Map.of("command", "rm -rf"))).isFalse();
        assertThat(t.matchRule(null, Map.of("command", "anything"))).isTrue(); // null matches all
    }
}
