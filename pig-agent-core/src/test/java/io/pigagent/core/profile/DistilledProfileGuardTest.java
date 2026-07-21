package io.pigagent.core.profile;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Offline coverage for {@link DistilledProfileGuard} (capability {@code user-profile}, M-E): the
 * deterministic, model-free conservative filter that gates auto-distilled content before it is written
 * to {@code USER.md}. It keeps only conforming {@code - **<Field>**: <value>} lines whose field name is
 * in a conservative identity/preference whitelist, drops free-form prose / out-of-whitelist / overlong
 * lines, canonicalizes the heading, and yields an empty string when nothing conforms (so the caller
 * declines to write).
 */
class DistilledProfileGuardTest {

    @Test
    void keepsWhitelistedIdentityAndPreferenceFields() {
        String distilled = """
                # User Profile

                - **name**: 罗湘赣
                - **language**: Chinese
                - **how to address you**: 罗总
                - **output style**: concise
                - **technology preferences**: Java, Maven
                - **working style**: TDD
                - **timezone**: Asia/Shanghai
                - **role**: staff engineer
                - **pronouns**: he/him
                """;

        String kept = DistilledProfileGuard.filter(distilled);

        assertThat(kept).contains("- **name**: 罗湘赣")
                .contains("- **language**: Chinese")
                .contains("- **how to address you**: 罗总")
                .contains("- **output style**: concise")
                .contains("- **technology preferences**: Java, Maven")
                .contains("- **working style**: TDD")
                .contains("- **timezone**: Asia/Shanghai")
                .contains("- **role**: staff engineer")
                .contains("- **pronouns**: he/him");
        assertThat(kept).startsWith("# User Profile");
    }

    @Test
    void dropsFreeFormProseAndOutOfWhitelistFields() {
        String distilled = """
                # User Profile

                The user has been working on a parser refactor and mentioned a headache.
                - **name**: 罗湘赣
                - **Current Task**: refactor the parser
                - **Home Address**: 123 Main St
                - **language**: Chinese
                """;

        String kept = DistilledProfileGuard.filter(distilled);

        assertThat(kept).contains("- **name**: 罗湘赣").contains("- **language**: Chinese");
        assertThat(kept).doesNotContain("headache")
                .doesNotContain("Current Task")
                .doesNotContain("Home Address")
                .doesNotContain("123 Main St");
    }

    @Test
    void fieldNameMatchingIsCaseAndSeparatorInsensitive() {
        String distilled = "- **Name**: X\n- **Output-Style**: terse\n- **tech_preferences**: Go\n";

        String kept = DistilledProfileGuard.filter(distilled);

        assertThat(kept).contains("- **Name**: X")
                .contains("- **Output-Style**: terse")
                .contains("- **tech_preferences**: Go");
    }

    @Test
    void dropsOverlongOrEmptyValues() {
        String longValue = "x".repeat(500);
        String distilled = "# User Profile\n\n- **name**: \n- **language**: " + longValue + "\n";

        String kept = DistilledProfileGuard.filter(distilled);

        assertThat(kept).isEmpty(); // empty-valued and overlong lines both dropped → nothing conforms
    }

    @Test
    void returnsEmptyWhenNoConformingLines() {
        assertThat(DistilledProfileGuard.filter("just some prose\n\nmore prose")).isEmpty();
        assertThat(DistilledProfileGuard.filter("# User Profile\n\n- **Current Task**: x\n")).isEmpty();
        assertThat(DistilledProfileGuard.filter("")).isEmpty();
        assertThat(DistilledProfileGuard.filter(null)).isEmpty();
    }

    @Test
    void canonicalizesHeadingWhenAbsent() {
        String kept = DistilledProfileGuard.filter("- **name**: X\n");

        assertThat(kept).startsWith("# User Profile").contains("- **name**: X");
    }
}
