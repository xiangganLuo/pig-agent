package io.pigagent.core.memory;

import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.memory.LongTermMemory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.middleware.ReasoningInput;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * av2 Phase-0 risk PoC #1 — <b>ephemeral memory + prefix cache via 2.0 Middleware</b>.
 *
 * <p>Proves that {@link EphemeralMemoryMiddleware#onReasoning} injects retrieved long-term memory
 * into the model input for the current reasoning step <em>ephemerally</em> — a new
 * {@link ReasoningInput} is handed to {@code next}, while the incoming message list (which is
 * {@code AgentState.getContext()} at runtime) is never mutated — and that
 * {@code onSystemPrompt} is identity, keeping the system prompt byte-stable (prefix-cache friendly).
 * This is the forward-path replacement for the 1.x {@code EphemeralMemoryContextHook}.
 */
class EphemeralMemoryMiddlewareTest {

    private static final String MEMORY_MSG_NAME = "long_term_memory";

    /** A stub long-term memory returning fixed content. */
    private static LongTermMemory memoryReturning(String content) {
        return new LongTermMemory() {
            @Override
            public Mono<Void> record(List<Msg> messages) {
                return Mono.empty();
            }

            @Override
            public Mono<String> retrieve(Msg query) {
                return Mono.justOrEmpty(content);
            }
        };
    }

    private static Msg user(String text) {
        return Msg.builder().name("user").role(MsgRole.USER)
                .content(TextBlock.builder().text(text).build()).build();
    }

    private static ReasoningInput inputWith(List<Msg> messages) {
        return new ReasoningInput(messages, List.of(), null);
    }

    /** A capturing {@code next}: records the ReasoningInput it receives, returns an empty stream. */
    private static Function<ReasoningInput, Flux<AgentEvent>> capturing(AtomicReference<ReasoningInput> sink) {
        return in -> {
            sink.set(in);
            return Flux.empty();
        };
    }

    @Test
    void injectsMemoryAsTrailingUserMessage_withoutMutatingHistory() {
        EphemeralMemoryMiddleware mw = new EphemeralMemoryMiddleware(memoryReturning("MEMTOKEN"));
        List<Msg> history = new ArrayList<>(List.of(user("hello")));
        AtomicReference<ReasoningInput> seen = new AtomicReference<>();

        mw.onReasoning(null, null, inputWith(history), capturing(seen)).blockLast();

        // The model input for THIS step carries the memory as a trailing user message.
        List<Msg> modelInput = seen.get().messages();
        assertThat(modelInput).hasSize(2);
        Msg injected = modelInput.get(modelInput.size() - 1);
        assertThat(injected.getRole()).isEqualTo(MsgRole.USER);
        assertThat(injected.getName()).isEqualTo(MEMORY_MSG_NAME);
        assertThat(injected.getTextContent()).contains("MEMTOKEN");

        // Ephemeral: the incoming history list is untouched (never written back / persisted).
        assertThat(history).hasSize(1);
        assertThat(history).noneMatch(m -> MEMORY_MSG_NAME.equals(m.getName()));
    }

    @Test
    void blankMemory_skipsInjection() {
        EphemeralMemoryMiddleware mw = new EphemeralMemoryMiddleware(memoryReturning(""));
        List<Msg> history = new ArrayList<>(List.of(user("hi")));
        AtomicReference<ReasoningInput> seen = new AtomicReference<>();

        mw.onReasoning(null, null, inputWith(history), capturing(seen)).blockLast();

        assertThat(seen.get().messages()).hasSize(1);
        assertThat(seen.get().messages()).noneMatch(m -> MEMORY_MSG_NAME.equals(m.getName()));
    }

    @Test
    void noUserMessage_skipsInjection() {
        EphemeralMemoryMiddleware mw = new EphemeralMemoryMiddleware(memoryReturning("MEMTOKEN"));
        Msg assistantOnly = Msg.builder().name("assistant").role(MsgRole.ASSISTANT)
                .content(TextBlock.builder().text("prior").build()).build();
        List<Msg> history = new ArrayList<>(List.of(assistantOnly));
        AtomicReference<ReasoningInput> seen = new AtomicReference<>();

        mw.onReasoning(null, null, inputWith(history), capturing(seen)).blockLast();

        assertThat(seen.get().messages()).hasSize(1);
        assertThat(seen.get().messages()).noneMatch(m -> MEMORY_MSG_NAME.equals(m.getName()));
    }

    @Test
    void onSystemPrompt_isIdentity_soPromptStaysByteStable() {
        EphemeralMemoryMiddleware mw = new EphemeralMemoryMiddleware(memoryReturning("MEMTOKEN"));
        String prompt = "SYSTEM_PROMPT_CONSTANT_XYZ";

        String out = mw.onSystemPrompt(null, null, prompt).block();

        assertThat(out).isEqualTo(prompt);
    }
}
