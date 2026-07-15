package io.pigagent.tool.loop;

import io.agentscope.core.tool.Tool;
import io.pigagent.core.loop.LoopDetectionHook;
import io.pigagent.core.loop.LoopMessages;

/**
 * 循环检测终止哨兵工具。{@link LoopDetectionHook} 判定一次工具调用陷入循环（重复次数达到 stop 阈值）时，
 * 把待执行的 {@code ToolUseBlock} 改写为指向此工具——真实工具因而不执行，模型收到本工具返回的收敛提示后
 * 停止重复、给出最终答复（对标 {@code PermissionDeniedTool} 的 veto-to-sentinel 机制）。
 *
 * <p>工具名 {@value #TOOL_NAME} 复用 {@link LoopDetectionHook#SENTINEL_TOOL_NAME}（单一事实源，
 * 与 hook 的改写目标名不漂移）；方法名必须与之一致（{@code @Tool} 默认取方法名）。
 */
public final class LoopDetectedTool {

    public static final String TOOL_NAME = LoopDetectionHook.SENTINEL_TOOL_NAME;

    @Tool(description = "循环检测终止占位（检测到重复的工具调用循环，已阻止继续重复）")
    public String loopDetected() {
        return LoopMessages.SENTINEL_STOP_TEXT;
    }
}
