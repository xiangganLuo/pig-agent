package io.pigagent.core.memory.decay;

import io.pigagent.core.search.Tokenizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Derives a fact's <b>recency</b> from the append-only, date-named daily ledger — capability
 * {@code memory-layering-and-decay} (M-C, D2). Because {@code memory/YYYY-MM-DD.md} files are only ever
 * appended to (native flush) and their name <b>is</b> the date, a fact's recency needs no persisted
 * counter: it is {@code today − (newest ledger date whose text contains the fact)}. Matching is the same
 * token-subset test M-D's eval uses (all of the fact's {@link Tokenizer} tokens present in the file),
 * shared so scoring never drifts.
 *
 * <p>When <b>no</b> ledger file matches (the fact was reworded by consolidation, or the ledger was pruned
 * by {@code dailyFileRetentionDays}), recency is {@code -1} — a deliberate <b>grace</b> value the
 * {@link RetentionScorer} treats as fresh, so a fact we cannot date is never archived. Fault-tolerant:
 * an absent dir / unreadable or non-date file is skipped, never throws. Ledger token sets are loaded
 * once per resolver instance (the curator builds a fresh resolver per pass).
 */
public final class RecencyResolver {

    private static final Logger log = LoggerFactory.getLogger(RecencyResolver.class);

    /** Recency for a fact that matches no ledger file — treated as fresh by the scorer. */
    public static final long NO_SIGNAL = -1L;

    private final Path memoryDir;
    private List<DatedLedger> ledgers; // lazily loaded, cached per instance

    public RecencyResolver(Path memoryDir) {
        this.memoryDir = Objects.requireNonNull(memoryDir, "memoryDir");
    }

    /**
     * Days since {@code factText} last appeared in the ledger (0 = today), or {@link #NO_SIGNAL} when it
     * matches no ledger file. {@code today} is injectable for deterministic tests.
     */
    public long recencyDays(String factText, LocalDate today) {
        Set<String> factTokens = new HashSet<>(Tokenizer.tokenize(factText));
        if (factTokens.isEmpty()) {
            return NO_SIGNAL;
        }
        LocalDate newest = null;
        for (DatedLedger ledger : ledgers()) {
            if (ledger.tokens.containsAll(factTokens) && (newest == null || ledger.date.isAfter(newest))) {
                newest = ledger.date;
            }
        }
        if (newest == null) {
            return NO_SIGNAL;
        }
        return Math.max(0L, today.toEpochDay() - newest.toEpochDay());
    }

    private List<DatedLedger> ledgers() {
        if (ledgers == null) {
            ledgers = loadLedgers();
        }
        return ledgers;
    }

    private List<DatedLedger> loadLedgers() {
        List<DatedLedger> out = new ArrayList<>();
        if (!Files.isDirectory(memoryDir)) {
            return out;
        }
        try (var stream = Files.list(memoryDir)) {
            stream.filter(Files::isRegularFile).forEach(p -> {
                LocalDate date = parseDate(p.getFileName().toString());
                if (date == null) {
                    return; // skip .decay/, non-date files
                }
                try {
                    Set<String> tokens = new HashSet<>(
                            Tokenizer.tokenize(Files.readString(p, StandardCharsets.UTF_8)));
                    out.add(new DatedLedger(date, tokens));
                } catch (IOException | RuntimeException e) {
                    log.debug("Skipping unreadable ledger file {}: {}", p, e.getMessage());
                }
            });
        } catch (IOException | RuntimeException e) {
            log.debug("Could not list ledger dir {}: {}", memoryDir, e.getMessage());
        }
        return out;
    }

    /** Parse {@code YYYY-MM-DD.md} → date, or null when the name is not a dated ledger file. */
    private static LocalDate parseDate(String fileName) {
        if (!fileName.toLowerCase().endsWith(".md")) {
            return null;
        }
        String stem = fileName.substring(0, fileName.length() - 3);
        try {
            return LocalDate.parse(stem);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private record DatedLedger(LocalDate date, Set<String> tokens) {
    }
}
