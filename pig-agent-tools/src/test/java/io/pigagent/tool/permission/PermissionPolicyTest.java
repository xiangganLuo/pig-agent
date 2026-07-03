package io.pigagent.tool.permission;

import io.pigagent.config.PermissionMode;
import org.junit.jupiter.api.Test;

import static io.pigagent.tool.permission.PermissionDecision.ALLOW;
import static io.pigagent.tool.permission.PermissionDecision.ASK;
import static io.pigagent.tool.permission.PermissionDecision.DENY;
import static org.assertj.core.api.Assertions.assertThat;

/** 权限策略矩阵回归（纯函数，4 模式 × 5 风险 + allowlist）。 */
class PermissionPolicyTest {

    private static PermissionDecision d(PermissionMode m, ToolRisk r) {
        return PermissionPolicy.decide(m, r, false);
    }

    @Test
    void readOnlyAlwaysAllowed() {
        for (PermissionMode m : PermissionMode.values()) {
            assertThat(d(m, ToolRisk.READ_ONLY)).as("READ_ONLY@" + m).isEqualTo(ALLOW);
        }
    }

    @Test
    void bypassAllowsEverything() {
        for (ToolRisk r : ToolRisk.values()) {
            assertThat(d(PermissionMode.BYPASS, r)).as("BYPASS@" + r).isEqualTo(ALLOW);
        }
    }

    @Test
    void planDeniesAllMutating() {
        assertThat(d(PermissionMode.PLAN, ToolRisk.WRITE)).isEqualTo(DENY);
        assertThat(d(PermissionMode.PLAN, ToolRisk.EXEC)).isEqualTo(DENY);
        assertThat(d(PermissionMode.PLAN, ToolRisk.NETWORK)).isEqualTo(DENY);
        assertThat(d(PermissionMode.PLAN, ToolRisk.MCP_ADMIN)).isEqualTo(DENY);
    }

    @Test
    void askConfirmsMutatingButDelegatesMcpAdmin() {
        assertThat(d(PermissionMode.ASK, ToolRisk.WRITE)).isEqualTo(ASK);
        assertThat(d(PermissionMode.ASK, ToolRisk.NETWORK)).isEqualTo(ASK);
        assertThat(d(PermissionMode.ASK, ToolRisk.EXEC)).isEqualTo(ASK);
        assertThat(d(PermissionMode.ASK, ToolRisk.MCP_ADMIN)).isEqualTo(ALLOW); // 委托 D-SEC
    }

    @Test
    void autoAllowsEditsAndNetworkButStillAsksExec() {
        assertThat(d(PermissionMode.AUTO, ToolRisk.WRITE)).isEqualTo(ALLOW);
        assertThat(d(PermissionMode.AUTO, ToolRisk.NETWORK)).isEqualTo(ALLOW);
        assertThat(d(PermissionMode.AUTO, ToolRisk.EXEC)).isEqualTo(ASK);
        assertThat(d(PermissionMode.AUTO, ToolRisk.MCP_ADMIN)).isEqualTo(ALLOW);
    }

    @Test
    void allowlistShortCircuitsInAskAndAutoButNotPlan() {
        assertThat(PermissionPolicy.decide(PermissionMode.ASK, ToolRisk.EXEC, true)).isEqualTo(ALLOW);
        assertThat(PermissionPolicy.decide(PermissionMode.AUTO, ToolRisk.EXEC, true)).isEqualTo(ALLOW);
        // PLAN 只读语义高于 allowlist
        assertThat(PermissionPolicy.decide(PermissionMode.PLAN, ToolRisk.EXEC, true)).isEqualTo(DENY);
    }
}
