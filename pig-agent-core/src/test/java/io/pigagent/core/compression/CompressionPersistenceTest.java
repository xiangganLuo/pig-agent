package io.pigagent.core.compression;

import io.agentscope.core.memory.Memory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.JsonFileAgentStateStore;
import io.pigagent.core.agent.AgentHolder;
import io.pigagent.core.agent.PigAgent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end proof of the HIGH compression-persistence fix on a real {@link PigAgent} backed by a
 * real {@link JsonFileAgentStateStore}: {@code compressNow(sessionId)} must rewrite the
 * {@code (pig, sessionId)} slot AND persist it, so a freshly-built agent sharing the same store reads
 * back the <em>compressed</em> conversation. Before the fix the rewrite targeted the wrong (default)
 * slot and was never saved, so the compression was silently reverted on the next turn's reload.
 */
class CompressionPersistenceTest {

    /** Fake model that returns a fixed one-line summary (feeds the {@code ModelSummarizer} seam). */
    static final class SummaryModel implements Model {
        @Override public String getModelName() { return "summary"; }

        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.just(ChatResponse.builder()
                    .content(List.of(TextBlock.builder().text("SUMMARY").build()))
                    .finishReason("stop").build());
        }
    }

    private static Msg user(String text) {
        return Msg.builder().name("user").role(MsgRole.USER)
                .content(TextBlock.builder().text(text).build()).build();
    }

    @Test
    void compressRewritesAndPersistsTheSessionSlot(@TempDir Path root) {
        JsonFileAgentStateStore store = new JsonFileAgentStateStore(root.resolve("state"));
        PigAgent agent = PigAgent.builder()
                .name("main").sysPrompt("sp").model(new SummaryModel())
                .stateStore(store).workspace(root)
                .build();
        AgentHolder holder = new AgentHolder(agent);

        // Seed the (pig, s1) slot with a conversation long enough to compress, and persist it.
        Memory slot = agent.getMemory("s1");
        for (int i = 0; i < 10; i++) {
            slot.addMessage(user("message " + i));
        }
        agent.saveTo("s1");

        CompressionService service = new CompressionService(holder, 1000, 0.8, true);
        boolean compressed = service.compressNow("s1");

        // In-memory rewrite landed on the right slot: 1 summary + 6 most-recent kept.
        assertThat(compressed).isTrue();
        assertThat(agent.getMemory("s1").getMessages()).hasSize(7);

        // The load-bearing assertion: a fresh agent on the SAME store reads back the COMPRESSED slot,
        // proving compressNow persisted the rewrite (not discarded on the next reload).
        PigAgent reopened = PigAgent.builder()
                .name("main").sysPrompt("sp").model(new SummaryModel())
                .stateStore(store).workspace(root)
                .build();
        List<Msg> restored = reopened.getMemory("s1").getMessages();
        assertThat(restored).hasSize(7);
        assertThat(restored.get(0).getTextContent()).contains("SUMMARY");

        agent.close();
        reopened.close();
    }
}
