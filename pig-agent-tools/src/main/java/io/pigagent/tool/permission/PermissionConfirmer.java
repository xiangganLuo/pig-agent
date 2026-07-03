package io.pigagent.tool.permission;

/**
 * 人工确认回调（三态 y/n/a）。实现由 CLI 提供（经 JLine 读 `readerRef`）。
 * 非交互场景（渠道）传 {@code null}，判定 ASK 时按 fail-closed 拒绝。
 */
@FunctionalInterface
public interface PermissionConfirmer {

    Outcome confirm(String prompt);

    /** 本次允许 / 始终允许（写 allowlist）/ 拒绝。 */
    enum Outcome {
        ALLOW_ONCE,
        ALLOW_ALWAYS,
        DENY
    }
}
