package io.pigagent.core.memory.extraction;

import io.agentscope.core.message.Msg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.function.DoubleSupplier;

/**
 * Orchestrates one extraction pass and is the <b>single point of graceful degradation</b>:
 * {@code recent window → noise filter → extract → confidence gate → load existing → merge/supersede
 * → save}. Every step delegates to an injected collaborator (Strategy composition); the pipeline
 * itself only sequences them and wraps the whole thing in a try/catch so <em>any</em> failure —
 * extractor throwing, parse/IO error — is logged and skipped, leaving existing memory intact
 * (mirroring the compression-lineage "swallow &amp; log" convention).
 *
 * <p>The input is the full conversation snapshot (that is what {@code record} receives); only the
 * {@link #RECENT_WINDOW} trailing messages are considered, so the extractor sees the recent turn(s)
 * — enough to cover a small debounced burst — while dedup-by-subject in {@link FactMerger} keeps
 * re-extraction of an already-known subject idempotent.
 */
public final class MemoryExtractionPipeline {

    private static final Logger log = LoggerFactory.getLogger(MemoryExtractionPipeline.class);

    /** Trailing messages fed to the extractor (~6 user/assistant rounds). */
    static final int RECENT_WINDOW = 12;

    private final MemoryNoiseFilter noiseFilter;
    private final MemoryExtractor extractor;
    private final ConfidenceGate gate;
    private final FactMerger merger;
    private final FactStore store;
    private final DoubleSupplier thresholdSupplier;

    public MemoryExtractionPipeline(MemoryNoiseFilter noiseFilter, MemoryExtractor extractor,
                                    ConfidenceGate gate, FactMerger merger, FactStore store,
                                    DoubleSupplier thresholdSupplier) {
        this.noiseFilter = Objects.requireNonNull(noiseFilter, "noiseFilter");
        this.extractor = Objects.requireNonNull(extractor, "extractor");
        this.gate = Objects.requireNonNull(gate, "gate");
        this.merger = Objects.requireNonNull(merger, "merger");
        this.store = Objects.requireNonNull(store, "store");
        this.thresholdSupplier = Objects.requireNonNull(thresholdSupplier, "thresholdSupplier");
    }

    /** Run one extraction pass over the conversation snapshot; never throws. */
    public void process(List<Msg> conversation) {
        try {
            List<Msg> cleaned = noiseFilter.filter(recentWindow(conversation));
            if (cleaned.isEmpty()) {
                return;
            }
            List<ExtractedFact> extracted = extractor.extract(cleaned);
            List<ExtractedFact> gated = gate.gate(extracted, thresholdSupplier.getAsDouble());
            if (gated.isEmpty()) {
                return; // nothing durable this turn — leave existing memory untouched
            }
            List<ExtractedFact> merged = merger.merge(store.load(), gated);
            store.save(merged);
        } catch (Exception e) {
            // Best-effort: never break the turn, never corrupt existing memory.
            log.warn("Memory extraction pipeline skipped: {}", e.getMessage());
        }
    }

    private static List<Msg> recentWindow(List<Msg> conversation) {
        if (conversation == null || conversation.isEmpty()) {
            return List.of();
        }
        int size = conversation.size();
        int from = Math.max(0, size - RECENT_WINDOW);
        return conversation.subList(from, size);
    }
}
