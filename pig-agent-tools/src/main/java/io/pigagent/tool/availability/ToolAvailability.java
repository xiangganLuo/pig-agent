package io.pigagent.tool.availability;

import java.util.Set;

/**
 * Optional interface a built-in tool class MAY implement to declare a runtime availability
 * precondition (e.g. a required environment variable, binary, or service). This is deliberately
 * <em>non-invasive</em>: it does not touch the {@code @Tool}/{@code @ToolParam} method signatures,
 * and tools that do not implement it are always treated as available (backward compatible).
 *
 * <p>Unavailable tools are filtered out of the schema handed to the model (see
 * {@link ToolAvailabilityGate}) so the model never sees — and cannot hallucinate a call to — a tool
 * whose prerequisites are missing. This filtering is orthogonal to and stacks with the execution-time
 * permission veto: availability decides visibility; permissions decide whether a visible tool may run.
 */
public interface ToolAvailability {

    /**
     * The tool schema name(s) this availability check governs, exactly as registered in the Toolkit
     * (for a {@code @Tool} method with no explicit name, that is the method name). Returning multiple
     * names lets one class gate several of its tools with a single check.
     */
    Set<String> availabilityToolNames();

    /**
     * Evaluate whether this tool's preconditions are currently met. The check SHOULD be cheap (no
     * network round-trips) and its reason MUST NOT include any credential value — only the name of the
     * missing prerequisite. A thrown exception is treated as unavailable (fail-safe) by the gate.
     */
    Availability checkAvailability();
}
