package io.pigagent.tool.availability;

import io.agentscope.core.tool.Toolkit;
import io.pigagent.tool.availability.ToolAvailabilityReport.Hidden;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Evaluates tool availability and removes unavailable tools from a {@link Toolkit} so they never
 * enter the schema handed to the model. This is the first-line filter, applied when the tool
 * definitions are assembled; it is independent of and stacks with the execution-time permission veto.
 *
 * <p>Only instances implementing {@link ToolAvailability} are considered — every other tool is
 * treated as available (backward compatible). An availability check that throws is fail-safe: the
 * governed tool names are hidden and processing of the remaining tools continues.
 */
public final class ToolAvailabilityGate {

    private static final Logger log = LoggerFactory.getLogger(ToolAvailabilityGate.class);

    private ToolAvailabilityGate() {
    }

    /**
     * Evaluate the availability of the given tool instances without touching any Toolkit. Instances
     * not implementing {@link ToolAvailability} are treated as available; a check that returns null or
     * throws yields "unavailable" (fail-safe).
     */
    public static ToolAvailabilityReport evaluate(Collection<?> tools) {
        List<Hidden> hidden = new ArrayList<>();
        if (tools != null) {
            for (Object tool : tools) {
                if (tool instanceof ToolAvailability ta) {
                    collectIfUnavailable(ta, hidden);
                }
            }
        }
        return new ToolAvailabilityReport(hidden);
    }

    /**
     * Evaluate availability and remove the unavailable tools from {@code toolkit}, so the model's
     * schema excludes them entirely. Returns the report of hidden (name, reason) for display.
     */
    public static ToolAvailabilityReport applyTo(Toolkit toolkit, Collection<?> tools) {
        ToolAvailabilityReport report = evaluate(tools);
        if (toolkit != null && !report.isEmpty()) {
            Set<String> present = toolkit.getToolNames();
            for (Hidden h : report.hidden()) {
                if (present.contains(h.toolName())) {
                    toolkit.removeTool(h.toolName());
                    log.info("Tool '{}' hidden from model schema: {}", h.toolName(), h.reason());
                }
            }
        }
        return report;
    }

    private static void collectIfUnavailable(ToolAvailability ta, List<Hidden> hidden) {
        Availability result;
        try {
            result = ta.checkAvailability();
            if (result == null) {
                result = Availability.unavailable("availability check returned no result");
            }
        } catch (Exception e) {
            String detail = (e.getMessage() == null || e.getMessage().isBlank())
                    ? e.getClass().getSimpleName() : e.getMessage();
            log.debug("Availability check threw; treating tool(s) as unavailable: {}", detail);
            result = Availability.unavailable("availability check failed: " + detail);
        }
        if (result.available()) {
            return;
        }
        Set<String> names = ta.availabilityToolNames();
        if (names == null) {
            return;
        }
        for (String name : names) {
            if (name != null && !name.isBlank()) {
                hidden.add(new Hidden(name, result.reason()));
            }
        }
    }
}
