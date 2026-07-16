package io.pigagent.tool.contract;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.permission.PermissionEngine;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression guard for the av2 Phase-6b P0 finding: the native {@link PermissionEngine} only gates a
 * tool when the toolkit resolves it to a {@link ToolBase} (the ReAct acting phase auto-ALLOWs any tool
 * that is not a {@code ToolBase}). Since {@link io.pigagent.tool.contract.ToolContractGuard} wraps every
 * registered tool, the wrapper MUST itself be a {@code ToolBase} that delegates the built-in permission
 * check — otherwise guarding a tool silently disables permission enforcement for it (a bypass).
 */
class GuardedToolPermissionTest {

    /** A raw {@link ToolBase} spy — mirrors pig's reflective/shell tools, which ARE ToolBase pre-guard. */
    static final class SpyTool extends ToolBase {
        final AtomicBoolean executed = new AtomicBoolean(false);

        SpyTool(String name) {
            super(name, "spy", Map.of(), true, false, false, null, false, false);
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            executed.set(true);
            return Mono.just(ToolResultBlock.text("ran"));
        }
    }

    private static PermissionContextState denyCtx(String toolName) {
        return PermissionContextState.builder()
                .mode(PermissionMode.DEFAULT)
                .addDenyRule(toolName,
                        new PermissionRule(toolName, null, PermissionBehavior.DENY, "test"))
                .build();
    }

    @Test
    void guardedToolRemainsAToolBase_soThePermissionEngineCanGateIt() {
        Toolkit toolkit = new Toolkit();
        toolkit.registration().agentTool(new SpyTool("secretTool")).apply();

        ToolContractGuard.install(toolkit);

        AgentTool resolved = toolkit.getTool("secretTool");
        assertThat(resolved)
                .as("a guarded tool must stay a ToolBase or the native permission engine skips (auto-ALLOWs) it")
                .isInstanceOf(ToolBase.class);
    }

    @Test
    void permissionEngineDeniesAGuardedTool_endToEnd() {
        Toolkit toolkit = new Toolkit();
        toolkit.registration().agentTool(new SpyTool("secretTool")).apply();
        ToolContractGuard.install(toolkit);

        ToolBase guarded = (ToolBase) toolkit.getTool("secretTool");
        PermissionEngine engine = new PermissionEngine(denyCtx("secretTool"));

        PermissionDecision decision = engine.checkPermission(guarded, Map.of()).block();

        assertThat(decision).isNotNull();
        assertThat(decision.getBehavior()).isEqualTo(PermissionBehavior.DENY);
    }
}
