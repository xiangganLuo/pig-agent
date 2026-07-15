package io.pigagent.core.memory;

import io.agentscope.core.memory.LongTermMemory;
import io.agentscope.core.message.Msg;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Objects;

/**
 * A {@link LongTermMemory} <b>Decorator</b> that caches {@link #retrieve(Msg)} for the duration of a
 * single turn, keyed by the query's text content.
 *
 * <p>Motivation: {@code EphemeralMemoryContextHook} calls {@code retrieve} on every
 * {@code PreReasoningEvent} — i.e. on every ReAct reasoning step within one turn — and the query
 * (the last user message) is unchanged across those steps, so the retrieved memory is identical yet
 * was re-read from disk N times ({@code FileSystemLongTermMemory.retrieve} does a fresh
 * {@code Files.readString} each call). This decorator collapses those N reads to one.
 *
 * <p>Design:
 * <ul>
 *   <li><b>Single-slot cache keyed by query text.</b> A repeat retrieval with the same query text
 *       returns the cached result; a different query (a new turn) misses and re-reads. Content-based
 *       keying (rather than object identity) guarantees a same-query hit regardless of whether the
 *       framework reuses {@link Msg} instances.</li>
 *   <li><b>The cached value is a {@code .cache()}-wrapped {@code Mono}.</b> The underlying cold
 *       {@code Mono} (whose {@code Files.readString} runs on subscription) is subscribed once; later
 *       subscribers replay the cached value without re-reading disk.</li>
 *   <li><b>{@code record} delegates straight through and invalidates the cache.</b> Recording is a
 *       once-per-turn event ({@code PostCallEvent}); invalidating there guarantees the next turn
 *       re-reads even if its query text happens to equal the previous turn's, so a cached value can
 *       never go stale across turns.</li>
 * </ul>
 *
 * <p>Thread-safety: the tiny cache slot is guarded by an intrinsic lock. The agent is single-flight,
 * so contention is effectively nil; {@code Mono.cache()} is itself thread-safe.
 */
public final class CachingLongTermMemory implements LongTermMemory {

    private final LongTermMemory delegate;
    private final Object lock = new Object();

    private String cachedKey;      // guarded by lock
    private Mono<String> cached;   // guarded by lock; a .cache()-wrapped Mono

    public CachingLongTermMemory(LongTermMemory delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    /** The wrapped memory (exposed so callers can avoid double-wrapping). */
    public LongTermMemory delegate() {
        return delegate;
    }

    @Override
    public Mono<String> retrieve(Msg query) {
        String key = keyOf(query);
        synchronized (lock) {
            if (cached != null && Objects.equals(key, cachedKey)) {
                return cached; // per-turn cache hit: no disk re-read
            }
            Mono<String> fresh = delegate.retrieve(query).cache();
            cachedKey = key;
            cached = fresh;
            return fresh;
        }
    }

    @Override
    public Mono<Void> record(List<Msg> messages) {
        invalidate();
        return delegate.record(messages);
    }

    private void invalidate() {
        synchronized (lock) {
            cachedKey = null;
            cached = null;
        }
    }

    private static String keyOf(Msg query) {
        if (query == null) {
            return "";
        }
        String text = query.getTextContent();
        return text == null ? "" : text;
    }
}
