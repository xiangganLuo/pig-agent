package io.pigagent.core.outreach;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Immutability, null-normalization, urgency and de-dup-key derivation for {@link Notification}. */
class NotificationTest {

    @Test
    void ofNormalizesNullsToSafeDefaults() {
        Notification n = new Notification(null, null, null, null, null, null, null, null);

        assertThat(n.type()).isEqualTo(NotificationType.MESSAGE);
        assertThat(n.severity()).isEqualTo(Severity.NORMAL);
        assertThat(n.title()).isEmpty();
        assertThat(n.body()).isEmpty();
        assertThat(n.targetChannel()).isNull();
        assertThat(n.recipient()).isNull();
    }

    @Test
    void blankTargetAndRecipientBecomeNull() {
        Notification n = Notification.of(NotificationType.MESSAGE, Severity.NORMAL, "t", "b")
                .withTarget("   ", "  ");

        assertThat(n.targetChannel()).isNull();
        assertThat(n.recipient()).isNull();
    }

    @Test
    void urgentReflectsSeverity() {
        assertThat(Notification.of(NotificationType.ALERT, Severity.URGENT, "t", "b").urgent()).isTrue();
        assertThat(Notification.of(NotificationType.MESSAGE, Severity.NORMAL, "t", "b").urgent()).isFalse();
    }

    @Test
    void effectiveDedupKeyDerivedFromTitleAndBodyWhenNotSet() {
        Notification n = Notification.of(NotificationType.MESSAGE, Severity.NORMAL, "Hi", "there");
        assertThat(n.effectiveDedupKey()).isEqualTo("Hi|there");
    }

    @Test
    void effectiveDedupKeyUsesExplicitKeyWhenSet() {
        Notification n = Notification.of(NotificationType.MESSAGE, Severity.NORMAL, "Hi", "there")
                .withDedupKey("k-123");
        assertThat(n.effectiveDedupKey()).isEqualTo("k-123");
    }

    @Test
    void withMethodsReturnNewImmutableCopies() {
        Notification base = Notification.of(NotificationType.MESSAGE, Severity.NORMAL, "t", "b");

        Notification targeted = base.withTarget("feishu", "u1");
        Notification acted = base.withAction(OutreachAction.yesNo("ok?"));

        assertThat(base.targetChannel()).isNull();          // original untouched
        assertThat(base.action()).isNull();
        assertThat(targeted.targetChannel()).isEqualTo("feishu");
        assertThat(targeted.recipient()).isEqualTo("u1");
        assertThat(acted.action().options()).containsExactly("yes", "no");
    }
}
