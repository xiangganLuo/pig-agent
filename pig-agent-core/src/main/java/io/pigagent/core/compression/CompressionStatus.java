package io.pigagent.core.compression;

/**
 * Snapshot of the compression state for one session, shown by {@code /compress status}.
 *
 * @param enabled              whether auto-compression is on for this session
 * @param estimatedTokens      rough token estimate of the current in-memory conversation
 * @param budgetTokens         configured context budget
 * @param thresholdTokens      token level at which auto-compression triggers
 * @param messageCount         number of messages currently in the in-memory conversation
 * @param lastCompressedEpochMs time of the last compression (0 if never)
 * @param budget               three-tier allocation (pinned / recent verbatim / summarized) of the budget
 */
public record CompressionStatus(
        boolean enabled,
        int estimatedTokens,
        int budgetTokens,
        int thresholdTokens,
        int messageCount,
        long lastCompressedEpochMs,
        ContextBudget budget) {
}
