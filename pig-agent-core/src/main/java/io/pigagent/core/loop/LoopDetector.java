package io.pigagent.core.loop;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Objects;

/**
 * Pure, offline-testable loop detector: a fixed-capacity sliding window of recent tool-call
 * signatures plus warn/stop thresholds. {@link #observe(String, Map)} folds a call into a signature
 * (via the injected {@link ToolCallSignatureStrategy}), pushes it into the window (evicting the
 * oldest when full), counts how many times that signature occurs in the current window, and maps
 * the count to a {@link LoopDecision}: {@code count >= stopThreshold ⇒ STOP};
 * {@code count >= warnThreshold ⇒ WARN}; else {@code OK}.
 *
 * <p>Constructor arguments are <b>defensively clamped</b> so bad config never throws or produces a
 * nonsensical policy: {@code windowSize ≥ 1}, {@code warnThreshold ≥ 1},
 * {@code stopThreshold ≥ warnThreshold}. {@link #reset()} clears the window at turn/conversation
 * boundaries so counts never leak across unrelated turns. This class holds no AgentScope types; the
 * middleware adapter ({@code LoopDetectionMiddleware}) translates decisions into acting/reasoning phases.
 *
 * <p>Thread-safety: {@code observe} and {@code reset} are {@code synchronized} because a hook may be
 * invoked on reactive scheduler threads.
 */
public final class LoopDetector {

    /** Default sliding-window capacity. */
    public static final int DEFAULT_WINDOW_SIZE = 20;
    /** Default repeat count at which a call is WARNed. */
    public static final int DEFAULT_WARN_THRESHOLD = 3;
    /** Default repeat count at which a call is hard-STOPped. */
    public static final int DEFAULT_STOP_THRESHOLD = 5;

    private final ToolCallSignatureStrategy signatureStrategy;
    private final int windowSize;
    private final int warnThreshold;
    private final int stopThreshold;
    private final Deque<String> window = new ArrayDeque<>();

    /** Detector with the default strategy and default thresholds. */
    public LoopDetector() {
        this(DEFAULT_WINDOW_SIZE, DEFAULT_WARN_THRESHOLD, DEFAULT_STOP_THRESHOLD);
    }

    /** Detector with the default {@link DefaultToolCallSignatureStrategy} and the given policy. */
    public LoopDetector(int windowSize, int warnThreshold, int stopThreshold) {
        this(new DefaultToolCallSignatureStrategy(), windowSize, warnThreshold, stopThreshold);
    }

    public LoopDetector(ToolCallSignatureStrategy signatureStrategy,
                        int windowSize, int warnThreshold, int stopThreshold) {
        this.signatureStrategy = Objects.requireNonNull(signatureStrategy, "signatureStrategy");
        this.windowSize = Math.max(1, windowSize);
        this.warnThreshold = Math.max(1, warnThreshold);
        this.stopThreshold = Math.max(this.warnThreshold, stopThreshold);
    }

    /**
     * Observe one tool call and decide. Mutates the window (adds this call's signature, evicting the
     * oldest if the window is full).
     */
    public synchronized LoopDecision observe(String toolName, Map<String, Object> input) {
        String signature = signatureStrategy.signature(toolName, input);
        window.addLast(signature);
        while (window.size() > windowSize) {
            window.removeFirst();
        }
        int count = 0;
        for (String s : window) {
            if (s.equals(signature)) {
                count++;
            }
        }
        if (count >= stopThreshold) {
            return LoopDecision.STOP;
        }
        if (count >= warnThreshold) {
            return LoopDecision.WARN;
        }
        return LoopDecision.OK;
    }

    /** Clear the window — call at turn/conversation boundaries so counts don't leak across turns. */
    public synchronized void reset() {
        window.clear();
    }

    public int windowSize() {
        return windowSize;
    }

    public int warnThreshold() {
        return warnThreshold;
    }

    public int stopThreshold() {
        return stopThreshold;
    }
}
