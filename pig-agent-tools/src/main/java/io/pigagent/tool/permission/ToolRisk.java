package io.pigagent.tool.permission;

/** 工具风险等级，驱动权限判定。未知工具按最严的 {@link #EXEC} 处理（fail-safe）。 */
public enum ToolRisk {
    /** 只读：读文件、列目录、搜索、连通测试、列技能等。所有模式放行。 */
    READ_ONLY,
    /** 写/改文件等可变但非执行的操作。 */
    WRITE,
    /** 执行 shell 命令（最高危）。 */
    EXEC,
    /** 网络出网（抓取 URL 等，SSRF/数据外泄面）。 */
    NETWORK,
    /** MCP 服务器自助增删（RCE/外泄面，已由 D-SEC 门内层控制）。 */
    MCP_ADMIN
}
