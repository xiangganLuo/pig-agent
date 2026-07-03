package io.pigagent.tool.permission;

import io.pigagent.config.PermissionMode;
import io.pigagent.config.PigAgentConfig.PermissionConfig;

import java.util.Map;

/**
 * 权限判定的可测核心：把「分级 → allowlist 命中 → 策略 → ASK 确认 → 记忆」串起来，
 * 返回 true(放行)/false(否决)。confirmer/writer 可注入 stub，故整段无需 AgentScope 即可单测。
 */
public final class PermissionResolver {

    private PermissionResolver() {
    }

    /**
     * @param mode      本回合生效的模式（交互回合用 resolveMode，渠道回合用 resolveChannelMode）
     * @param confirmer ASK 时的人工确认；{@code null} 表示非交互（ASK 一律 fail-closed 拒绝）
     * @param writer    allowlist 持久化；{@code null} 时 ALLOW_ALWAYS 退化为本次允许
     * @return true=放行，false=否决
     */
    public static boolean resolve(PermissionConfig cfg, PermissionMode mode, String toolName,
                                  Map<String, Object> input, PermissionConfirmer confirmer,
                                  AllowlistWriter writer) {
        ToolRisk risk = ToolRiskClassifier.classify(toolName, cfg.getToolOverrides());
        String cmdKey = risk == ToolRisk.EXEC ? CommandKeys.of(input) : null;
        boolean allowlisted = cfg.getAllowlist().getTools().contains(toolName)
                || (cmdKey != null && cfg.getAllowlist().getCommands().contains(cmdKey));

        PermissionDecision decision = PermissionPolicy.decide(mode, risk, allowlisted);
        switch (decision) {
            case ALLOW:
                return true;
            case DENY:
                return false;
            case ASK:
            default:
                if (confirmer == null) {
                    return false; // 非交互 fail-closed
                }
                PermissionConfirmer.Outcome o = confirmer.confirm(buildPrompt(toolName, cmdKey));
                if (o == PermissionConfirmer.Outcome.DENY) {
                    return false;
                }
                if (o == PermissionConfirmer.Outcome.ALLOW_ALWAYS && writer != null) {
                    if (cmdKey != null) {
                        writer.rememberCommand(cmdKey);
                    } else {
                        writer.rememberTool(toolName);
                    }
                }
                return true;
        }
    }

    private static String buildPrompt(String toolName, String cmdKey) {
        return "允许执行工具 '" + toolName + "'"
                + (cmdKey != null ? "（命令: " + cmdKey + " …）" : "") + "？";
    }
}
