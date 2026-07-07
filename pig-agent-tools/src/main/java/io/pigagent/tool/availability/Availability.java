package io.pigagent.tool.availability;

/**
 * Immutable result of a tool availability check: whether a tool's runtime preconditions are met,
 * plus an optional human-readable reason when they are not.
 *
 * <p>The {@code reason} MUST describe only the missing prerequisite (e.g. an environment variable
 * name) and MUST NOT contain any credential value. When {@code available} is {@code true} the reason
 * is normalized to {@code null}; when unavailable a blank reason is normalized to {@code "unavailable"}.
 */
public record Availability(boolean available, String reason) {

    /** Shared instance for a tool whose preconditions are satisfied. */
    public static final Availability AVAILABLE = new Availability(true, null);

    public Availability {
        if (available) {
            reason = null;
        } else if (reason == null || reason.isBlank()) {
            reason = "unavailable";
        }
    }

    /** A tool that cannot run; {@code reason} names the missing prerequisite (never its value). */
    public static Availability unavailable(String reason) {
        return new Availability(false, reason);
    }
}
