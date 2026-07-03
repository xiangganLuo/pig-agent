package io.pigagent.tool.permission;

import io.agentscope.core.tool.Tool;

/**
 * 权限拒绝哨兵工具。{@link ToolPermissionHook} 否决一次调用时，把待执行的 {@code ToolUseBlock}
 * 改写为指向此工具——真实工具因而不执行，模型收到本工具返回的拒绝说明后继续对话
 * （见 step-0 spike {@code PermissionVetoSpikeIT}）。工具名 = 方法名 {@value #TOOL_NAME}。
 */
public final class PermissionDeniedTool {

    public static final String TOOL_NAME = "permissionDenied";

    @Tool(description = "权限拒绝占位（当前模式不允许执行该可变工具）")
    public String permissionDenied() {
        return "权限拒绝：当前权限模式不允许执行该工具，或用户拒绝了本次调用。"
                + "若处于 plan（只读）模式，请产出计划或说明你的下一步意图，而不要直接执行可变操作。";
    }
}
