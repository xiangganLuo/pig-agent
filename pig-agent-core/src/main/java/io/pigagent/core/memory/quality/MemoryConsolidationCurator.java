package io.pigagent.core.memory.quality;

import io.pigagent.core.memory.quality.FactUnitSplitter.Segment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Optional pig-side <b>post-consolidation curator</b> — capability {@code memory-consolidation-quality}.
 * After the native LLM consolidation has rewritten the workspace-level {@code MEMORY.md}, this runs a
 * confined, deterministic <b>semantic dedup</b> pass over it: split into fact units
 * ({@link FactUnitSplitter}), drop near-duplicates ({@link SemanticDeduplicator}) while preserving
 * structural lines / layer headings ({@code MemoryLayerFormat} contract), and atomically rewrite the
 * file only when something changed.
 *
 * <p><b>Design.</b> Mirrors {@code ProfileConsolidationService}: throttled by {@code minGap} (a
 * {@link Clock} is injectable for tests), and <b>fault-tolerant</b> — any read/split/dedup/write failure
 * is logged at warn and swallowed, leaving the existing {@code MEMORY.md} untouched. The embedder (or its
 * absence → BM25 degradation) lives inside the injected {@link SemanticDeduplicator}. Default-off at the
 * wiring layer (opt-in via {@code memory.consolidation-quality.dedup.enabled}).
 */
public final class MemoryConsolidationCurator {

    private static final Logger log = LoggerFactory.getLogger(MemoryConsolidationCurator.class);

    private final Path memoryMd;
    private final SemanticDeduplicator deduplicator;
    private final Duration minGap;
    private final Clock clock;

    /** Epoch-ms of the last run; {@code 0} = never (so the first call always runs). */
    private final AtomicLong lastRunAtMs = new AtomicLong(0);

    public MemoryConsolidationCurator(Path memoryMd, SemanticDeduplicator deduplicator, Duration minGap) {
        this(memoryMd, deduplicator, minGap, Clock.systemUTC());
    }

    /** Seam constructor with an injectable clock (throttle testing). */
    public MemoryConsolidationCurator(Path memoryMd, SemanticDeduplicator deduplicator, Duration minGap,
                                      Clock clock) {
        this.memoryMd = Objects.requireNonNull(memoryMd, "memoryMd");
        this.deduplicator = Objects.requireNonNull(deduplicator, "deduplicator");
        this.minGap = minGap == null || minGap.isNegative() ? Duration.ZERO : minGap;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    /**
     * Curate only if the min-gap has elapsed since the last run (background trigger). Returns whether a
     * rewrite actually happened (false when throttled, empty, unchanged, or on any failure).
     */
    public boolean maybeCurate() {
        long now = clock.millis();
        long last = lastRunAtMs.get();
        if (last != 0 && now - last < minGap.toMillis()) {
            return false; // throttled
        }
        return curateNow();
    }

    /**
     * Run a dedup pass regardless of the throttle (still updates the throttle clock). Fault-tolerant:
     * any failure is logged and swallowed, leaving {@code MEMORY.md} intact.
     */
    public boolean curateNow() {
        lastRunAtMs.set(clock.millis());
        try {
            if (!Files.isRegularFile(memoryMd)) {
                return false;
            }
            String content = Files.readString(memoryMd, StandardCharsets.UTF_8);
            if (content.isBlank()) {
                return false;
            }
            List<Segment> segments = FactUnitSplitter.split(content);
            List<String> factTexts = FactUnitSplitter.factTexts(segments);
            if (factTexts.size() <= 1) {
                return false; // nothing to dedup against
            }
            Set<Integer> survivors = new HashSet<>(deduplicator.survivingIndices(factTexts));
            if (survivors.size() == factTexts.size()) {
                return false; // no near-duplicates → no rewrite
            }
            String rewritten = rebuild(segments, survivors);
            writeAtomically(rewritten);
            log.info("MEMORY.md deduped: {} fact unit(s) → {} (removed {})",
                    factTexts.size(), survivors.size(), factTexts.size() - survivors.size());
            return true;
        } catch (Exception e) {
            log.warn("Memory dedup skipped (MEMORY.md left intact): {}", e.getMessage());
            return false;
        }
    }

    /** Re-emit every segment in order, dropping only the non-surviving fact lines (structure preserved). */
    private static String rebuild(List<Segment> segments, Set<Integer> survivors) {
        StringBuilder out = new StringBuilder();
        int factOrdinal = 0;
        for (Segment s : segments) {
            if (s.fact()) {
                if (survivors.contains(factOrdinal)) {
                    out.append(s.text()).append('\n');
                }
                factOrdinal++;
            } else {
                out.append(s.text()).append('\n');
            }
        }
        // split("\n", -1) yields a trailing empty segment for a file ending in '\n'; trim the one
        // extra newline our per-segment append introduces so round-tripping is stable.
        if (out.length() > 0 && out.charAt(out.length() - 1) == '\n') {
            out.setLength(out.length() - 1);
        }
        return out.toString();
    }

    /** Write via a temp file + atomic move (fault-tolerant: falls back to a plain replace). */
    private void writeAtomically(String content) throws java.io.IOException {
        Path dir = memoryMd.toAbsolutePath().getParent();
        Path tmp = Files.createTempFile(dir, "MEMORY", ".tmp");
        Files.writeString(tmp, content, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, memoryMd, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tmp, memoryMd, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
