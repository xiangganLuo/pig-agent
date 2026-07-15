package io.pigagent.tool.sandbox;

/**
 * Result of a bounded output read (see {@link CommandGuard#capOutput}). Immutable value.
 *
 * @param text      the captured text (already including a truncation marker when {@code truncated})
 * @param truncated true if the underlying stream exceeded the configured byte cap
 */
public record CappedOutput(String text, boolean truncated) {
}
