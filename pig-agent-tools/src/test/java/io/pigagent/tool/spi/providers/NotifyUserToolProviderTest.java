package io.pigagent.tool.spi.providers;

import io.pigagent.core.outreach.NotificationResult;
import io.pigagent.core.outreach.NotificationService;
import io.pigagent.tool.notify.NotifyUserTool;
import io.pigagent.tool.spi.ToolContext;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** {@link NotifyUserToolProvider} registers only when a notification service is present. */
class NotifyUserToolProviderTest {

    private static final NotificationService STUB = n -> NotificationResult.delivered("ok");

    @Test
    void returnsNullWhenNoNotificationService() {
        ToolContext ctx = new ToolContext(null, null); // no outreach wired
        assertThat(new NotifyUserToolProvider().create(ctx)).isNull();
    }

    @Test
    void returnsToolWhenServicePresent() {
        ToolContext ctx = new ToolContext(null, null, null, null, null, STUB, () -> true);
        Object tool = new NotifyUserToolProvider().create(ctx);
        assertThat(tool).isInstanceOf(NotifyUserTool.class);
    }
}
