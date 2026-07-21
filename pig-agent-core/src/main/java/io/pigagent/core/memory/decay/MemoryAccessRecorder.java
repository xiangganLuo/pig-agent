package io.pigagent.core.memory.decay;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.function.Supplier;

/**
 * The reuse-signal seam — capability {@code memory-layering-and-decay} (M-C, D2). Retrieval hits (facts
 * returned by {@code MemorySearchIndex.search} / the injection path) are fed here to reinforce those
 * facts against decay. Mirrors the skills line's {@code SkillUsageRecorder}: the <b>default is
 * {@link #noop()}</b> (zero side effects), so when nothing wires a real recorder the decay curator
 * simply falls back to recency-only aging — reuse reinforcement is an additive enhancement, not a
 * prerequisite.
 */
@FunctionalInterface
public interface MemoryAccessRecorder {

    /** Record that these fact texts were retrieved/used this turn (bumps their reuse counters). */
    void record(List<String> factTexts);

    /** The default no-op recorder — records nothing (recency-only decay). */
    static MemoryAccessRecorder noop() {
        return factTexts -> {
        };
    }

    /** A recorder backed by a {@link MemoryAccessStore}, bumping each fact's fingerprint on today's day. */
    static MemoryAccessRecorder backedBy(MemoryAccessStore store) {
        return backedBy(store, () -> LocalDate.now(Clock.systemDefaultZone()).toEpochDay());
    }

    /** Seam variant with an injectable {@code epochDay} supplier (deterministic tests). */
    static MemoryAccessRecorder backedBy(MemoryAccessStore store, Supplier<Long> epochDaySupplier) {
        return new StoreBackedRecorder(store, epochDaySupplier);
    }

    /** Store-backed recorder: fault-tolerant, invisible to the tool result (a failure never surfaces). */
    final class StoreBackedRecorder implements MemoryAccessRecorder {
        private static final Logger log = LoggerFactory.getLogger(StoreBackedRecorder.class);
        private final MemoryAccessStore store;
        private final Supplier<Long> epochDaySupplier;

        StoreBackedRecorder(MemoryAccessStore store, Supplier<Long> epochDaySupplier) {
            this.store = store;
            this.epochDaySupplier = epochDaySupplier == null
                    ? () -> LocalDate.now(ZoneId.systemDefault()).toEpochDay()
                    : epochDaySupplier;
        }

        @Override
        public void record(List<String> factTexts) {
            if (store == null || factTexts == null || factTexts.isEmpty()) {
                return;
            }
            try {
                long day = epochDaySupplier.get();
                for (String fact : factTexts) {
                    store.bump(FactFingerprint.of(fact), day);
                }
            } catch (RuntimeException e) {
                log.debug("Access recording skipped: {}", e.getMessage());
            }
        }
    }
}
