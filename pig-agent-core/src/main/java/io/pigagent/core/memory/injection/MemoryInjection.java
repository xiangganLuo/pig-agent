package io.pigagent.core.memory.injection;

import java.util.Objects;

/**
 * Bundle of the two dependencies the {@code NativeMemoryContextMiddleware} needs for RAG-style
 * injection (capability {@code memory-retrieval-injection}): the immutable {@link MemoryInjectionSettings}
 * and the {@link MemoryRetriever} seam. Threaded (nullable) through {@code AgentFactory} /
 * {@code AgentInstanceFactory} → {@code PigAgent.Builder}. A {@code null} bundle (the default
 * everywhere) means today's behavior — the whole {@code MEMORY.md} injected into the system prompt and
 * no query-aware injection.
 *
 * @param settings  injection settings (non-null; {@link MemoryInjectionSettings#enabled()} may be false)
 * @param retriever the retrieval seam (non-null; typically {@code memorySearchIndex::search})
 */
public record MemoryInjection(MemoryInjectionSettings settings, MemoryRetriever retriever) {

    public MemoryInjection {
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(retriever, "retriever");
    }
}
