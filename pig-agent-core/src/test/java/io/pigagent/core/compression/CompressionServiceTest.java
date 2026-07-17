package io.pigagent.core.compression;

import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the compression → lineage seam and the session-scoped memory/persist contract. The
 * model-backed summarizer and the live {@code Memory} slots are injected as fakes so compression runs
 * fully in-memory, with no network. The {@code persisted} list records the persist-after-rewrite
 * calls (the HIGH fix: a compressed slot MUST be saved back so the native per-turn reload keeps it).
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

    /** Session ids the service asked to persist after a rewrite (the load-bearing HIGH fix). */
    private final List<String> persisted = new ArrayList<>();

    private static Memory memoryWith(int count) {
        Memory memory = new InMemoryMemory();
        for (int i = 0; i < count; i++) {
            memory.addMessage(Msg.builder().name("user").role(MsgRole.USER)
                    .content(TextBlock.builder().text("message " + i).build()).build());
        }
        return memory;
    }

    /** A single fixed memory returned for any session id. */
    private CompressionService service(Memory memory, CompressionLineageRecorder recorder) {
        return service(sessionId -> memory, recorder);
    }

    /** A session-keyed memory resolver, so tests can assert per-session scoping. */
    private CompressionService service(Function<String, Memory> memoryFn, CompressionLineageRecorder recorder) {
        CompressionService.Summarizer summarizer = older -> "SUMMARY";
        // budget/threshold irrelevant for compressNow (manual trigger ignores threshold).
        return new CompressionService(memoryFn, persisted::add, summarizer, 1000, 0.8, true, recorder);
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
    void compressPersistsRewrittenSessionSlot() {
        Memory memory = memoryWith(10);
        CompressionService service = service(memory, new RecordingRecorder(false));

        boolean compressed = service.compressNow("sess-1");

        // HIGH fix: after the in-memory rewrite, the (pig, sess-1) slot is persisted so the native
        // per-turn reload observes the compressed conversation instead of discarding it.
        assertThat(compressed).isTrue();
        assertThat(memory.getMessages()).hasSize(7);
        assertThat(persisted).containsExactly("sess-1");
    }

    @Test
    void compressWithNullSessionIdDoesNotPersist() {
        Memory memory = memoryWith(10);
        CompressionService service = service(memory, new RecordingRecorder(false));

        boolean compressed = service.compressNow(null);

        assertThat(compressed).isTrue();       // rewrite still happens on the default slot
        assertThat(persisted).isEmpty();       // but no session slot to persist
    }

    @Test
    void compressAndStatusOperateOnTheGivenSessionSlot() {
        Memory sessA = memoryWith(10);
        Memory sessB = memoryWith(3);
        Map<String, Memory> slots = Map.of("A", sessA, "B", sessB);
        CompressionService service = service(slots::get, new RecordingRecorder(false));

        boolean compressed = service.compressNow("A");

        // Only session A's slot is rewritten + persisted; B is untouched (session scoping).
        assertThat(compressed).isTrue();
        assertThat(sessA.getMessages()).hasSize(7);
        assertThat(sessB.getMessages()).hasSize(3);
        assertThat(persisted).containsExactly("A");
        // status() reads the requested slot, not a shared default one.
        assertThat(service.status("A").messageCount()).isEqualTo(7);
        assertThat(service.status("B").messageCount()).isEqualTo(3);
    }

    @Test
    void statusReportsNonZeroForNonEmptySession() {
        Memory memory = memoryWith(5);
        CompressionService service = service(memory, new RecordingRecorder(false));

        CompressionStatus status = service.status("sess-1");

        assertThat(status.messageCount()).isEqualTo(5);
        assertThat(status.estimatedTokens()).isGreaterThan(0);
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
        assertThat(persisted).containsExactly("sess-1"); // and the slot was still persisted first
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
        assertThat(persisted).isEmpty();         // nothing rewritten → nothing persisted
        assertThat(memory.getMessages()).hasSize(3); // untouched
    }
}
