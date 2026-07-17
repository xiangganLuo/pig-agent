package io.pigagent.tool.spi.providers;

import io.pigagent.core.profile.UserProfileStore;
import io.pigagent.tool.profile.UserProfileTool;
import io.pigagent.tool.spi.ToolContext;
import io.pigagent.tool.spi.ToolProvider;

/**
 * Auto-discovery provider for {@link UserProfileTool} ({@code user-profile}). Registers the tool only
 * when a user-profile file is present on the context (i.e. profile is wired) — otherwise returns
 * {@code null}, so a deployment without the profile subsystem never sees the tool. Visibility further
 * tracks {@code user-profile.enabled} via {@code ToolAvailability} (hidden when disabled).
 */
public final class UserProfileToolProvider implements ToolProvider {
    @Override
    public Object create(ToolContext context) {
        if (context == null || context.userProfileFile() == null) {
            return null;
        }
        return new UserProfileTool(
                new UserProfileStore(context.userProfileFile()), context.userProfileEnabled());
    }
}
