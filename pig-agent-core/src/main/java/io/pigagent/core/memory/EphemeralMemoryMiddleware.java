package io.pigagent.core.memory;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.memory.LongTermMemory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.state.AgentState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Injects retrieved long-term memory into the model call on the <em>user</em> side, ephemerally, and
 * records conversation insights back to long-term memory after each turn — the AgentScope 2.0
 * middleware replacement for the deleted 1.x {@code EphemeralMemoryContextHook} (av2 Phase 5a).
 *
 * <p><b>Injection half ({@link #onReasoning}).</b> Retrieved memory is appended as a trailing USER
 * message to a <em>new</em> {@link ReasoningInput} handed to {@code next}; the incoming message list
 * (which is {@code AgentState.getContext()} at runtime) is never mutated, so nothing is persisted.
 * {@link #onSystemPrompt} is identity, so the system prompt stays byte-stable across turns
 * (prefix-cache friendly) — memory rides on the user side, never in the system prompt. This preserves
 * the exact prefix-cache semantics of the 1.x hook (which injected on {@code PreReasoningEvent}, whose
 * list is rebuilt from {@code [system] + memory} each step and never written back).
 *
 * <p><b>Record half ({@link #onAgent}).</b> After the whole reply completes, the finished conversation
 * ({@code RuntimeContext.getAgentState().getContext()}) is handed to
 * {@link LongTermMemory#record(List)} (session-tier only via {@code CompositeLongTermMemory}; a no-op
 * when memory is disabled) — the 2.0 equivalent of the 1.x {@code PostCallEvent} record. It emits no
 * agent events and any failure is logged and swallowed, so recording never breaks a turn.
 *
 * <p><b>Per-turn cache.</b> {@code onReasoning} runs on every reasoning step but the query (the last
 * user message) is unchanged across a turn, so the delegate is wrapped in {@link CachingLongTermMemory}
 * to collapse the N per-turn disk reads to one (exactly as the 1.x hook did); {@code record}
 * invalidates the cache.
 */
public final class EphemeralMemoryMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(EphemeralMemoryMiddleware.class);
    private static final String MEMORY_MSG_NAME = "long_term_memory";

    private final LongTermMemory longTermMemory;

    public EphemeralMemoryMiddleware(LongTermMemory longTermMemory) {
        Objects.requireNonNull(longTermMemory, "longTermMemory");
        // Wrap once so retrieve() within a turn hits a single-slot cache (same as the 1.x hook).
        this.longTermMemory = longTermMemory instanceof CachingLongTermMemory
                ? longTermMemory
                : new CachingLongTermMemory(longTermMemory);
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
                    return Mono.just(input);
                })
                .flatMapMany(next);
    }

    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext ctx, AgentInput input,
                                    Function<AgentInput, Flux<AgentEvent>> next) {
        // Record insights AFTER the whole reply completes (the 2.0 equivalent of PostCallEvent), so the
        // finished conversation (incl. the assistant's response) is what gets recorded. Emits no events.
        return next.apply(input)
                .concatWith(Flux.defer(() -> recordConversation(agent, ctx)));
    }

    @Override
    public Mono<String> onSystemPrompt(Agent agent, RuntimeContext ctx, String currentPrompt) {
        // Identity: memory is injected user-side, so the system prompt is never altered and stays
        // byte-stable across turns (prefix-cache friendly).
        return Mono.justOrEmpty(currentPrompt);
    }

    /** Delegate recording to long-term memory (session-tier only; no-op when disabled). Emits nothing. */
    private Flux<AgentEvent> recordConversation(Agent agent, RuntimeContext ctx) {
        List<Msg> messages = conversationOf(agent, ctx);
        if (messages == null || messages.isEmpty()) {
            return Flux.empty();
        }
        return longTermMemory.record(new ArrayList<>(messages))
                .thenMany(Flux.<AgentEvent>empty())
                .onErrorResume(e -> {
                    log.warn("Failed to record to long-term memory: {}", e.getMessage());
                    return Flux.empty();
                });
    }

    /** The finished conversation for this call — from the runtime context's bound state slot. */
    private static List<Msg> conversationOf(Agent agent, RuntimeContext ctx) {
        AgentState state = ctx == null ? null : ctx.getAgentState();
        if (state == null && agent != null) {
            state = agent.getAgentState();
        }
        return state == null ? null : state.getContext();
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
