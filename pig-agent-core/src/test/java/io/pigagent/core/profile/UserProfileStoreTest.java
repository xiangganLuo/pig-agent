package io.pigagent.core.profile;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Offline coverage for {@link UserProfileStore} (capability {@code user-profile}): deterministic
 * set/merge, no-duplicate replacement, credential redaction, bounded read (size cap), and
 * fault-tolerant absence.
 */
class UserProfileStoreTest {

    @Test
    void updateField_createsFileWithHeadingAndFieldLine(@TempDir Path ws) {
        UserProfileStore store = new UserProfileStore(ws.resolve("USER.md"));

        boolean ok = store.updateField("name", "罗湘赣");

        assertThat(ok).isTrue();
        assertThat(store.readFull())
                .contains("# User Profile")
                .contains("- **name**: 罗湘赣");
    }

    @Test
    void updateField_replacesExistingFieldWithoutDuplicating(@TempDir Path ws) {
        UserProfileStore store = new UserProfileStore(ws.resolve("USER.md"));
        store.updateField("name", "old");

        store.updateField("Name", "new"); // case-insensitive match

        String content = store.readFull();
        assertThat(content).contains("- **Name**: new");
        assertThat(content).doesNotContain("old");
        // only one name line survives (no duplication)
        int count = content.split("(?i)\\*\\*name\\*\\*", -1).length - 1;
        assertThat(count).isEqualTo(1);
    }

    @Test
    void updateField_appendsSecondDistinctField(@TempDir Path ws) {
        UserProfileStore store = new UserProfileStore(ws.resolve("USER.md"));
        store.updateField("name", "X");

        store.updateField("language", "Chinese");

        assertThat(store.readFull())
                .contains("- **name**: X")
                .contains("- **language**: Chinese");
    }

    @Test
    void updateField_redactsCredentialsInValue(@TempDir Path ws) {
        UserProfileStore store = new UserProfileStore(ws.resolve("USER.md"));

        store.updateField("token", "my key is sk-abcdef123456 keep it");

        assertThat(store.readFull())
                .doesNotContain("sk-abcdef123456")
                .contains("***");
    }

    @Test
    void updateField_collapsesNewlinesToSingleLine(@TempDir Path ws) {
        UserProfileStore store = new UserProfileStore(ws.resolve("USER.md"));

        store.updateField("style", "concise\nand\ndirect");

        assertThat(store.readFull()).contains("- **style**: concise and direct");
    }

    @Test
    void updateField_blankFieldRejected(@TempDir Path ws) {
        UserProfileStore store = new UserProfileStore(ws.resolve("USER.md"));

        assertThat(store.updateField("   ", "value")).isFalse();
        assertThat(Files.exists(ws.resolve("USER.md"))).isFalse();
    }

    @Test
    void read_boundedToMaxChars_appendsTruncationMarker(@TempDir Path ws) throws IOException {
        Path userMd = ws.resolve("USER.md");
        Files.writeString(userMd, "x".repeat(500), StandardCharsets.UTF_8);
        UserProfileStore store = new UserProfileStore(userMd);

        String read = store.read(100);

        assertThat(read.length()).isLessThanOrEqualTo(100);
        assertThat(read).contains("truncated");
    }

    @Test
    void read_missingFile_returnsEmpty(@TempDir Path ws) {
        UserProfileStore store = new UserProfileStore(ws.resolve("nope.md"));

        assertThat(store.read(4000)).isEmpty();
        assertThat(store.readFull()).isEmpty();
    }

    @Test
    void read_redactsCredentialsInHandEditedFile(@TempDir Path ws) throws IOException {
        Path userMd = ws.resolve("USER.md");
        Files.writeString(userMd, "- **note**: Bearer abcDEF12345 secret", StandardCharsets.UTF_8);
        UserProfileStore store = new UserProfileStore(userMd);

        assertThat(store.read(4000)).doesNotContain("abcDEF12345").contains("***");
    }

    @Test
    void write_overwritesWholeBody_redacted(@TempDir Path ws) {
        UserProfileStore store = new UserProfileStore(ws.resolve("USER.md"));
        store.updateField("name", "X");

        boolean ok = store.write("# User Profile\n\n- **name**: Y\n- **key**: token=sk-zzzzzz999999\n");

        assertThat(ok).isTrue();
        assertThat(store.readFull())
                .contains("- **name**: Y")
                .doesNotContain("sk-zzzzzz999999");
    }
}
