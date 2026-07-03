package io.pigagent.tool.permission;

/** 权限判定结果：直接放行 / 直接否决 / 需人工确认。 */
public enum PermissionDecision {
    ALLOW,
    DENY,
    ASK
}
