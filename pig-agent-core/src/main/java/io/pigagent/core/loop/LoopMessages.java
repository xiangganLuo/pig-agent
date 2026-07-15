package io.pigagent.core.loop;

/**
 * Pure text used by loop detection: the WARN nudge injected into the model, and the STOP message
 * the sentinel tool returns. Kept separate so the wording is unit-testable and lives in one place.
 */
public final class LoopMessages {

    /** Message returned by the {@code loopDetected} sentinel tool when a call is hard-stopped. */
    public static final String SENTINEL_STOP_TEXT =
            "循环检测：你在反复执行同一个操作（相同工具 + 相同参数）。这通常说明当前方法不奏效。"
            + "请立刻停止重复该操作，改用不同的方法，或基于已有信息直接给出最终答复。";

    private LoopMessages() {
    }

    /**
     * The ephemeral, user-side nudge injected on WARN (before the model's next decision).
     *
     * @param toolName      the repeated tool
     * @param warnThreshold the warn threshold that was crossed (rendered as "at least N times", which
     *                      is always accurate: WARN first fires when the count reaches this value)
     */
    public static String warnText(String toolName, int warnThreshold) {
        String name = toolName == null || toolName.isBlank() ? "该工具" : toolName;
        return "循环提醒：你已连续至少 " + warnThreshold + " 次以几乎相同的参数调用「" + name + "」。"
                + "这看起来像在原地打转。请改变策略——换一种方法或不同的参数，"
                + "或者如果已有足够信息，请直接给出最终答复，不要继续重复同一操作。";
    }
}
