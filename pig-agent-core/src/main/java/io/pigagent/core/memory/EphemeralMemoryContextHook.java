package io.pigagent.core.memory;

import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostCallEvent;
import io.agentscope.core.hook.PreReasoningEvent;
import io.agentscope.core.memory.LongTermMemory;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Injects retrieved long-term memory into the model call on the <em>user</em> side, ephemerally,
 * and records conversation insights back to long-term memory after each turn.
 *
 * <p>This deliberately replaces AgentScope's built-in {@code STATIC_CONTROL} wiring
 * ({@code StaticLongTermMemoryHook}). That hook injects on {@code PreCallEvent}, whose modified
 * message list flows through {@code doCall → addToMemory} and is therefore <b>persisted into the
 * conversation history</b> (and to disk), accumulating a fresh memory snapshot every turn. This
 * hook instead injects on {@link PreReasoningEvent}, whose input list is rebuilt from
 * {@code [system] + memory.getMessages()} on every reasoning step and is never written back to
 * memory — so the injection is truly ephemeral and does not pollute or grow the history.
 *
 * <p>Behavior preserved from the built-in mechanism:
 * <ul>
 *   <li>The system prompt is untouched, so it stays byte-stable across turns (prefix-cache friendly).</li>
 *   <li>Memory is retrieved via {@link LongTermMemory#retrieve(Msg)} using the last user message as
 *       the query, keeping the two-tier merge + source labels of {@link CompositeLongTermMemory}.</li>
 *   <li>{@code record} still runs on {@link PostCallEvent}, delegating to the same
 *       {@link LongTermMemory#record(List)} (session-tier only), so recording behavior is unchanged.</li>
 *   <li>When memory is disabled, {@code retrieve} returns empty (no injection) and {@code record} is
 *       a no-op — matching the existing {@code /memory off} semantics.</li>
 * </ul>
 */
public final class EphemeralMemoryContextHook implements Hook {

    private static final Logger log = LoggerFactory.getLogger(EphemeralMemoryContextHook.class);
    private static final String MEMORY_MSG_NAME = "long_term_memory";
    private static final int PRIORITY = 50;

    private final LongTermMemory longTermMemory;

    public EphemeralMemoryContextHook(LongTermMemory longTermMemory) {
        this.longTermMemory = Objects.requireNonNull(longTermMemory, "longTermMemory");
    }

    @Override
    public int priority() {
        return PRIORITY;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (event instanceof PreReasoningEvent pre) {
            return (Mono<T>) injectMemory(pre);
        }
        if (event instanceof PostCallEvent post) {
            return (Mono<T>) recordConversation(post);
        }
        return Mono.just(event);
    }

    /** Append the retrieved memory as a trailing, ephemeral USER message (never persisted). */
    private Mono<PreReasoningEvent> injectMemory(PreReasoningEvent event) {
        List<Msg> input = event.getInputMessages();
        if (input == null || input.isEmpty()) {
            return Mono.just(event);
        }
        int queryIdx = lastUserMessageIndex(input);
        if (queryIdx < 0) {
            return Mono.just(event);
        }
        return longTermMemory.retrieve(input.get(queryIdx))
                .filter(memory -> memory != null && !memory.isEmpty())
                .map(memory -> {
                    List<Msg> augmented = new ArrayList<>(input);
                    augmented.add(memoryMessage(memory));
                    event.setInputMessages(augmented);
                    return event;
                })
                .defaultIfEmpty(event)
                .onErrorResume(e -> {
                    log.warn("Failed to retrieve from long-term memory: {}", e.getMessage());
                    return Mono.just(event);
                });
    }

    /** Delegate recording to the long-term memory (session-tier only; no-op when disabled). */
    private Mono<PostCallEvent> recordConversation(PostCallEvent event) {
        Memory memory = event.getMemory();
        List<Msg> messages = memory == null ? null : memory.getMessages();
        if (messages == null || messages.isEmpty()) {
            return Mono.just(event);
        }
        return longTermMemory.record(messages)
                .thenReturn(event)
                .onErrorResume(e -> {
                    log.warn("Failed to record to long-term memory: {}", e.getMessage());
                    return Mono.just(event);
                });
    }

    private static Msg memoryMessage(String memory) {
        return Msg.builder()
                .role(MsgRole.USER)
                .name(MEMORY_MSG_NAME)
                .content(TextBlock.builder().text(memory).build())
                .build();
    }

    private static int lastUserMessageIndex(List<Msg> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).getRole() == MsgRole.USER) {
                return i;
            }
        }
        return -1;
    }
}
