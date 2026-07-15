package io.pigagent.core.compression;

import io.agentscope.core.message.Msg;

import java.util.List;

/**
 * Strategy for estimating the token footprint of a list of messages.
 *
 * <p>Extracted as an interface so the estimation algorithm is decoupled from the compression
 * orchestration in {@code CompressionService} and can evolve (e.g. swap the char-budget heuristic
 * for a real tokenizer) without touching the trigger logic. The default implementation is
 * {@link CharBudgetTokenEstimator}.
 */
@FunctionalInterface
public interface TokenEstimator {

    /** Estimate the number of tokens the given messages would occupy (null → 0). */
    int estimate(List<Msg> messages);
}
