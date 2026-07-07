package io.pigagent.tool.availability;

import java.util.List;

/**
 * Immutable, queryable record of which tools were hidden from the model schema because their
 * availability preconditions were not met, and why. Surfaced to the user (e.g. via {@code /status})
 * so a missing prerequisite is discoverable and fixable. Reasons name the missing prerequisite only
 * and never contain credential values.
 */
public record ToolAvailabilityReport(List<Hidden> hidden) {

    public ToolAvailabilityReport {
        hidden = hidden == null ? List.of() : List.copyOf(hidden);
    }

    /** An empty report (no tools hidden). */
    public static ToolAvailabilityReport empty() {
        return new ToolAvailabilityReport(List.of());
    }

    public boolean isEmpty() {
        return hidden.isEmpty();
    }

    /** A single hidden tool and the reason it was hidden (prerequisite name, never a credential). */
    public record Hidden(String toolName, String reason) {
    }
}
