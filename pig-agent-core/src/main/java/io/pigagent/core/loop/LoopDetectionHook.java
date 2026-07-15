package io.pigagent.core.loop;

import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PreActingEvent;
import io.agentscope.core.hook.PreReasoningEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * Adapter that plugs the pure {@link LoopDetector} into AgentScope's hook event model. It carries no
 * detection logic itself (the detector owns that) — it only translates events into detector calls
 * and detector verdicts into two reused mechanisms:
 *
 * <ul>
 *   <li><b>{@code PreActingEvent}</b> is the detection point. The pending {@link ToolUseBlock} is
 *       observed unless the hook is disabled or the tool is in the ignore set (this sentinel + the
 *       permission-deny sentinel — see below). On {@link LoopDecision#STOP} the block is rewritten
 *       to the read-only {@link #SENTINEL_TOOL_NAME} sentinel (reusing the veto-to-sentinel mechanism
 *       from the permission system), so the real tool never runs and the model receives a
 *       "you're looping, stop and answer" result. On {@link LoopDecision#WARN} a pending nudge is
 *       recorded and the tool is allowed to run.</li>
 *   <li><b>{@code PreReasoningEvent}</b> does two things: (1) detect a turn boundary via the count of
 *       USER messages in the reasoning input and {@link LoopDetector#reset()} on change, so counts
 *       never leak across unrelated turns; (2) if a WARN nudge is pending, inject it as a trailing
 *       user-side {@link Msg} into the reasoning input — the same <em>ephemeral</em> injection
 *       {@code EphemeralMemoryContextHook} uses (rebuilt each step from {@code [system]+memory},
 *       never written back), so the nudge reaches the model without polluting persisted history.</li>
 * </ul>
 *
 * <p><b>Ordering:</b> {@link #priority()} is {@value #PRIORITY} — after the permission veto hook
 * ({@code priority()=0}) so this never interferes with it. Because permission rewrites a vetoed call
 * to its own sentinel <em>before</em> this hook sees it, the permission sentinel name is placed in
 * the ignore set (so denied repeats aren't miscounted as loops); loop detection thus focuses on
 * calls that actually run and burn tokens.
 *
 * <p>State is per hook instance (per agent), matching the per-agent hook-list pattern. The mutable
 * fields ({@code lastUserMsgCount}, {@code pendingWarnToolName}) are guarded by {@code this}.
 */
public final class LoopDetectionHook implements Hook {

    private static final Logger log = LoggerFactory.getLogger(LoopDetectionHook.class);

    /** Tool name the STOP verdict rewrites a call to — the single source of truth for the name. */
    public static final String SENTINEL_TOOL_NAME = "loopDetected";

    /** After the permission veto ({@code 0}); before the logging hooks ({@code 50}/{@code 60}). */
    private static final int PRIORITY = 10;

    private final LoopDetector detector;
    private final BooleanSupplier enabled;
    private final Set<String> ignoredToolNames;

    private int lastUserMsgCount = -1;
    private String pendingWarnToolName;

    /**
     * @param detector         the pure detector this hook adapts (per-agent instance)
     * @param enabled          read live so {@code loop-detection.enabled=false} fully bypasses
     * @param ignoredToolNames tool names never counted/rewritten (this sentinel + permission sentinel)
     */
    public LoopDetectionHook(LoopDetector detector, BooleanSupplier enabled, Set<String> ignoredToolNames) {
        this.detector = Objects.requireNonNull(detector, "detector");
        this.enabled = Objects.requireNonNull(enabled, "enabled");
        this.ignoredToolNames = Set.copyOf(Objects.requireNonNull(ignoredToolNames, "ignoredToolNames"));
    }

    @Override
    public int priority() {
        return PRIORITY;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (event instanceof PreActingEvent pre) {
            onPreActing(pre);
        } else if (event instanceof PreReasoningEvent pre) {
            onPreReasoning(pre);
        }
        return Mono.just(event);
    }

    /** Detection point: observe the pending call; STOP → veto-to-sentinel, WARN → record a nudge. */
    private synchronized void onPreActing(PreActingEvent event) {
        if (!enabled.getAsBoolean()) {
            return;
        }
        ToolUseBlock tu = event.getToolUse();
        if (tu == null) {
            return;
        }
        String name = tu.getName();
        if (name == null || ignoredToolNames.contains(name)) {
            return;
        }
        LoopDecision decision = detector.observe(name, tu.getInput());
        if (decision == LoopDecision.STOP) {
            log.warn("Loop detected: tool '{}' repeated to stop threshold — vetoing to sentinel", name);
            event.setToolUse(ToolUseBlock.builder()
                    .id(tu.getId())
                    .name(SENTINEL_TOOL_NAME)
                    .input(Map.of())
                    .build());
            pendingWarnToolName = null; // superseded by the hard stop
        } else if (decision == LoopDecision.WARN) {
            log.info("Loop detected: tool '{}' repeated to warn threshold — nudging model", name);
            pendingWarnToolName = name;
        }
    }

    /** Turn-boundary reset via USER-message count, then inject any pending WARN nudge (ephemeral). */
    private synchronized void onPreReasoning(PreReasoningEvent event) {
        List<Msg> input = event.getInputMessages();
        int userCount = countUserMessages(input);
        if (userCount != lastUserMsgCount) {
            // New user turn (increase) or session switch / compression rewrite (change) — start fresh.
            detector.reset();
            pendingWarnToolName = null;
            lastUserMsgCount = userCount;
            return;
        }
        if (pendingWarnToolName != null && input != null && !input.isEmpty()) {
            List<Msg> augmented = new ArrayList<>(input);
            augmented.add(warnMessage(pendingWarnToolName));
            event.setInputMessages(augmented);
            pendingWarnToolName = null;
        }
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
