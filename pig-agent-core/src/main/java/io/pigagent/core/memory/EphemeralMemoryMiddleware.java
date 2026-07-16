package io.pigagent.core.memory;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.memory.LongTermMemory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ReasoningInput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * AgentScope 2.0 forward-path replacement for {@link EphemeralMemoryContextHook}, proving the
 * "ephemeral memory + prefix-cache" equivalence point (av2 Phase-0 risk validation).
 *
 * <p>It injects retrieved long-term memory into the model input <b>per reasoning step</b>, as a
 * trailing user-side message, without ever writing it back to the conversation history — the exact
 * semantics of the 1.x hook, achieved natively via {@link MiddlewareBase#onReasoning}:
 * <ul>
 *   <li>{@code onReasoning} receives an immutable {@link ReasoningInput} record for the current
 *       step. Injection builds a <em>new</em> {@code ReasoningInput} with an augmented message list
 *       and passes it to {@code next} — the incoming list (backed by {@code AgentState.getContext()})
 *       is never mutated and nothing is persisted. This is what makes injection ephemeral.</li>
 *   <li>{@link MiddlewareBase#onSystemPrompt} is left as identity, so the system prompt stays
 *       byte-stable across turns (prefix-cache friendly) — memory rides on the user side, never in
 *       the system prompt.</li>
 * </ul>
 *
 * <p>Recording insights back to long-term memory (the 1.x {@code PostCallEvent} path) is a separate
 * concern; in 2.0 it belongs in a post-phase of {@code onAgent} (deferred to a later phase). This
 * class deliberately covers only the injection half — the prefix-cache-critical, previously-risky
 * behavior.
 */
public final class EphemeralMemoryMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(EphemeralMemoryMiddleware.class);
    private static final String MEMORY_MSG_NAME = "long_term_memory";

    private final LongTermMemory longTermMemory;

    public EphemeralMemoryMiddleware(LongTermMemory longTermMemory) {
        this.longTermMemory = Objects.requireNonNull(longTermMemory, "longTermMemory");
    }

    @Override
    public Flux<AgentEvent> onReasoning(Agent agent, RuntimeContext ctx, ReasoningInput input,
                                        Function<ReasoningInput, Flux<AgentEvent>> next) {
        List<Msg> messages = input.messages();
        int queryIdx = lastUserMessageIndex(messages);
        if (queryIdx < 0) {
            return next.apply(input);
        }
        return longTermMemory.retrieve(messages.get(queryIdx))
                .filter(memory -> memory != null && !memory.isBlank())
                .map(memory -> augment(input, memory))
                .defaultIfEmpty(input)
                .onErrorResume(e -> {
                    log.warn("Failed to retrieve from long-term memory: {}", e.getMessage());
                    return reactor.core.publisher.Mono.just(input);
                })
                .flatMapMany(next);
    }

    @Override
    public reactor.core.publisher.Mono<String> onSystemPrompt(Agent agent, RuntimeContext ctx, String currentPrompt) {
        // Identity: memory is injected user-side, so the system prompt is never altered and stays
        // byte-stable across turns (prefix-cache friendly).
        return reactor.core.publisher.Mono.justOrEmpty(currentPrompt);
    }

    /** Build a NEW ReasoningInput with the memory appended — the incoming input is untouched. */
    private static ReasoningInput augment(ReasoningInput input, String memory) {
        List<Msg> augmented = new ArrayList<>(input.messages());
        augmented.add(memoryMessage(memory));
        return new ReasoningInput(augmented, input.tools(), input.options());
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
