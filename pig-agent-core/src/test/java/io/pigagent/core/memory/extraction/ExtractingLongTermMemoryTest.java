package io.pigagent.core.memory.extraction;

import io.agentscope.core.memory.LongTermMemory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/** Decorator: disabled → raw delegate; enabled → async (off critical path) + graceful; retrieve delegates. */
class ExtractingLongTermMemoryTest {

    /** Counts raw record/retrieve delegation. */
    static final class CountingDelegate implements LongTermMemory {
        int recordCount;
        List<Msg> lastRecorded;

        @Override
        public Mono<String> retrieve(Msg query) {
            return Mono.just("DELEGATE-MEM");
        }

        @Override
        public Mono<Void> record(List<Msg> messages) {
            recordCount++;
            lastRecorded = messages;
            return Mono.empty();
        }
    }

    /** In-memory store recording save calls. */
    static final class FakeStore implements FactStore {
        List<ExtractedFact> existing = List.of();
        int saveCount;

        @Override
        public List<ExtractedFact> load() {
            return existing;
        }

        @Override
        public void save(List<ExtractedFact> facts) {
            existing = facts;
            saveCount++;
        }
    }

    private static Msg user(String t) {
        return Msg.builder().name("u").role(MsgRole.USER).content(TextBlock.builder().text(t).build()).build();
    }

    private MemoryExtractionPipeline pipeline(MemoryExtractor extractor, FactStore store) {
        return new MemoryExtractionPipeline(new MemoryNoiseFilter(), extractor,
                new ConfidenceGate(), new FactMerger(), store, () -> 0.7);
    }

    @Test
    void disabled_delegatesRawRecord_noExtraction() {
        CountingDelegate delegate = new CountingDelegate();
        FakeStore store = new FakeStore();
        try (AsyncMemoryExtractionScheduler scheduler = new AsyncMemoryExtractionScheduler(60_000)) {
            ExtractingLongTermMemory mem = new ExtractingLongTermMemory(delegate,
                    pipeline(turn -> {
                        throw new AssertionError("extractor must not run when disabled");
                    }, store),
                    scheduler, () -> false);

            mem.record(List.of(user("remember this"))).block();

            assertThat(delegate.recordCount).isEqualTo(1); // raw fallback, byte-for-byte behavior
            assertThat(store.saveCount).isZero();
        }
    }

    @Test
    void enabled_recordIsAsync_offCriticalPath() {
        CountingDelegate delegate = new CountingDelegate();
        FakeStore store = new FakeStore();
        MemoryExtractor extractor = turn ->
                List.of(new ExtractedFact("a", FactCategory.PROJECT_FACT, "stmt", 0.9, false));
        try (AsyncMemoryExtractionScheduler scheduler = new AsyncMemoryExtractionScheduler(60_000)) {
            ExtractingLongTermMemory mem = new ExtractingLongTermMemory(delegate,
                    pipeline(extractor, store), scheduler, () -> true);

            mem.record(List.of(user("The project uses Maven."))).block();

            // Not raw-recorded, and extraction has NOT run yet (off the critical path).
            assertThat(delegate.recordCount).isZero();
            assertThat(store.saveCount).isZero();

            scheduler.flush(); // force the background job now

            assertThat(store.saveCount).isEqualTo(1);
        }
    }

    @Test
    void enabled_extractorThrows_recordStillCompletes() {
        FakeStore store = new FakeStore();
        MemoryExtractor boom = turn -> {
            throw new RuntimeException("boom");
        };
        try (AsyncMemoryExtractionScheduler scheduler = new AsyncMemoryExtractionScheduler(60_000)) {
            ExtractingLongTermMemory mem = new ExtractingLongTermMemory(new CountingDelegate(),
                    pipeline(boom, store), scheduler, () -> true);

            // The turn is never impacted: record completes, and flushing swallows the failure.
            assertThatCode(() -> mem.record(List.of(user("hi"))).block()).doesNotThrowAnyException();
            assertThatCode(scheduler::flush).doesNotThrowAnyException();
            assertThat(store.saveCount).isZero();
        }
    }

    @Test
    void retrieveDelegates() {
        try (AsyncMemoryExtractionScheduler scheduler = new AsyncMemoryExtractionScheduler(60_000)) {
            ExtractingLongTermMemory mem = new ExtractingLongTermMemory(new CountingDelegate(),
                    pipeline(MemoryExtractor.NONE, new FakeStore()), scheduler, () -> true);
            assertThat(mem.retrieve(user("q")).block()).isEqualTo("DELEGATE-MEM");
        }
    }

    @Test
    void enabled_emptyMessages_isNoOp() {
        FakeStore store = new FakeStore();
        try (AsyncMemoryExtractionScheduler scheduler = new AsyncMemoryExtractionScheduler(60_000)) {
            ExtractingLongTermMemory mem = new ExtractingLongTermMemory(new CountingDelegate(),
                    pipeline(MemoryExtractor.NONE, store), scheduler, () -> true);
            mem.record(new ArrayList<>()).block();
            scheduler.flush();
            assertThat(store.saveCount).isZero();
        }
    }
}
