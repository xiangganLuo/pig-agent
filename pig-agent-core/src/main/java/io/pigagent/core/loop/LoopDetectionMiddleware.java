package io.pigagent.core.loop;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ReasoningInput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/**
 * Adapter that plugs the pure {@link LoopDetector} into AgentScope 2.0's {@link MiddlewareBase}
 * (av2 Phase 5a middleware port of the deleted 1.x {@code LoopDetectionHook}). It carries no detection
 * logic itself (the detector owns that) — it only translates the acting/reasoning phases into detector
 * calls and detector verdicts into two reused mechanisms:
 *
 * <ul>
 *   <li><b>{@link #onActing}</b> is the detection point. Each pending {@link ToolUseBlock} in the
 *       acting phase is observed unless the middleware is disabled or the tool is in the ignore set
 *       (this sentinel; the native permission engine denies before execution so there is no permission
 *       sentinel to ignore). On {@link LoopDecision#STOP} the block is rewritten to the read-only
 *       {@link #SENTINEL_TOOL_NAME} sentinel in a <em>new</em> {@link ActingInput} handed to
 *       {@code next} (reusing the veto-to-sentinel mechanism), so the real tool never runs and the
 *       model receives a "you're looping, stop and answer" result. On {@link LoopDecision#WARN} a
 *       pending nudge is recorded and the tool runs unchanged.</li>
 *   <li><b>{@link #onReasoning}</b> does two things: (1) detect a turn boundary via the count of USER
 *       messages in the reasoning input and {@link LoopDetector#reset()} on change, so counts never
 *       leak across unrelated turns; (2) if a WARN nudge is pending, inject it as a trailing user-side
 *       {@link Msg} into a new {@link ReasoningInput} — an <em>ephemeral</em> injection (never written
 *       back to history).</li>
 * </ul>
 *
 * <p><b>Ordering.</b> This middleware is placed early (more-outer) in the list so its {@code onReasoning}
 * sees the raw conversation and counts only real USER messages. Native long-term memory
 * ({@code pa-memory-native}) is injected into the <em>system prompt</em> (not the reasoning list), so it
 * does not affect this USER-message count.
 *
 * <p>State is per instance (per agent). The mutable fields ({@code lastUserMsgCount},
 * {@code pendingWarnToolName}) are guarded by {@code this}.
 */
public final class LoopDetectionMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(LoopDetectionMiddleware.class);

    /** Tool name the STOP verdict rewrites a call to — the single source of truth for the name. */
    public static final String SENTINEL_TOOL_NAME = "loopDetected";

    private final LoopDetector detector;
    private final BooleanSupplier enabled;
    private final Set<String> ignoredToolNames;

    private int lastUserMsgCount = -1;
    private String pendingWarnToolName;

    /**
     * @param detector         the pure detector this middleware adapts (per-agent instance)
     * @param enabled          read live so {@code loop-detection.enabled=false} fully bypasses
     * @param ignoredToolNames tool names never counted/rewritten (this sentinel)
     */
    public LoopDetectionMiddleware(LoopDetector detector, BooleanSupplier enabled, Set<String> ignoredToolNames) {
        this.detector = Objects.requireNonNull(detector, "detector");
        this.enabled = Objects.requireNonNull(enabled, "enabled");
        this.ignoredToolNames = Set.copyOf(Objects.requireNonNull(ignoredToolNames, "ignoredToolNames"));
    }

    @Override
    public Flux<AgentEvent> onReasoning(Agent agent, RuntimeContext ctx, ReasoningInput input,
                                        Function<ReasoningInput, Flux<AgentEvent>> next) {
        return next.apply(maybeResetAndInject(input));
    }

    @Override
    public Flux<AgentEvent> onActing(Agent agent, RuntimeContext ctx, ActingInput input,
                                     Function<ActingInput, Flux<AgentEvent>> next) {
        return next.apply(observeAndMaybeRewrite(input));
    }

    /** Turn-boundary reset via USER-message count, then inject any pending WARN nudge (ephemeral). */
    private synchronized ReasoningInput maybeResetAndInject(ReasoningInput input) {
        List<Msg> messages = input.messages();
        int userCount = countUserMessages(messages);
        if (userCount != lastUserMsgCount) {
            // New user turn (increase) or session switch / compression rewrite (change) — start fresh.
            detector.reset();
            pendingWarnToolName = null;
            lastUserMsgCount = userCount;
            return input;
        }
        if (pendingWarnToolName != null && messages != null && !messages.isEmpty()) {
            List<Msg> augmented = new ArrayList<>(messages);
            augmented.add(warnMessage(pendingWarnToolName));
            pendingWarnToolName = null;
            return new ReasoningInput(augmented, input.tools(), input.options());
        }
        return input;
    }

    /** Detection point: observe each pending call; STOP → veto-to-sentinel, WARN → record a nudge. */
    private synchronized ActingInput observeAndMaybeRewrite(ActingInput input) {
        if (!enabled.getAsBoolean()) {
            return input;
        }
        List<ToolUseBlock> calls = input.toolCalls();
        if (calls == null || calls.isEmpty()) {
            return input;
        }
        boolean rewritten = false;
        List<ToolUseBlock> out = new ArrayList<>(calls.size());
        for (ToolUseBlock tu : calls) {
            if (tu == null) {
                out.add(null);
                continue;
            }
            String name = tu.getName();
            if (name == null || ignoredToolNames.contains(name)) {
                out.add(tu);
                continue;
            }
            LoopDecision decision = detector.observe(name, tu.getInput());
            if (decision == LoopDecision.STOP) {
                log.warn("Loop detected: tool '{}' repeated to stop threshold — vetoing to sentinel", name);
                out.add(ToolUseBlock.builder()
                        .id(tu.getId())
                        .name(SENTINEL_TOOL_NAME)
                        .input(Map.of())
                        .build());
                pendingWarnToolName = null; // superseded by the hard stop
                rewritten = true;
            } else if (decision == LoopDecision.WARN) {
                log.info("Loop detected: tool '{}' repeated to warn threshold — nudging model", name);
                pendingWarnToolName = name;
                out.add(tu);
            } else {
                out.add(tu);
            }
        }
        return rewritten ? new ActingInput(out) : input;
    }

    private Msg warnMessage(String toolName) {
        return Msg.builder()
                .role(MsgRole.USER)
                .name("loop_detection")
                .content(TextBlock.builder().text(LoopMessages.warnText(toolName, detector.warnThreshold())).build())
                .build();
    }

    private static int countUserMessages(List<Msg> messages) {
        if (messages == null) {
            return 0;
        }
        int count = 0;
        for (Msg m : messages) {
            if (m != null && m.getRole() == MsgRole.USER) {
                count++;
            }
        }
        return count;
    }
}
