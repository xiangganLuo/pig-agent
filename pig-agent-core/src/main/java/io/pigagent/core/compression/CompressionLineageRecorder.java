package io.pigagent.core.compression;

/**
 * Records session lineage after a successful compression.
 *
 * <p>This is the seam that keeps {@link CompressionService} (in {@code pig-agent-core}) decoupled
 * from the session module: core only knows the {@code sessionId} and fires this callback, while
 * the concrete implementation (in {@code pig-agent-session}) writes the lineage into the session
 * metadata. Implementations MUST be best-effort — a failure here MUST NOT break compression, so
 * {@code CompressionService} invokes it defensively.
 */
@FunctionalInterface
public interface CompressionLineageRecorder {

    /** No-op recorder (default when no lineage tracking is wired). */
    CompressionLineageRecorder NOOP = sessionId -> {};

    /**
     * Called after the given session's in-memory conversation was compressed. Should derive and
     * persist the session's compression lineage (parent/child provenance).
     *
     * @param sessionId the session that was just compressed (never {@code null} when invoked)
     */
    void recordCompression(String sessionId);
}
