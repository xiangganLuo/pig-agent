package io.pigagent.core.agent.kernel;

/**
 * The outcome of a {@link ToolAdmin} action ({@code /tools enable|disable|refresh}) — whether it
 * changed anything and a human-readable, <b>credential-free</b> message for display.
 *
 * @param changed true when the action mutated tool visibility / availability
 * @param message a display message; MUST NOT contain any credential value
 */
public record ToolActionResult(boolean changed, String message) {

    public ToolActionResult {
        message = message == null ? "" : message;
    }

    /** A result that changed state. */
    public static ToolActionResult changed(String message) {
        return new ToolActionResult(true, message);
    }

    /** A result that made no change (already in the requested state, or a no-op hint). */
    public static ToolActionResult noChange(String message) {
        return new ToolActionResult(false, message);
    }
}
