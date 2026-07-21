package io.pigagent.cli.tools;

import io.agentscope.core.tool.Tool;
import io.pigagent.tool.availability.Availability;
import io.pigagent.tool.availability.ToolAvailability;

import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Test fixture: a {@code @Tool} whose availability flips with a mutable flag, so the availability hot
 * re-evaluation ({@link ToolAvailabilityRefresher}) can be exercised offline. The reason names a
 * prerequisite only (never a credential value).
 */
public final class ToggleAvailabilityTool implements ToolAvailability {

    private final AtomicBoolean available;

    public ToggleAvailabilityTool(AtomicBoolean available) {
        this.available = available;
    }

    @Tool(description = "Search the web (needs a prerequisite).")
    public String webSearch() {
        return "searched";
    }

    @Override
    public Set<String> availabilityToolNames() {
        return Set.of("webSearch");
    }

    @Override
    public Availability checkAvailability() {
        return available.get() ? Availability.AVAILABLE : Availability.unavailable("SEARCH_KEY not set");
    }
}
