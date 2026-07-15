package io.pigagent.core.compression;

import io.agentscope.core.memory.Memory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.pigagent.core.agent.AgentHolder;
import io.pigagent.core.agent.PigAgent;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end test of the default {@code ModelSummarizer} path through {@link CompressionService}:
 * it asserts that the text actually handed to the summarizer <em>model</em> includes tool-result
 * content. Before the fix the summarizer fed the model only {@code getTextContent()}, dropping the
 * "what did that command/file return" context; this locks in that tool results now reach it.
 */
class ModelSummarizerToolAwareTest {

    /** Captures every message list handed to {@code Model.stream}, then returns a canned reply. */
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
                    .content(List.of(TextBlock.builder().text("SUMMARY").build()))
                    .finishReason("stop").build());
        }
    }

    private static Msg text(MsgRole role, String t) {
        return Msg.builder().name("m").role(role).content(TextBlock.builder().text(t).build()).build();
    }

    @Test
    void summarizerModelInput_includesToolResultContent() {
        // Arrange: a live agent on the capturing model; seed a conversation whose OLDER batch
        // (everything but the last 6 messages) contains a tool-result payload.
        CapturingModel model = new CapturingModel();
        AgentHolder holder = new AgentHolder(PigAgent.builder().name("main").model(model).build());
        Memory memory = holder.get().getMemory();
        memory.addMessage(text(MsgRole.USER, "please run the build"));
        memory.addMessage(Msg.builder().name("shell").role(MsgRole.TOOL)
                .content(ToolResultBlock.text("TOOLRESULTMARKER: BUILD SUCCESS, 42 tests")
                        .withIdAndName("c1", "shell")).build());
        for (int i = 0; i < 8; i++) {
            memory.addMessage(text(MsgRole.USER, "filler " + i));
        }
        CompressionService service = new CompressionService(holder, 1000, 0.8, true);

        // Act: manual compression → ModelSummarizer renders older messages and calls the model.
        boolean compressed = service.compressNow("sess-1");

        // Assert: the summarizer's model input carried the tool-result payload (not dropped).
        assertThat(compressed).isTrue();
        boolean toolContentReachedModel = model.captured.stream()
                .flatMap(List::stream)
                .anyMatch(m -> {
                    String t = m.getTextContent();
                    return t != null && t.contains("TOOLRESULTMARKER") && t.contains("BUILD SUCCESS");
                });
        assertThat(toolContentReachedModel)
                .as("tool-result content reached the summarizer model").isTrue();
    }
}
