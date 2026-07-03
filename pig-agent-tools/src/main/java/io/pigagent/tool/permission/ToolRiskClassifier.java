package io.pigagent.tool.permission;

import java.util.Locale;
import java.util.Map;

/**
 * 把工具名映射到 {@link ToolRisk}。顺序：{@code tool-overrides} 覆盖 → 内置默认表 →
 * 未知一律 {@link ToolRisk#EXEC}（fail-safe，宁严勿松）。
 */
public final class ToolRiskClassifier {

    private static final Map<String, ToolRisk> DEFAULTS = Map.ofEntries(
            // 只读
            Map.entry("readFile", ToolRisk.READ_ONLY),
            Map.entry("listDirectory", ToolRisk.READ_ONLY),
            Map.entry("webSearch", ToolRisk.READ_ONLY),
            Map.entry("listMcpServers", ToolRisk.READ_ONLY),
            Map.entry("testMcpServer", ToolRisk.READ_ONLY),
            Map.entry("listSkills", ToolRisk.READ_ONLY),
            Map.entry("loadSkill", ToolRisk.READ_ONLY),
            // 写
            Map.entry("writeFile", ToolRisk.WRITE),
            // 执行
            Map.entry("executeCommand", ToolRisk.EXEC),
            // 网络
            Map.entry("fetchUrl", ToolRisk.NETWORK),
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
