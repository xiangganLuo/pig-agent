package io.pigagent.core.profile;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Offline coverage for {@link UserProfileContextMiddleware} (capability {@code user-profile}): the
 * curated {@code USER.md} is appended to the system prompt when present + enabled, absent/blank or
 * disabled leaves it untouched, injection is byte-stable while the file is unchanged, and it is bounded
 * + credential-redacted (via {@link UserProfileStore}).
 */
class UserProfileContextMiddlewareTest {

    private static UserProfileContextMiddleware mw(Path userMd, int maxChars, boolean enabled) {
        return new UserProfileContextMiddleware(new UserProfileStore(userMd), maxChars, () -> enabled);
    }

    @Test
    void injectsProfileIntoSystemPrompt_whenPresentAndEnabled(@TempDir Path ws) throws IOException {
        Path userMd = ws.resolve("USER.md");
        Files.writeString(userMd, "- **name**: 罗湘赣\n- **language**: Chinese", StandardCharsets.UTF_8);

        String result = mw(userMd, 4000, true).onSystemPrompt(null, null, "BASE PROMPT").block();

        assertThat(result).startsWith("BASE PROMPT");
        assertThat(result).contains(UserProfileContextMiddleware.PROFILE_HEADER);
        assertThat(result).contains("罗湘赣").contains("Chinese");
    }

    @Test
    void disabled_leavesPromptUnchanged(@TempDir Path ws) throws IOException {
        Path userMd = ws.resolve("USER.md");
        Files.writeString(userMd, "- **name**: X", StandardCharsets.UTF_8);

        String result = mw(userMd, 4000, false).onSystemPrompt(null, null, "ONLY BASE").block();

        assertThat(result).isEqualTo("ONLY BASE");
    }

    @Test
    void missingFile_leavesPromptUnchanged(@TempDir Path ws) {
        String result = mw(ws.resolve("USER.md"), 4000, true).onSystemPrompt(null, null, "BASE").block();

        assertThat(result).isEqualTo("BASE");
    }

    @Test
    void blankFile_leavesPromptUnchanged(@TempDir Path ws) throws IOException {
        Path userMd = ws.resolve("USER.md");
        Files.writeString(userMd, "   \n\n  ", StandardCharsets.UTF_8);

        String result = mw(userMd, 4000, true).onSystemPrompt(null, null, "BASE").block();

        assertThat(result).isEqualTo("BASE");
    }

    @Test
    void injectionIsStableWhileFileUnchanged(@TempDir Path ws) throws IOException {
        Path userMd = ws.resolve("USER.md");
        Files.writeString(userMd, "- **name**: X", StandardCharsets.UTF_8);
        UserProfileContextMiddleware mw = mw(userMd, 4000, true);

        String first = mw.onSystemPrompt(null, null, "SYS").block();
        String second = mw.onSystemPrompt(null, null, "SYS").block();

        assertThat(second).isEqualTo(first); // prefix-cache friendly
    }

    @Test
    void injectionIsBounded_bySizeCap(@TempDir Path ws) throws IOException {
        Path userMd = ws.resolve("USER.md");
        Files.writeString(userMd, "- **note**: " + "y".repeat(1000), StandardCharsets.UTF_8);

        String result = mw(userMd, 120, true).onSystemPrompt(null, null, "BASE").block();

        // BASE + header + a bounded profile block — far smaller than the 1000-char file.
        assertThat(result).contains("truncated");
        assertThat(result.length()).isLessThan(400);
    }

    @Test
    void injection_redactsCredentials(@TempDir Path ws) throws IOException {
        Path userMd = ws.resolve("USER.md");
        Files.writeString(userMd, "- **secret**: sk-abcdef123456", StandardCharsets.UTF_8);

        String result = mw(userMd, 4000, true).onSystemPrompt(null, null, "BASE").block();

        assertThat(result).doesNotContain("sk-abcdef123456").contains("***");
    }
}
