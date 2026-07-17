package io.pigagent.tool.profile;

import io.pigagent.core.profile.UserProfileStore;
import io.pigagent.tool.spi.ToolContext;
import io.pigagent.tool.spi.providers.UserProfileToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Offline coverage for {@link UserProfileTool} (capability {@code user-profile}): deterministic
 * set/merge into {@code USER.md}, the {@code {"error"}} failure contract, availability gating on
 * {@code user-profile.enabled}, and the SPI provider (registers only when a profile file is wired).
 */
class UserProfileToolTest {

    @Test
    void updateProfile_writesField(@TempDir Path ws) {
        Path userMd = ws.resolve("USER.md");
        UserProfileTool tool = new UserProfileTool(new UserProfileStore(userMd), () -> true);

        String result = tool.updateProfile("name", "罗湘赣");

        assertThat(result).contains("Updated user profile");
        assertThat(new UserProfileStore(userMd).readFull()).contains("- **name**: 罗湘赣");
    }

    @Test
    void updateProfile_mergesExistingField(@TempDir Path ws) {
        Path userMd = ws.resolve("USER.md");
        UserProfileStore store = new UserProfileStore(userMd);
        UserProfileTool tool = new UserProfileTool(store, () -> true);
        tool.updateProfile("language", "English");

        tool.updateProfile("language", "Chinese");

        String content = store.readFull();
        assertThat(content).contains("- **language**: Chinese").doesNotContain("English");
    }

    @Test
    void updateProfile_blankField_returnsError() {
        UserProfileTool tool = new UserProfileTool(
                new UserProfileStore(Path.of("does-not-matter")), () -> true);

        String result = tool.updateProfile("  ", "value");

        assertThat(result).startsWith("{\"error\":");
    }

    @Test
    void availability_reflectsEnabledFlag(@TempDir Path ws) {
        UserProfileStore store = new UserProfileStore(ws.resolve("USER.md"));

        assertThat(new UserProfileTool(store, () -> true).checkAvailability().available()).isTrue();
        assertThat(new UserProfileTool(store, () -> false).checkAvailability().available()).isFalse();
    }

    @Test
    void provider_registersOnlyWhenProfileFileWired(@TempDir Path ws) {
        UserProfileToolProvider provider = new UserProfileToolProvider();

        // No profile file on the context → no tool.
        assertThat(provider.create(new ToolContext(null, null))).isNull();

        // Profile file present → a UserProfileTool.
        ToolContext ctx = new ToolContext(null, null, ws, null, null, null, null,
                ws.resolve("USER.md"), () -> true);
        assertThat(provider.create(ctx)).isInstanceOf(UserProfileTool.class);
    }
}
