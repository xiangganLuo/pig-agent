package io.pigagent.core.profile;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The cross-session profile guarantee (capability {@code user-profile}), mirroring
 * {@code CrossSessionMemoryTest}: a profile field set in "session A" is visible in the profile injected
 * in "session B", because {@code USER.md} is a single workspace-level file (no session scoping) — it
 * does not vanish across {@code /session new}. Deterministic, offline (no live model).
 */
class UserProfileCrossSessionTest {

    @Test
    void nameSetInSessionA_appearsInInjectedProfileInSessionB(@TempDir Path workspace) {
        Path userMd = workspace.resolve("USER.md");

        // Session A: the agent confirms the user's name via updateProfile (workspace-level USER.md).
        UserProfileStore sessionAStore = new UserProfileStore(userMd);
        assertThat(sessionAStore.updateField("name", "罗湘赣")).isTrue();

        // Session B (/session new → a DIFFERENT store instance over the SAME workspace file): the
        // profile injection middleware surfaces the name into the system prompt.
        UserProfileContextMiddleware sessionB =
                new UserProfileContextMiddleware(new UserProfileStore(userMd), 4000, () -> true);
        String injected = sessionB.onSystemPrompt(null, null, "SYSTEM").block();

        assertThat(injected)
                .as("name set in session A must be injected into session B's profile (workspace-level USER.md)")
                .contains("罗湘赣")
                .contains(UserProfileContextMiddleware.PROFILE_HEADER);
    }
}
