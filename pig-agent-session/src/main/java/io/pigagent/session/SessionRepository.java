package io.pigagent.session;

import java.util.List;
import java.util.Optional;

/**
 * Persistence for session metadata. Implementations must be fault-tolerant: a single
 * unreadable session must not break {@link #findAll()} for the others.
 */
public interface SessionRepository {
    Session save(Session session);

    Optional<Session> findById(String id);

    /** All sessions, including {@code corrupt} placeholders for unreadable ones. */
    List<Session> findAll();

    /** Remove a session and all of its on-disk data (conversation + temp memory). */
    void deleteById(String id);
}
