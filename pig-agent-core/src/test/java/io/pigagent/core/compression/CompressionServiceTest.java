package io.pigagent.core.compression;

import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the compression → lineage seam. The model-backed summarizer and the live
 * {@code Memory} are injected as fakes so compression runs fully in-memory, with no network.
 */
class CompressionServiceTest {

    /** Records which sessions had lineage recorded; optionally throws to prove failures are swallowed. */
    static final class RecordingRecorder implements CompressionLineageRecorder {
        final List<String> recorded = new ArrayList<>();
        private final boolean fail;

        RecordingRecorder(boolean fail) {
            this.fail = fail;
        }

        @Override
        public void recordCompression(String sessionId) {
            recorded.add(sessionId);
            if (fail) {
                throw new RuntimeException("boom");
            }
        }
    }

    private static Memory memoryWith(int count) {
        Memory memory = new InMemoryMemory();
        for (int i = 0; i < count; i++) {
            memory.addMessage(Msg.builder().name("user").role(MsgRole.USER)
                    .content(TextBlock.builder().text("message " + i).build()).build());
        }
        return memory;
    }

    private CompressionService service(Memory memory, CompressionLineageRecorder recorder) {
        Supplier<Memory> memorySupplier = () -> memory;
        CompressionService.Summarizer summarizer = older -> "SUMMARY";
        // budget/threshold irrelevant for compressNow (manual trigger ignores threshold).
        return new CompressionService(memorySupplier, summarizer, 1000, 0.8, true, recorder);
    }

    @Test
    void compressRecordsLineageForSession() {
        Memory memory = memoryWith(10);
        RecordingRecorder recorder = new RecordingRecorder(false);
        CompressionService service = service(memory, recorder);

        boolean compressed = service.compressNow("sess-1");

        assertThat(compressed).isTrue();
        assertThat(recorder.recorded).containsExactly("sess-1");
        // Memory rewritten in place: one summary + the 6 most recent kept.
        assertThat(memory.getMessages()).hasSize(7);
        assertThat(memory.getMessages().get(0).getTextContent()).contains("SUMMARY");
    }

    @Test
    void lineageRecorderFailureDoesNotBreakCompression() {
        Memory memory = memoryWith(10);
        RecordingRecorder recorder = new RecordingRecorder(true); // throws
        CompressionService service = service(memory, recorder);

        boolean compressed = service.compressNow("sess-1");

        assertThat(compressed).isTrue();               // compression still succeeds
        assertThat(recorder.recorded).containsExactly("sess-1"); // recorder was attempted
        assertThat(memory.getMessages()).hasSize(7);   // memory still rewritten
    }

    @Test
    void noLineageRecordedWhenSessionIdIsNull() {
        Memory memory = memoryWith(10);
        RecordingRecorder recorder = new RecordingRecorder(false);
        CompressionService service = service(memory, recorder);

        boolean compressed = service.compressNow(null);

        assertThat(compressed).isTrue();
        assertThat(recorder.recorded).isEmpty(); // sessionId==null → no lineage attempt
    }

    @Test
    void noCompressionWhenNothingToCompress() {
        Memory memory = memoryWith(3); // <= KEEP_RECENT, nothing to summarize
        RecordingRecorder recorder = new RecordingRecorder(false);
        CompressionService service = service(memory, recorder);

        boolean compressed = service.compressNow("sess-1");

        assertThat(compressed).isFalse();
        assertThat(recorder.recorded).isEmpty(); // no compression → no lineage
        assertThat(memory.getMessages()).hasSize(3); // untouched
    }
}
