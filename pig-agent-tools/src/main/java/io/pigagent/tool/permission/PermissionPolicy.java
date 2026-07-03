package io.pigagent.tool.permission;

import io.pigagent.config.PermissionMode;

/**
 * 纯函数权限策略：给定模式、风险、是否命中 allowlist，产出判定。无 I/O，好测。
 *
 * <pre>
 * 风险\模式    PLAN   ASK    AUTO   BYPASS
 * READ_ONLY    ALLOW  ALLOW  ALLOW  ALLOW
 * WRITE        DENY   ASK    ALLOW  ALLOW
 * NETWORK      DENY   ASK    ALLOW  ALLOW
 * EXEC         DENY   ASK    ASK    ALLOW
 * MCP_ADMIN    DENY   ALLOW  ALLOW  ALLOW   （放行给内层 D-SEC 门，避免重复确认）
 * </pre>
 * allowlist 命中在 ask/auto 下短路为 ALLOW；但 PLAN 只读语义高于 allowlist（可变工具仍 DENY）。
 */
public final class PermissionPolicy {

    private PermissionPolicy() {
    }

    public static PermissionDecision decide(PermissionMode mode, ToolRisk risk, boolean allowlisted) {
        if (risk == ToolRisk.READ_ONLY) {
            return PermissionDecision.ALLOW;
        }
        if (mode == PermissionMode.BYPASS) {
            return PermissionDecision.ALLOW;
        }
        if (mode == PermissionMode.PLAN) {
            return PermissionDecision.DENY; // 只读模式否决一切可变工具，allowlist 不越权
        }
        // ask / auto
        if (allowlisted) {
            return PermissionDecision.ALLOW;
        }
        if (risk == ToolRisk.MCP_ADMIN) {
            return PermissionDecision.ALLOW; // 委托 D-SEC 门，不重复确认
        }
        if (mode == PermissionMode.AUTO && (risk == ToolRisk.WRITE || risk == ToolRisk.NETWORK)) {
            return PermissionDecision.ALLOW;
        }
        return PermissionDecision.ASK; // WRITE/NETWORK@ASK；EXEC@ASK/AUTO
    }
}
