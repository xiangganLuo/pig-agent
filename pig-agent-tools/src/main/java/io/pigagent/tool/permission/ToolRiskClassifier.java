package io.pigagent.tool.permission;

import java.util.Locale;
import java.util.Map;

/**
 * 把工具名映射到 {@link ToolRisk}。顺序：{@code tool-overrides} 覆盖 → 内置默认表 →
 * 未知一律 {@link ToolRisk#EXEC}（fail-safe，宁严勿松）。
 */
public final class ToolRiskClassifier {

    // 本表是「工具名 → 风险」的中央目录，与工具物理所在模块解耦（字符串键，无编译期反向依赖）：
    // 即便工具住在别的模块（如 pig-agent-plugin-builtin 的 webSearch/fetchUrl 与计算工具），仍在此登记分级。
    private static final Map<String, ToolRisk> DEFAULTS = Map.ofEntries(
            // 只读
            Map.entry("readFile", ToolRisk.READ_ONLY),
            Map.entry("listDirectory", ToolRisk.READ_ONLY),
            Map.entry("listMcpServers", ToolRisk.READ_ONLY),
            Map.entry("testMcpServer", ToolRisk.READ_ONLY),
            Map.entry("listSkills", ToolRisk.READ_ONLY),
            Map.entry("loadSkill", ToolRisk.READ_ONLY),
            // 延迟工具发现（deferred-tools）——只读：仅搜索/揭示元数据，不改任何状态
            Map.entry("tool_search", ToolRisk.READ_ONLY),
            // 内置计算插件（pig-agent-plugin-builtin）——纯计算工具，无 shell/网络/写盘
            Map.entry("currentDateTime", ToolRisk.READ_ONLY),
            Map.entry("convertTimezone", ToolRisk.READ_ONLY),
            Map.entry("epochToIso", ToolRisk.READ_ONLY),
            Map.entry("isoToEpoch", ToolRisk.READ_ONLY),
            Map.entry("generateUuid", ToolRisk.READ_ONLY),
            Map.entry("base64Encode", ToolRisk.READ_ONLY),
            Map.entry("base64Decode", ToolRisk.READ_ONLY),
            Map.entry("md5Hash", ToolRisk.READ_ONLY),
            Map.entry("sha256Hash", ToolRisk.READ_ONLY),
            Map.entry("jsonPrettyPrint", ToolRisk.READ_ONLY),
            Map.entry("jsonValidate", ToolRisk.READ_ONLY),
            Map.entry("randomNumber", ToolRisk.READ_ONLY),
            Map.entry("randomString", ToolRisk.READ_ONLY),
            // 清单展示——只读
            Map.entry("showChecklist", ToolRisk.READ_ONLY),
            // 写
            Map.entry("writeFile", ToolRisk.WRITE),
            // 清单创建/勾选——写（非 EXEC；auto 免确认、plan 拒绝）
            Map.entry("createChecklist", ToolRisk.WRITE),
            Map.entry("markComplete", ToolRisk.WRITE),
            // 执行
            Map.entry("executeCommand", ToolRisk.EXEC),
            // 网络
            Map.entry("fetchUrl", ToolRisk.NETWORK),
            // webSearch 归网络出口（与 fetchUrl 一致）：plan/EXPLORE 拒绝，防经搜索查询外泄上下文（Brave 走公网、不经 SSRF 守卫）
            Map.entry("webSearch", ToolRisk.NETWORK),
            // 主动外呼：经渠道给用户发通知（proactive-outreach）
            Map.entry("notifyUser", ToolRisk.NETWORK),
            // MCP 自助管理
            Map.entry("addMcpServer", ToolRisk.MCP_ADMIN),
            Map.entry("removeMcpServer", ToolRisk.MCP_ADMIN));

    private ToolRiskClassifier() {
    }

    /** 未知工具 → EXEC（最严）。overrides 值容错解析，非法值忽略回退默认表。 */
    public static ToolRisk classify(String toolName, Map<String, String> overrides) {
        if (toolName == null || toolName.isBlank()) {
            return ToolRisk.EXEC;
        }
        if (overrides != null) {
            String o = overrides.get(toolName);
            if (o != null && !o.isBlank()) {
                try {
                    return ToolRisk.valueOf(o.trim().toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException ignore) {
                    // 非法覆盖值 → 落到默认表
                }
            }
        }
        return DEFAULTS.getOrDefault(toolName, ToolRisk.EXEC);
    }
}
