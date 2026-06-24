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
 */
public record Session(
        String id,
        String name,
        Instant createdAt,
        Instant lastActiveAt,
        boolean corrupt) {

    /** Name given to a freshly created session before the first message renames it. */
    public static final String DEFAULT_NAME = "New session";

    /** Create a new blank session with a generated short id. */
    public static Session create(String name) {
        Instant now = Instant.now();
        String resolved = (name == null || name.isBlank()) ? DEFAULT_NAME : name.strip();
        return new Session(UUID.randomUUID().toString().substring(0, 8), resolved, now, now, false);
    }

    public Session withName(String newName) {
        return new Session(id, newName, createdAt, lastActiveAt, corrupt);
    }

    public Session withLastActiveAt(Instant when) {
        return new Session(id, name, createdAt, when, corrupt);
    }

    public boolean hasDefaultName() {
        return DEFAULT_NAME.equals(name);
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
