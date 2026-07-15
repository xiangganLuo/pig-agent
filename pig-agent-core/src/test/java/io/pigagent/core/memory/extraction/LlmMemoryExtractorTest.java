package io.pigagent.core.memory.extraction;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The LLM extractor with a fake {@link Model} (offline, mirrors {@code ModelSummarizerToolAwareTest}):
 * a fixed JSON reply → parsed facts; and graceful degradation when the model supplier fails.
 */
class LlmMemoryExtractorTest {

    /** Returns a canned reply for the extractor's single model call. */
    static final class CannedModel implements Model {
        private final String reply;

        CannedModel(String reply) {
            this.reply = reply;
        }

        @Override
        public String getModelName() {
            return "canned";
        }

        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.just(ChatResponse.builder()
                    .content(List.of(TextBlock.builder().text(reply).build()))
                    .finishReason("stop").build());
        }
    }

    private static Msg user(String t) {
        return Msg.builder().name("u").role(MsgRole.USER).content(TextBlock.builder().text(t).build()).build();
    }

    @Test
    void happyPath_parsesModelJsonIntoFacts() {
        String json = "[{\"subject\":\"language\",\"category\":\"user-preference\","
                + "\"statement\":\"Prefers Chinese\",\"confidence\":0.95,\"correction\":false}]";
        LlmMemoryExtractor extractor = new LlmMemoryExtractor(() -> new CannedModel(json));

        List<ExtractedFact> facts = extractor.extract(List.of(user("Please always answer in Chinese.")));

        assertThat(facts).hasSize(1);
        assertThat(facts.get(0).subject()).isEqualTo("language");
        assertThat(facts.get(0).category()).isEqualTo(FactCategory.USER_PREFERENCE);
        assertThat(facts.get(0).confidence()).isEqualTo(0.95);
    }

    @Test
    void modelSupplierThrows_returnsEmpty() {
        LlmMemoryExtractor extractor = new LlmMemoryExtractor(() -> {
            throw new IllegalStateException("no model configured");
        });
        assertThat(extractor.extract(List.of(user("hi")))).isEmpty();
    }

    @Test
    void nullModel_returnsEmpty() {
        LlmMemoryExtractor extractor = new LlmMemoryExtractor(() -> null);
        assertThat(extractor.extract(List.of(user("hi")))).isEmpty();
    }

    @Test
    void emptyTurn_returnsEmptyWithoutCallingModel() {
        LlmMemoryExtractor extractor = new LlmMemoryExtractor(() -> {
            throw new AssertionError("model must not be built for an empty turn");
        });
        assertThat(extractor.extract(List.of())).isEmpty();
    }

    @Test
    void nonJsonReply_returnsEmpty() {
        LlmMemoryExtractor extractor = new LlmMemoryExtractor(() -> new CannedModel("Sorry, nothing to note."));
        assertThat(extractor.extract(List.of(user("hi")))).isEmpty();
    }
}
