package io.pigagent.session;

import io.pigagent.core.compression.CompressionLineageRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Persists compression lineage into session metadata (the session-module implementation of the
 * core {@link CompressionLineageRecorder} seam).
 *
 * <p>On each compression it derives the session's lineage via {@link Session#withCompressionLineage()}
 * and saves only the {@code meta.json} — it never touches the persisted conversation history or the
 * temporary memory, so the "compression does not touch persisted history" guarantee holds. A
 * missing or corrupt session is a no-op (fault tolerance).
 */
public final class SessionLineageWriter implements CompressionLineageRecorder {

    private static final Logger log = LoggerFactory.getLogger(SessionLineageWriter.class);

    private final SessionRepository repository;

    public SessionLineageWriter(SessionRepository repository) {
        this.repository = repository;
    }

    @Override
    public void recordCompression(String sessionId) {
        if (sessionId == null) {
            return;
        }
        repository.findById(sessionId)
                .filter(s -> !s.corrupt())
                .ifPresent(s -> {
                    Session updated = repository.save(s.withCompressionLineage());
                    log.debug("Recorded compression lineage for session {} (lineage={}, parent={})",
                            sessionId, updated.lineageId(), updated.parentSessionId());
                });
    }
}
