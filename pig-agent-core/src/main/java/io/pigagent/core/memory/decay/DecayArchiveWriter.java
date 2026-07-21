package io.pigagent.core.memory.decay;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;

/**
 * Appends archived facts to an audit ledger — capability {@code memory-layering-and-decay} (M-C, D5).
 * Archival is <b>never a delete</b>: a stale fact removed from {@code MEMORY.md} is appended to
 * {@code <memoryDir>/.decay/archive/YYYY-MM.md} so it stays auditable/recoverable. The
 * <b>dot-prefixed</b> {@code .decay/archive} location is doubly out of reach of the native consolidation
 * corpus scan (which only ingests top-level {@code memory/YYYY-MM-DD.md} date files), so archived facts
 * are <b>not resurrected</b>. Fault-tolerant: a write failure is logged + swallowed (the curator still
 * proceeds); creates the archive dir on demand.
 */
public final class DecayArchiveWriter {

    private static final Logger log = LoggerFactory.getLogger(DecayArchiveWriter.class);
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy-MM");

    private final Path archiveDir;

    public DecayArchiveWriter(Path memoryDir) {
        this.archiveDir = Objects.requireNonNull(memoryDir, "memoryDir").resolve(".decay").resolve("archive");
    }

    /** The archive file for {@code date}'s month ({@code .decay/archive/YYYY-MM.md}). */
    public Path archiveFileFor(LocalDate date) {
        return archiveDir.resolve(MONTH.format(date) + ".md");
    }

    /**
     * Append {@code facts} to this month's archive ledger, each tagged with the archival date. Returns
     * whether the append persisted — the curator only removes facts from {@code MEMORY.md} when this is
     * {@code true}, so a fact we could not archive is never dropped (archive-non-delete guarantee). An
     * empty list returns {@code true} (nothing to do); a write failure is logged + returns {@code false}.
     */
    public boolean archive(List<String> facts, LocalDate today) {
        if (facts == null || facts.isEmpty()) {
            return true;
        }
        StringBuilder sb = new StringBuilder();
        for (String fact : facts) {
            sb.append("- [").append(today).append("] ").append(fact.strip()).append('\n');
        }
        try {
            Files.createDirectories(archiveDir);
            Files.writeString(archiveFileFor(today), sb.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            return true;
        } catch (Exception e) {
            log.warn("Could not append to decay archive (facts kept in MEMORY.md this pass): {}",
                    e.getMessage());
            return false;
        }
    }
}
