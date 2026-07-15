package io.pigagent.core.memory.extraction;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pipeline orchestration with a MOCKED extractor (the {@link MemoryExtractor} seam) + an in-memory
 * {@link FactStore}: confidence gating + merge + write, graceful degradation, noise stripping.
 */
class MemoryExtractionPipelineTest {

    /** In-memory fact store recording writes. */
    static final class FakeStore implements FactStore {
        List<ExtractedFact> existing = List.of();
        List<ExtractedFact> lastSaved;
        int saveCount;

        @Override
        public List<ExtractedFact> load() {
            return existing;
        }

        @Override
        public void save(List<ExtractedFact> facts) {
            lastSaved = facts;
            existing = facts;
            saveCount++;
        }
    }

    private static Msg user(String t) {
        return Msg.builder().name("u").role(MsgRole.USER).content(TextBlock.builder().text(t).build()).build();
    }

    private static ExtractedFact fact(String subject, double conf, boolean correction) {
        return new ExtractedFact(subject, FactCategory.PROJECT_FACT, "stmt-" + subject, conf, correction);
    }

    private MemoryExtractionPipeline pipeline(MemoryExtractor extractor, FactStore store, double threshold) {
        return new MemoryExtractionPipeline(new MemoryNoiseFilter(), extractor,
                new ConfidenceGate(), new FactMerger(), store, () -> threshold);
    }

    @Test
    void gatesLowConfidence_thenMergesAndWrites() {
        FakeStore store = new FakeStore();
        MemoryExtractor extractor = turn -> List.of(fact("a", 0.9, false), fact("b", 0.5, false));

        pipeline(extractor, store, 0.7).process(List.of(user("hi")));

        assertThat(store.saveCount).isEqualTo(1);
        assertThat(store.lastSaved).extracting(ExtractedFact::subject).containsExactly("a");
    }

    @Test
    void extractorThrowing_isSwallowed_storeUntouched() {
        FakeStore store = new FakeStore();
        store.existing = List.of(fact("keep", 0.9, false));
        MemoryExtractor extractor = turn -> {
            throw new RuntimeException("model blew up");
        };

        // Must not throw, and must not touch the store (existing memory preserved).
        pipeline(extractor, store, 0.7).process(List.of(user("hi")));

        assertThat(store.saveCount).isZero();
        assertThat(store.existing).extracting(ExtractedFact::subject).containsExactly("keep");
    }

    @Test
    void noDurableFacts_leavesStoreUntouched() {
        FakeStore store = new FakeStore();
        pipeline(turn -> List.of(), store, 0.7).process(List.of(user("hi")));
        assertThat(store.saveCount).isZero();
    }

    @Test
    void noiseStrippedBeforeExtractor() {
        FakeStore store = new FakeStore();
        List<Msg> seenByExtractor = new ArrayList<>();
        MemoryExtractor capturing = turn -> {
            seenByExtractor.addAll(turn);
            return List.of(fact("a", 0.9, false));
        };
        Msg substantive = user("The project uses Java 17 and Maven.");
        Msg toolResult = Msg.builder().name("shell").role(MsgRole.TOOL)
                .content(ToolResultBlock.text("BUILD SUCCESS").withIdAndName("c1", "shell")).build();
        Msg ephemeral = Msg.builder().name("a").role(MsgRole.ASSISTANT)
                .content(TextBlock.builder().text("I ran mvn test").build()).build();

        pipeline(capturing, store, 0.7).process(List.of(substantive, toolResult, ephemeral));

        assertThat(seenByExtractor).containsExactly(substantive);
    }

    @Test
    void mergesWithExistingFacts_correctionSupersedes() {
        FakeStore store = new FakeStore();
        store.existing = new ArrayList<>(List.of(
                new ExtractedFact("language", FactCategory.USER_PREFERENCE, "English", 0.9, false)));
        MemoryExtractor extractor = turn -> List.of(
                new ExtractedFact("language", FactCategory.USER_PREFERENCE, "Chinese", 0.2, true));

        pipeline(extractor, store, 0.7).process(List.of(user("no, I actually prefer Chinese")));

        assertThat(store.lastSaved).hasSize(1);
        assertThat(store.lastSaved.get(0).statement()).isEqualTo("Chinese");
        assertThat(store.lastSaved.get(0).correction()).isTrue();
    }
}
