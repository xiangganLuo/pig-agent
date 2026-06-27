package io.pigagent.tool.mcp;

/** 人工确认回调。CLI 用 JLine reader 实现；agent 自助添加 MCP 服务器前经此确认（D-SEC）。 */
@FunctionalInterface
public interface McpConfirmer {
    boolean confirm(String prompt);
}
