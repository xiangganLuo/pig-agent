package io.pigagent.core.memory;

import io.agentscope.core.memory.LongTermMemory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.pigagent.core.agent.PigAgent;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression tests locking the prefix-cache-context invariants: retrieved long-term memory is
 * injected on the <em>user</em> side (never the system prompt), the system prompt stays byte-stable
 * across turns even as memory changes, the injection is ephemeral (never persisted, so it does not
 * accumulate in the conversation history), {@code record} still writes to the session tier, and
 * {@code /memory off} neither injects nor records.
 *
 * <p>These assert the observable contract by capturing the exact message list handed to the model
 * and inspecting the persisted conversation, so they catch any regression that leaks memory into
 * the system prompt or re-persists the injection into history.
 */
class MemoryUserSideInjectionTest {

    private static final String SYS_PROMPT = "SYSTEM_PROMPT_CONSTANT_XYZ";
    private static final String MEM_ALPHA = "MEMALPHATOKEN";
    private static final String MEM_BETA = "MEMBETATOKEN";
    private static final String MEMORY_MSG_NAME = "long_term_memory";

    /** Captures every message list handed to {@code Model.stream}. */
    static final class CapturingModel implements Model {
        final List<List<Msg>> captured = new ArrayList<>();

        @Override
        public String getModelName() {
            return "capture";
        }

        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            captured.add(new ArrayList<>(messages));
            return Flux.just(ChatResponse.builder()
                    .content(List.of(TextBlock.builder().text("ok").build()))
                    .finishReason("stop").build());
        }
    }

    /** A controllable memory tier: retrieval content is mutable and recorded messages are captured. */
    static final class FakeMemory implements LongTermMemory {
        volatile String content = "";
        final List<Msg> recorded = new ArrayList<>();

        @Override
        public Mono<Void> record(List<Msg> messages) {
            recorded.addAll(messages);
            return Mono.empty();
        }

        @Override
        public Mono<String> retrieve(Msg query) {
            return Mono.just(content);
        }
    }

    private static Msg user(String text) {
        return Msg.builder().name("user").role(MsgRole.USER)
                .content(TextBlock.builder().text(text).build()).build();
    }

    private static String textOf(Msg m) {
        String t = m.getTextContent();
        return t == null ? "" : t;
    }

    private static Msg systemMsg(List<Msg> sent) {
        return sent.stream().filter(m -> m.getRole() == MsgRole.SYSTEM).findFirst().orElseThrow();
    }

    private static PigAgent agentWith(CapturingModel model, LongTermMemory memory) {
        return PigAgent.builder()
                .name("mem-test")
                .sysPrompt(SYS_PROMPT)
                .model(model)
                .longTermMemory(memory)
                .build();
    }

    @Test
    void memoryEnabled_injectedOnUserSide_notInSystemPrompt() {
        CapturingModel model = new CapturingModel();
        FakeMemory global = new FakeMemory();
        PigAgent agent = agentWith(model, new CompositeLongTermMemory(global, true));
        global.content = MEM_ALPHA;

        agent.stream(user("hello")).blockLast();

        List<Msg> sent = model.captured.get(0);
        assertThat(textOf(systemMsg(sent))).isEqualTo(SYS_PROMPT).doesNotContain(MEM_ALPHA);
        boolean userCarriesMemory = sent.stream()
                .anyMatch(m -> m.getRole() == MsgRole.USER && textOf(m).contains(MEM_ALPHA));
        assertThat(userCarriesMemory).isTrue();
    }

    @Test
    void systemPrompt_byteStableAcrossTurns_whenMemoryChanges() {
        CapturingModel model = new CapturingModel();
        FakeMemory global = new FakeMemory();
        PigAgent agent = agentWith(model, new CompositeLongTermMemory(global, true));

        global.content = MEM_ALPHA;
        agent.stream(user("turn one")).blockLast();
        global.content = MEM_BETA;
        agent.stream(user("turn two")).blockLast();

        String firstSys = textOf(systemMsg(model.captured.get(0)));
        String lastSys = textOf(systemMsg(model.captured.get(model.captured.size() - 1)));
        assertThat(firstSys).isEqualTo(lastSys).isEqualTo(SYS_PROMPT);
        assertThat(firstSys).doesNotContain(MEM_ALPHA).doesNotContain(MEM_BETA);
    }

    @Test
    void injection_isEphemeral_notPersistedIntoHistory() {
        CapturingModel model = new CapturingModel();
        FakeMemory global = new FakeMemory();
        PigAgent agent = agentWith(model, new CompositeLongTermMemory(global, true));
        global.content = MEM_ALPHA;

        agent.stream(user("hello")).blockLast();

        List<Msg> history = agent.getMemory().getMessages();
        // The original user message is present and unchanged.
        Msg originalUser = history.stream()
                .filter(m -> m.getRole() == MsgRole.USER && textOf(m).contains("hello"))
                .findFirst().orElseThrow();
        assertThat(textOf(originalUser)).isEqualTo("hello");
        // The injection is ephemeral: no history message carries the memory or the synthetic name.
        assertThat(history).noneMatch(m -> textOf(m).contains(MEM_ALPHA));
        assertThat(history).noneMatch(m -> MEMORY_MSG_NAME.equals(m.getName()));
    }

    @Test
    void injection_doesNotAccumulateAcrossTurns() {
        CapturingModel model = new CapturingModel();
        FakeMemory global = new FakeMemory();
        PigAgent agent = agentWith(model, new CompositeLongTermMemory(global, true));

        int turns = 3;
        for (int i = 0; i < turns; i++) {
            global.content = "MEM_TURN_" + i;
            agent.stream(user("turn " + i)).blockLast();
        }

        List<Msg> history = agent.getMemory().getMessages();
        long injectedInHistory = history.stream().filter(m -> MEMORY_MSG_NAME.equals(m.getName())).count();
        assertThat(injectedInHistory).as("no injected memory message persisted").isZero();
        // History grows only with the real conversation (one user + one assistant per turn),
        // NOT with a per-turn memory snapshot — so it does not accumulate injected memory.
        assertThat(history).hasSize(turns * 2);
    }

    @Test
    void record_writesConversationToSessionTierAfterTurn() {
        CapturingModel model = new CapturingModel();
        FakeMemory session = new FakeMemory();
        CompositeLongTermMemory memory = new CompositeLongTermMemory(new FakeMemory(), true);
        memory.setSessionMemory(session);
        PigAgent agent = agentWith(model, memory);

        agent.stream(user("please remember this fact")).blockLast();

        assertThat(session.recorded)
                .as("record delegated to the session tier after the turn")
                .anyMatch(m -> textOf(m).contains("please remember this fact"));
    }

    @Test
    void memoryDisabled_nothingInjectedAndRecordIsNoOp() {
        CapturingModel model = new CapturingModel();
        FakeMemory global = new FakeMemory();
        FakeMemory session = new FakeMemory();
        global.content = MEM_ALPHA;
        CompositeLongTermMemory memory = new CompositeLongTermMemory(global, false);
        memory.setSessionMemory(session);
        PigAgent agent = agentWith(model, memory);

        agent.stream(user("hi")).blockLast();

        List<Msg> sent = model.captured.get(0);
        assertThat(sent).noneMatch(m -> textOf(m).contains(MEM_ALPHA));
        assertThat(textOf(systemMsg(sent))).isEqualTo(SYS_PROMPT);
        assertThat(session.recorded).as("record is a no-op while memory is disabled").isEmpty();
    }
}
