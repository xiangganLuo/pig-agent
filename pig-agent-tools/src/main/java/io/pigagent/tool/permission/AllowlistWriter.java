package io.pigagent.tool.permission;

/**
 * 持久化 allowlist 的回调（用户选 `a`=始终允许时）。实现由 CLI 提供
 * （经 {@code ConfigurationManager.updateConfig} 写入 `permissions.allowlist` 并落盘）。
 */
public interface AllowlistWriter {

    /** 记住整个工具（后续该工具免确认）。 */
    void rememberTool(String toolName);

    /** 记住某条规范化命令键（后续同命令免确认）。 */
    void rememberCommand(String commandKey);
}
