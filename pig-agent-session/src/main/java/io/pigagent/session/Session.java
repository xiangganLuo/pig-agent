package io.pigagent.session;

import java.time.Instant;
import java.util.UUID;

/**
 * Metadata for one independent conversation session. Immutable — mutate via {@code withXxx}.
 *
 * <p>The actual conversation state and temporary memory live on disk under
 * {@code sessions/{id}/} (managed by {@link SessionManager}); this record only carries the
 * listing metadata. A session whose metadata file could not be read is represented with
 * {@link #corrupt} = {@code true} so the rest of the tool keeps working (fault tolerance).
 *
 * <p><b>Compression lineage.</b> When a session's in-memory conversation is compressed, the
 * {@code lineageId}/{@code parentSessionId} pair records the provenance (see
 * {@link #withCompressionLineage()}): {@code lineageId} groups the compression chain (assigned
 * once, on the first compression) and {@code parentSessionId} references the just-superseded
 * pre-compression state. Both are {@code null} for sessions that were never compressed; sessions
 * created before this capability simply read back as {@code null} (no parent), keeping the
 * fault-tolerant listing intact. This is <em>record-only</em>: no snapshot content is stored and
 * no new session directory is created (lightweight same-id lineage).
 */
public record Session(
        String id,
        String name,
        Instant createdAt,
        Instant lastActiveAt,
        String modelId,
        String lineageId,
        String parentSessionId,
        boolean corrupt) {

    /** Name given to a freshly created session before the first message renames it. */
    public static final String DEFAULT_NAME = "New session";

    /** Create a new blank session with a generated short id (no model binding → default). */
    public static Session create(String name) {
        Instant now = Instant.now();
        String resolved = (name == null || name.isBlank()) ? DEFAULT_NAME : name.strip();
        return new Session(shortId(), resolved, now, now, null, null, null, false);
    }

    public Session withName(String newName) {
        return new Session(id, newName, createdAt, lastActiveAt, modelId, lineageId, parentSessionId, corrupt);
    }

    public Session withLastActiveAt(Instant when) {
        return new Session(id, name, createdAt, when, modelId, lineageId, parentSessionId, corrupt);
    }

    /** Bind (or clear, with null) a per-session model — the temporary-switch case. */
    public Session withModelId(String newModelId) {
        return new Session(id, name, createdAt, lastActiveAt, newModelId, lineageId, parentSessionId, corrupt);
    }

    /**
     * Derive updated lineage after one compression event. The lineage id is started on the first
     * compression and preserved on subsequent ones (so the whole chain shares it); the parent
     * reference is (re)set to a fresh id standing for the state that was just superseded. Immutable:
     * returns a copy, never mutating this instance.
     */
    public Session withCompressionLineage() {
        String lineage = hasCompressionLineage() ? lineageId : shortId();
        String parent = shortId();
        return new Session(id, name, createdAt, lastActiveAt, modelId, lineage, parent, corrupt);
    }

    /** Whether this session's current context was derived from at least one compression. */
    public boolean hasCompressionLineage() {
        return lineageId != null && !lineageId.isBlank();
    }

    public boolean hasDefaultName() {
        return DEFAULT_NAME.equals(name);
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /** Derive a session name from the first user message (first line, trimmed to ~30 chars). */
    public static String deriveName(String firstMessage) {
        if (firstMessage == null || firstMessage.isBlank()) {
            return DEFAULT_NAME;
        }
        String firstLine = firstMessage.strip().split("\\R", 2)[0].strip();
        if (firstLine.length() > 30) {
            firstLine = firstLine.substring(0, 30).strip() + "…";
        }
        return firstLine.isBlank() ? DEFAULT_NAME : firstLine;
    }
}
