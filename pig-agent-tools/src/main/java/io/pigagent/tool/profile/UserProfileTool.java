package io.pigagent.tool.profile;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.pigagent.core.profile.UserProfileStore;
import io.pigagent.tool.availability.Availability;
import io.pigagent.tool.availability.ToolAvailability;
import io.pigagent.tool.contract.ToolErrors;

import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * Lets the agent write a <b>confirmed</b> durable user preference/identity into the curated user
 * profile ({@code USER.md}) immediately — capability {@code user-profile} (Hermes {@code USER.md}
 * blueprint). Use it when the user states something durable about themselves ("call me X", "always
 * reply in Chinese", a standing tech preference), NOT for transient/task-specific facts (those belong
 * to the long-term memory).
 *
 * <p>Classified {@code WRITE} in {@code ToolRiskClassifier} (governed by the permission system) and
 * gated by {@link ToolAvailability}: when the profile is disabled the tool is hidden from the model
 * schema entirely (zero behavior change when off). The merge is deterministic (set/replace one field,
 * no model call); the value is credential-redacted by the store. Return contract: a successful write
 * returns a short confirmation; a failure returns the canonical {@code {"error":"<reason>"}} (never
 * throws), and secrets are never echoed.
 */
public final class UserProfileTool implements ToolAvailability {

    static final String TOOL_NAME = "updateProfile";

    private final UserProfileStore store;
    private final BooleanSupplier enabled;

    public UserProfileTool(UserProfileStore store, BooleanSupplier enabled) {
        this.store = Objects.requireNonNull(store, "store");
        this.enabled = enabled == null ? () -> false : enabled;
    }

    @Tool(description = "Record a durable fact about the USER into their profile (USER.md): their "
            + "identity (e.g. name / how to address them), a standing preference (e.g. reply language, "
            + "output style, technology preference) or working style. Sets or replaces one field. Use "
            + "only for confirmed, durable user facts — not transient or task-specific details.")
    public String updateProfile(
            @ToolParam(name = "field", description = "The profile field name, e.g. 'name', 'language', "
                    + "'output-style'") String field,
            @ToolParam(name = "value", description = "The field value, e.g. 'Alice', 'Chinese', "
                    + "'concise and direct'") String value) {
        try {
            if (field == null || field.isBlank()) {
                return ToolErrors.message("updateProfile: field must not be empty");
            }
            boolean ok = store.updateField(field, value == null ? "" : value);
            if (!ok) {
                return ToolErrors.message("updateProfile: could not write the profile");
            }
            return "Updated user profile field '" + field.strip() + "'.";
        } catch (Exception e) {
            return ToolErrors.message("updateProfile failed: " + e.getClass().getSimpleName());
        }
    }

    @Override
    public Set<String> availabilityToolNames() {
        return Set.of(TOOL_NAME);
    }

    @Override
    public Availability checkAvailability() {
        return enabled.getAsBoolean()
                ? Availability.AVAILABLE
                : Availability.unavailable("user profile disabled (set user-profile.enabled)");
    }
}
