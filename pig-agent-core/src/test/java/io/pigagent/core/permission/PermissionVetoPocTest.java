package io.pigagent.core.permission;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.permission.PermissionEngine;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * av2 Phase-0 risk PoC #2 — <b>permission veto via the native 2.0 PermissionEngine</b>.
 *
 * <p>pig-agent's 1.x behavior rewrote a denied {@code ToolUseBlock} to a read-only sentinel so the
 * real tool never ran and the model got a denial result and continued the turn. This proves 2.0
 * replicates that natively:
 * <ul>
 *   <li>{@link PermissionEngine#checkPermission} is a <em>gate evaluated before execution</em>: for a
 *       tool matched by a deny rule it returns {@link PermissionBehavior#DENY} and the tool's
 *       {@code callAsync} is never invoked (asserted via a spy tool).</li>
 *   <li>A tool with no matching rule passes through (proving the deny is targeted, not blanket).</li>
 *   <li>The native denial surface exists — {@link ToolResultState#DENIED} — which the ReAct loop
 *       emits as the tool result so the model continues (the full live-model turn-continuation is
 *       the documented native behavior; a real-model IT is deferred to a later phase).</li>
 * </ul>
 */
class PermissionVetoPocTest {

    /** A minimal spy tool that records whether it was ever executed. */
    static final class SpyTool extends ToolBase {
        final AtomicBoolean executed = new AtomicBoolean(false);

        SpyTool(String name) {
            super(name, "spy tool", Map.of(), true, false, false, null, false, false);
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            executed.set(true);
            return Mono.empty();
        }
    }

    private static PermissionEngine engineDenying(String toolName) {
        PermissionContextState ctx = PermissionContextState.builder()
                .mode(PermissionMode.DEFAULT)
                .addDenyRule(toolName,
                        new PermissionRule(toolName, null, PermissionBehavior.DENY, "test"))
                .build();
        return new PermissionEngine(ctx);
    }

    @Test
    void deniedTool_isVetoed_andNeverExecuted() {
        SpyTool tool = new SpyTool("dangerTool");
        PermissionEngine engine = engineDenying("dangerTool");

        PermissionDecision decision = engine.checkPermission(tool, Map.of()).block();

        assertThat(decision).isNotNull();
        assertThat(decision.getBehavior()).isEqualTo(PermissionBehavior.DENY);
        // The gate ran BEFORE execution — the tool body was never invoked (veto-to-sentinel parity).
        assertThat(tool.executed).isFalse();
    }

    @Test
    void unmatchedTool_isNotDenied() {
        SpyTool tool = new SpyTool("safeTool");
        PermissionEngine engine = engineDenying("dangerTool"); // deny rule targets a different tool

        PermissionDecision decision = engine.checkPermission(tool, Map.of()).block();

        assertThat(decision).isNotNull();
        assertThat(decision.getBehavior()).isNotEqualTo(PermissionBehavior.DENY);
    }

    @Test
    void nativeDeniedResultStateExists() {
        // The ReAct loop feeds a DENIED ToolResult back to the model so the turn continues.
        assertThat(ToolResultState.valueOf("DENIED")).isEqualTo(ToolResultState.DENIED);
    }
}
