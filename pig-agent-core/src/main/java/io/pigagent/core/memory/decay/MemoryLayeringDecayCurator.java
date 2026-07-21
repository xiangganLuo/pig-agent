package io.pigagent.core.memory.decay;

import io.pigagent.core.memory.quality.FactUnitSplitter;
import io.pigagent.core.memory.quality.FactUnitSplitter.Segment;
import io.pigagent.core.memory.quality.MemoryLayerFormat.Layer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The pig-side <b>post-consolidation layering/decay curator</b> — capability
 * {@code memory-layering-and-decay} (M-C, D4). It mirrors M-D's {@code MemoryConsolidationCurator}: it
 * runs <b>after</b> the native LLM consolidation (and M-D's semantic dedup) has rewritten the
 * workspace-level {@code MEMORY.md}, and — when enabled — deterministically re-layers + ages it, without
 * touching the native flush/consolidation triggers or prompts.
 *
 * <p><b>Cooperates with the native full rewrite, does not fight it:</b> every pass re-derives each fact's
 * layer from its content ({@link FactLayerClassifier}) rather than trusting a stored marker to survive
 * the native rewrite; recency comes from the append-only dated ledger ({@link RecencyResolver}); reuse
 * from the reuse sidecar ({@link MemoryAccessStore}). {@link RetentionScorer} then decides keep / promote
 * / degrade / archive per fact; archived facts are moved to the dot-prefixed audit ledger
 * ({@link DecayArchiveWriter}, never deleted, not re-ingested by the native scan). The file is rewritten
 * grouped under the canonical {@code MemoryLayerFormat} headings, atomically, only when the result
 * differs.
 *
 * <p><b>Default is a non-destructive dry-run</b> ({@code autoArchive=false}): decisions are computed and
 * would-degrade/would-archive candidates logged, but {@code MEMORY.md} is left byte-identical. Throttled
 * by {@code minGap} and <b>fault-tolerant</b> — any failure is logged and leaves {@code MEMORY.md}
 * intact.
 */
public final class MemoryLayeringDecayCurator {

    private static final Logger log = LoggerFactory.getLogger(MemoryLayeringDecayCurator.class);

    private final Path memoryMd;
    private final Path memoryDir;
    private final FactLayerClassifier classifier;
    private final RetentionScorer scorer;
    private final DecayArchiveWriter archiveWriter;
    private final boolean autoArchive;
    private final Duration minGap;
    private final Clock clock;

    private final AtomicLong lastRunAtMs = new AtomicLong(0);

    public MemoryLayeringDecayCurator(Path memoryMd, Path memoryDir, FactLayerClassifier classifier,
                                      RetentionScorer scorer, DecayArchiveWriter archiveWriter,
                                      boolean autoArchive, Duration minGap) {
        this(memoryMd, memoryDir, classifier, scorer, archiveWriter, autoArchive, minGap, Clock.systemUTC());
    }

    /** Seam constructor with an injectable clock (throttle + {@code today} for recency testing). */
    public MemoryLayeringDecayCurator(Path memoryMd, Path memoryDir, FactLayerClassifier classifier,
                                      RetentionScorer scorer, DecayArchiveWriter archiveWriter,
                                      boolean autoArchive, Duration minGap, Clock clock) {
        this.memoryMd = Objects.requireNonNull(memoryMd, "memoryMd");
        this.memoryDir = Objects.requireNonNull(memoryDir, "memoryDir");
        this.classifier = Objects.requireNonNull(classifier, "classifier");
        this.scorer = Objects.requireNonNull(scorer, "scorer");
        this.archiveWriter = Objects.requireNonNull(archiveWriter, "archiveWriter");
        this.autoArchive = autoArchive;
        this.minGap = minGap == null || minGap.isNegative() ? Duration.ZERO : minGap;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    /**
     * Curate only if the min-gap has elapsed since the last run. Returns whether {@code MEMORY.md} was
     * actually rewritten (always false in dry-run, when throttled, unchanged, or on any failure).
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
     * Run one layering/decay pass regardless of throttle. Fault-tolerant: any failure is logged and
     * leaves {@code MEMORY.md} intact.
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
            LocalDate today = LocalDate.now(clock);
            RecencyResolver recency = new RecencyResolver(memoryDir);
            MemoryAccessStore access = new MemoryAccessStore(memoryDir);

            List<Segment> segments = FactUnitSplitter.split(content);
            Map<Layer, List<String>> survivors = new EnumMap<>(Layer.class);
            List<String> toArchive = new ArrayList<>();
            int degradeCount = 0;
            for (Segment s : segments) {
                if (!s.fact()) {
                    continue; // headings/blank are re-imposed by the layered rebuild
                }
                String fact = s.text();
                Layer layer = classifier.classify(fact);
                long recencyDays = recency.recencyDays(fact, today);
                int accessCount = access.accessCount(FactFingerprint.of(fact));
                RetentionDecision decision = scorer.decide(layer, recencyDays, accessCount);
                if (decision == RetentionDecision.ARCHIVE) {
                    toArchive.add(fact);
                } else if (decision == RetentionDecision.DEGRADE) {
                    degradeCount++;
                    survivors.computeIfAbsent(down(layer), k -> new ArrayList<>()).add(fact);
                } else if (decision == RetentionDecision.PROMOTE) {
                    survivors.computeIfAbsent(up(layer), k -> new ArrayList<>()).add(fact);
                } else {
                    survivors.computeIfAbsent(layer, k -> new ArrayList<>()).add(fact);
                }
            }

            if (!autoArchive) {
                if (!toArchive.isEmpty() || degradeCount > 0) {
                    log.info("Memory decay (dry-run): would archive {} fact(s), degrade {} — MEMORY.md left "
                            + "intact (set memory.layering-decay.auto-archive=true to apply)",
                            toArchive.size(), degradeCount);
                }
                return false;
            }

            // Archive first; only keep archived facts out of MEMORY.md when the append persisted.
            if (!toArchive.isEmpty() && !archiveWriter.archive(toArchive, today)) {
                // Archive failed → do NOT drop the facts; retain them in their classified layer.
                for (String fact : toArchive) {
                    survivors.computeIfAbsent(classifier.classify(fact), k -> new ArrayList<>()).add(fact);
                }
                toArchive.clear();
            }

            String rebuilt = rebuild(survivors);
            if (rebuilt.equals(content)) {
                return false; // nothing changed
            }
            writeAtomically(rebuilt);
            log.info("Memory re-layered: {} archived, {} degraded (Pinned={}, General={}, Recent={})",
                    toArchive.size(), degradeCount,
                    count(survivors, Layer.PINNED), count(survivors, Layer.GENERAL),
                    count(survivors, Layer.VOLATILE));
            return true;
        } catch (Exception e) {
            log.warn("Memory layering/decay skipped (MEMORY.md left intact): {}", e.getMessage());
            return false;
        }
    }

    /** Re-emit surviving facts grouped under the canonical layer headings (Pinned → General → Recent). */
    private static String rebuild(Map<Layer, List<String>> survivors) {
        StringBuilder out = new StringBuilder();
        for (Layer layer : Layer.values()) {
            List<String> facts = survivors.get(layer);
            if (facts == null || facts.isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append('\n');
            }
            out.append("## ").append(layer.heading()).append('\n');
            for (String fact : facts) {
                out.append(fact).append('\n');
            }
        }
        // Strip the trailing newline for a stable, idempotent round-trip (mirrors the M-D dedup curator).
        return out.toString().strip();
    }

    private static int count(Map<Layer, List<String>> m, Layer l) {
        List<String> facts = m.get(l);
        return facts == null ? 0 : facts.size();
    }

    /** One layer more durable (VOLATILE→GENERAL→PINNED; PINNED stays). */
    private static Layer up(Layer layer) {
        return layer.ordinal() > 0 ? Layer.values()[layer.ordinal() - 1] : layer;
    }

    /** One layer less durable (PINNED→GENERAL→VOLATILE; VOLATILE stays). */
    private static Layer down(Layer layer) {
        Layer[] all = Layer.values();
        return layer.ordinal() < all.length - 1 ? all[layer.ordinal() + 1] : layer;
    }

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
