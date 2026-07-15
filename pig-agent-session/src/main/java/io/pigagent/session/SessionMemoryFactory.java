package io.pigagent.session;

import io.agentscope.core.memory.LongTermMemory;
import io.pigagent.core.memory.FileSystemLongTermMemory;

import java.nio.file.Path;

/**
 * Seam for building a session's per-session temporary long-term memory. {@link SessionManager}
 * previously hard-coded {@code new FileSystemLongTermMemory(tempMemoryFile)}; extracting it here
 * lets the bootstrap wrap that raw memory with the memory-extraction decorator (or any future
 * decorator) without {@link SessionManager} depending on the extraction module.
 *
 * <p>{@link #DEFAULT} is the identity behavior — a plain {@link FileSystemLongTermMemory} at the
 * given path — so an unwired {@link SessionManager} behaves exactly as before (byte-for-byte).
 */
@FunctionalInterface
public interface SessionMemoryFactory {

    /** Raw file-backed memory at the temp path — the pre-extraction default. */
    SessionMemoryFactory DEFAULT = FileSystemLongTermMemory::new;

    /**
     * Build the per-session temporary memory for the given {@code temp-memory.md} path (invoked on
     * every session activation, so it MUST be cheap / stateless per call).
     */
    LongTermMemory create(Path tempMemoryFile);
}
