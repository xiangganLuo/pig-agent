package io.pigagent.core.memory.extraction;

import java.util.List;

/**
 * Persistence seam for the session-tier extracted facts. Decouples the extraction pipeline from
 * <em>how</em> facts are stored (file, in-memory fake for tests, …). Implementations MUST be
 * fault-tolerant on read (a corrupt/legacy line is skipped, never crashes {@link #load()}).
 */
public interface FactStore {

    /** Load the currently persisted facts (empty when none / unreadable). */
    List<ExtractedFact> load();

    /** Persist the given facts, replacing prior content (the merge happens before this call). */
    void save(List<ExtractedFact> facts);
}
