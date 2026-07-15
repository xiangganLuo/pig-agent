package io.pigagent.core.memory.extraction;

import io.agentscope.core.memory.LongTermMemory;
import io.agentscope.core.message.Msg;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * A {@link LongTermMemory} <b>Decorator</b> that upgrades the session-tier {@code record} from
 * "store the raw turn" to "extract structured facts", while delegating {@code retrieve} unchanged.
 *
 * <ul>
 *   <li><b>{@code retrieve}</b> → straight delegate. The wrapped {@code FileSystemLongTermMemory}
 *       reads the same {@code temp-memory.md} the extraction pipeline writes to, so retrieval sees
 *       the structured facts.</li>
 *   <li><b>{@code record} when disabled</b> ({@code enabled} predicate false) → straight delegate,
 *       i.e. the original raw-append behavior, byte-for-byte. This is the graceful runtime-off path.</li>
 *   <li><b>{@code record} when enabled</b> → snapshot the messages and hand a
 *       {@code () -> pipeline.process(snapshot)} job to the debounce {@link
 *       AsyncMemoryExtractionScheduler}, then return immediately ({@code Mono.empty()}) — the
 *       extraction + write happen on the scheduler's background thread, off the turn's critical
 *       path. Submitting never blocks and never throws into the turn.</li>
 * </ul>
 */
public final class ExtractingLongTermMemory implements LongTermMemory {

    private final LongTermMemory delegate;
    private final MemoryExtractionPipeline pipeline;
    private final AsyncMemoryExtractionScheduler scheduler;
    private final BooleanSupplier enabled;

    public ExtractingLongTermMemory(LongTermMemory delegate, MemoryExtractionPipeline pipeline,
                                    AsyncMemoryExtractionScheduler scheduler, BooleanSupplier enabled) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.pipeline = Objects.requireNonNull(pipeline, "pipeline");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.enabled = Objects.requireNonNull(enabled, "enabled");
    }

    @Override
    public Mono<String> retrieve(Msg query) {
        return delegate.retrieve(query);
    }

    @Override
    public Mono<Void> record(List<Msg> messages) {
        if (!enabled.getAsBoolean()) {
            return delegate.record(messages); // raw-append fallback, identical to pre-extraction
        }
        if (messages == null || messages.isEmpty()) {
            return Mono.empty();
        }
        List<Msg> snapshot = new ArrayList<>(messages); // decouple from the live conversation list
        scheduler.submit(() -> pipeline.process(snapshot));
        return Mono.empty(); // off the critical path: extraction/write run on the scheduler thread
    }
}
