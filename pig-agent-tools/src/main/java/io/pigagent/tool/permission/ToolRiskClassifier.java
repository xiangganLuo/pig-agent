package io.pigagent.tool.permission;

import java.util.Locale;
import java.util.Map;

/**
 * 把工具名映射到 {@link ToolRisk}。顺序：{@code tool-overrides} 覆盖 → 内置默认表 →
 * 未知一律 {@link ToolRisk#EXEC}（fail-safe，宁严勿松）。
 */
public final class ToolRiskClassifier {

    /** MCP 命名空间工具名前缀（{@code mcp__server__tool}，T4）。 */
    private static final String MCP_NAMESPACE_PREFIX = "mcp__";

    // 本表是「工具名 → 风险」的中央目录，与工具物理所在模块解耦（字符串键，无编译期反向依赖）：
    // 即便工具住在别的模块（如 pig-agent-plugin-builtin 的 webSearch/fetchUrl 与计算工具），仍在此登记分级。
    private static final Map<String, ToolRisk> DEFAULTS = Map.ofEntries(
            // 只读
            Map.entry("readFile", ToolRisk.READ_ONLY),
            Map.entry("listDirectory", ToolRisk.READ_ONLY),
            // 富内置文件工具（builtin-file-tools）——纯 Java 内容搜索 / glob 文件名查找，只读遍历不改盘
            Map.entry("searchFiles", ToolRisk.READ_ONLY),
            Map.entry("findFiles", ToolRisk.READ_ONLY),
            Map.entry("listMcpServers", ToolRisk.READ_ONLY),
            Map.entry("testMcpServer", ToolRisk.READ_ONLY),
            Map.entry("listSkills", ToolRisk.READ_ONLY),
            Map.entry("loadSkill", ToolRisk.READ_ONLY),
            // checklist（pig-agent-plugin-builtin）——showChecklist 只读；创建/勾选是写（M-3）
            Map.entry("showChecklist", ToolRisk.READ_ONLY),
            // 延迟工具发现（deferred-tools）——只读：仅搜索/揭示元数据，不改任何状态
            Map.entry("tool_search", ToolRisk.READ_ONLY),
            // 技能语义匹配（skill-matching）——只读：按 query 排序技能元数据，不改任何状态
            Map.entry("skill_search", ToolRisk.READ_ONLY),
            // 原生两层记忆检索（pa-memory-native）——只读：扫固化层/日志层、读行区间、搜历史转录
            Map.entry("memory_search", ToolRisk.READ_ONLY),
            Map.entry("memory_get", ToolRisk.READ_ONLY),
            Map.entry("session_search", ToolRisk.READ_ONLY),
            Map.entry("session_list", ToolRisk.READ_ONLY),
            Map.entry("session_history", ToolRisk.READ_ONLY),
            // OS 层内置工具（os-tools）——纯 Java 跨平台系统自省，只读不改盘：
            // 主机快照 / 磁盘用量 / 进程列表（命令行脱敏）/ 读环境变量（密钥名遮蔽）/ PATH 上定位可执行文件
            Map.entry("systemInfo", ToolRisk.READ_ONLY),
            Map.entry("diskUsage", ToolRisk.READ_ONLY),
            Map.entry("listProcesses", ToolRisk.READ_ONLY),
            Map.entry("getEnvironment", ToolRisk.READ_ONLY),
            Map.entry("whichCommand", ToolRisk.READ_ONLY),
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
            // 写
            Map.entry("writeFile", ToolRisk.WRITE),
            // 富内置文件工具（builtin-file-tools）——editFile 对已存在文件做定位串替（非整篇覆盖）
            Map.entry("editFile", ToolRisk.WRITE),
            Map.entry("createChecklist", ToolRisk.WRITE),
            Map.entry("completeItem", ToolRisk.WRITE),
            // 原生记忆写（pa-memory-native）——memory_save 原子更新 MEMORY.md + 日志层
            Map.entry("memory_save", ToolRisk.WRITE),
            // 自主沉淀 skills（autonomous-skills）——起草 + 管理暂存草稿（永不安装；提升走人工门）
            Map.entry("proposeSkill", ToolRisk.WRITE),
            Map.entry("skillManage", ToolRisk.WRITE),
            // 用户画像写（user-profile）——updateProfile 确定性 set/merge 一个画像字段到 USER.md
            Map.entry("updateProfile", ToolRisk.WRITE),
            // 执行
            Map.entry("executeCommand", ToolRisk.EXEC),
            // 网络（webSearch 走 Brave 公网、不经 SSRF 守卫 → 与 fetchUrl 同归 NETWORK，H-1）
            Map.entry("webSearch", ToolRisk.NETWORK),
            Map.entry("fetchUrl", ToolRisk.NETWORK),
            // OS 网络探测（os-tools）——resolveHost/checkPort 只解析域名/连端口、不取内容，故不套 fetchUrl 的
            // SSRF 私网黑名单（探测 localhost/内网正是主用途），但仍归 NETWORK 受权限门控
            Map.entry("resolveHost", ToolRisk.NETWORK),
            Map.entry("checkPort", ToolRisk.NETWORK),
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
        // T4（命名空间感知，安全）：命名空间 MCP 工具（mcp__server__tool）只按<b>完整名</b>分类——上面的
        // overrides 已按完整名匹配；此处 fail-safe 到 EXEC，且 MUST NOT 回落 base 名去查内置表（否则某
        // 服务器把可变工具命名为 "readFile" 会被误继承 READ_ONLY，构成冒充/提权）。行为与 getOrDefault 一致
        // （命名空间名不在内置表），此显式分支使该安全保证永久、自证。
        if (toolName.startsWith(MCP_NAMESPACE_PREFIX)) {
            return ToolRisk.EXEC;
        }
        return DEFAULTS.getOrDefault(toolName, ToolRisk.EXEC);
    }
}
