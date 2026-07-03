package io.pigagent.config;

import java.util.Locale;

/**
 * 工具权限模式（对标 Claude Code / opencode / hermes 的权限级别）。
 *
 * <ul>
 *   <li>{@link #PLAN} —— 只读：否决所有可变工具，agent 只产出计划。</li>
 *   <li>{@link #ASK} —— 逐次确认可变工具（默认）。</li>
 *   <li>{@link #AUTO} —— 自动放行编辑/网络，仍确认执行/MCP 管理（对标 acceptEdits）。</li>
 *   <li>{@link #BYPASS} —— 全部放行、无提示（完全信任 / 恢复旧行为）。</li>
 * </ul>
 */
public enum PermissionMode {
    PLAN,
    ASK,
    AUTO,
    BYPASS;

    /** 容错解析：null/空白/未知值一律回退到 {@code fallback}，绝不抛出。 */
    public static PermissionMode fromString(String value, PermissionMode fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return PermissionMode.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
