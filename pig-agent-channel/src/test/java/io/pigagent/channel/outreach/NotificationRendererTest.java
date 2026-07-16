package io.pigagent.channel.outreach;

import io.pigagent.core.outreach.Notification;
import io.pigagent.core.outreach.NotificationType;
import io.pigagent.core.outreach.OutreachAction;
import io.pigagent.core.outreach.Severity;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Plain-text rendering of a {@link Notification}. */
class NotificationRendererTest {

    @Test
    void rendersTitleAndBody() {
        Notification n = Notification.of(NotificationType.BRIEFING, Severity.NORMAL, "每日简报", "今天有 3 件事");
        String text = NotificationRenderer.render(n);
        assertThat(text).isEqualTo("每日简报\n\n今天有 3 件事");
    }

    @Test
    void rendersActionPromptWithOptions() {
        Notification n = Notification.of(NotificationType.ACTION_REQUEST, Severity.NORMAL, "确认", "要部署吗")
                .withAction(OutreachAction.yesNo("请回复"));
        String text = NotificationRenderer.render(n);
        assertThat(text).contains("确认").contains("要部署吗").contains("请回复 [yes / no]");
    }

    @Test
    void toleratesBlankFields() {
        Notification onlyBody = Notification.of(NotificationType.MESSAGE, Severity.NORMAL, "", "just body");
        assertThat(NotificationRenderer.render(onlyBody)).isEqualTo("just body");
        assertThat(NotificationRenderer.render(null)).isEmpty();
    }
}
