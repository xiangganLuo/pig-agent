package io.pigagent.tool.spi.providers;

import io.pigagent.tool.notify.NotifyUserTool;
import io.pigagent.tool.spi.ToolContext;
import io.pigagent.tool.spi.ToolProvider;

/**
 * Auto-discovery provider for {@link NotifyUserTool}. Registers the tool only when a
 * {@link io.pigagent.core.outreach.NotificationService} is present on the context (i.e. outreach is
 * wired) — otherwise returns {@code null}, so a deployment without outreach never sees the tool.
 */
public final class NotifyUserToolProvider implements ToolProvider {
    @Override
    public Object create(ToolContext context) {
        if (context == null || context.notificationService() == null) {
            return null;
        }
        return new NotifyUserTool(context.notificationService(), context.outreachEnabled());
    }
}
