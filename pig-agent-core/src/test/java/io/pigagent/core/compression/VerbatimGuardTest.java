package io.pigagent.core.compression;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the verbatim guard: code / commands / IDs / hashes / long quotes are protected;
 * ordinary chatter is not. Pure detector, no model.
 */
class VerbatimGuardTest {

    private final VerbatimGuard guard = new VerbatimGuard();

    private static Msg user(String t) {
        return Msg.builder().name("u").role(MsgRole.USER).content(TextBlock.builder().text(t).build()).build();
    }

    @Test
    void fencedCodeIsProtected() {
        Msg m = user("Here is the fix:\n```java\nint x = compute();\n```\nApply it.");
        assertThat(guard.isProtected(m)).isTrue();
    }

    @Test
    void commandLineIsProtected() {
        assertThat(guard.containsProtected("git rebase -i origin/main")).isTrue();
        assertThat(guard.containsProtected("mvn -q -pl pig-agent-core test")).isTrue();
    }

    @Test
    void hashAndUuidAreProtected() {
        assertThat(guard.containsProtected("commit 3cb26d9e1f0a")).isTrue();
        assertThat(guard.containsProtected("id 550e8400-e29b-41d4-a716-446655440000")).isTrue();
    }

    @Test
    void longQuoteIsProtected() {
        assertThat(guard.containsProtected("The error was \"connection refused by host\" earlier."))
                .isTrue();
    }

    @Test
    void plainChatterIsNotProtected() {
        assertThat(guard.isProtected(user("please run the build and tell me if it works"))).isFalse();
        assertThat(guard.isProtected(user("message 3"))).isFalse();
        assertThat(guard.isProtected(user("thanks, that looks good to me"))).isFalse();
    }

    @Test
    void protectedSpans_returnsMatches() {
        // Arrange
        String text = "run `git status` and note hash 3cb26d9e1f0a";

        // Act
        var spans = guard.protectedSpans(text);

        // Assert: both the inline command and the hash are captured.
        assertThat(spans).anyMatch(s -> s.contains("git status"));
        assertThat(spans).anyMatch(s -> s.contains("3cb26d9e1f0a"));
    }
}
