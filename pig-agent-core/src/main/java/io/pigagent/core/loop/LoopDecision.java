package io.pigagent.core.loop;

/**
 * The verdict a {@link LoopDetector} returns for one observed tool call.
 *
 * <ul>
 *   <li>{@link #OK} — the call is not (yet) a loop; proceed normally.</li>
 *   <li>{@link #WARN} — the same signature has repeated at/above the warn threshold; the model
 *       should be nudged to change approach, but the call is still allowed to run.</li>
 *   <li>{@link #STOP} — the same signature has repeated at/above the stop threshold; the call MUST
 *       be prevented so the agent is forced to stop looping and produce a final answer.</li>
 * </ul>
 */
public enum LoopDecision {
    OK,
    WARN,
    STOP
}
