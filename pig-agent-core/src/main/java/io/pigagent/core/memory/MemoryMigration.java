package io.pigagent.core.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;

/**
 * One-time, idempotent, fault-tolerant migration of the retired pig self-built global memory
 * ({@code workspace/context/memory.md}) into the AgentScope 2.0 native consolidated memory
 * ({@code workspace/MEMORY.md}) — capability {@code pa-memory-native}, OD9/D8.
 *
 * <p>Behaviour:
 * <ul>
 *   <li>No old file → no-op.</li>
 *   <li>Already migrated (a {@code .migrated} marker beside the old file) → no-op (idempotent, so a
 *       repeated startup never re-appends).</li>
 *   <li>Otherwise: append the old content to {@code MEMORY.md} under a provenance header, keep a
 *       {@code .bak} backup of the original, and drop the {@code .migrated} marker. The original file
 *       is left in place (read-only preserved).</li>
 * </ul>
 *
 * <p><b>Fault-tolerant.</b> Any IO error is logged at warn and swallowed — the new memory library
 * simply starts empty and the original file is left untouched (mirroring the 2.0 session-migration
 * fault posture). Migration MUST NOT crash startup or lose the original data.
 *
 * <p>The session-tier temp memory ({@code sessions/{id}/temp-memory.md}) is deliberately NOT migrated
 * (session isolation is retired; those were ephemeral scratch notes).
 */
public final class MemoryMigration {

    private static final Logger log = LoggerFactory.getLogger(MemoryMigration.class);

    private static final String MIGRATED_MARKER_SUFFIX = ".migrated";
    private static final String BACKUP_SUFFIX = ".bak";

    private MemoryMigration() {
    }

    /**
     * Migrate {@code oldGlobalMemory} into {@code newMemoryMd} once.
     *
     * @return {@code true} if content was migrated on this call; {@code false} when there was nothing
     *     to do (no old file, already migrated) or migration failed (safe degrade).
     */
    public static boolean migrate(Path oldGlobalMemory, Path newMemoryMd) {
        if (oldGlobalMemory == null || newMemoryMd == null) {
            return false;
        }
        try {
            if (!Files.isRegularFile(oldGlobalMemory)) {
                return false; // nothing to migrate
            }
            Path marker = markerFor(oldGlobalMemory);
            if (Files.exists(marker)) {
                return false; // already migrated — idempotent
            }
            String oldContent = Files.readString(oldGlobalMemory, StandardCharsets.UTF_8);
            if (!oldContent.isBlank()) {
                String block = "\n<!-- migrated from context/memory.md @ " + Instant.now() + " -->\n"
                        + oldContent.strip() + "\n";
                if (newMemoryMd.getParent() != null) {
                    Files.createDirectories(newMemoryMd.getParent());
                }
                Files.writeString(newMemoryMd, block, StandardCharsets.UTF_8,
                        java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            }
            // Keep a backup of the original and drop the idempotency marker.
            Files.copy(oldGlobalMemory, backupFor(oldGlobalMemory), StandardCopyOption.REPLACE_EXISTING);
            Files.writeString(marker, Instant.now().toString(), StandardCharsets.UTF_8);
            log.info("Migrated legacy global memory {} into {}", oldGlobalMemory, newMemoryMd);
            return true;
        } catch (IOException | RuntimeException e) {
            log.warn("Legacy memory migration skipped (new memory starts empty, original preserved): {}",
                    e.getMessage());
            return false;
        }
    }

    private static Path markerFor(Path oldGlobalMemory) {
        return oldGlobalMemory.resolveSibling(oldGlobalMemory.getFileName() + MIGRATED_MARKER_SUFFIX);
    }

    private static Path backupFor(Path oldGlobalMemory) {
        return oldGlobalMemory.resolveSibling(oldGlobalMemory.getFileName() + BACKUP_SUFFIX);
    }
}
