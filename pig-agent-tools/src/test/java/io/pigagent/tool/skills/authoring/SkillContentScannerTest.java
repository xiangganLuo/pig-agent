package io.pigagent.tool.skills.authoring;

import io.pigagent.tool.skills.SkillLimits;
import io.pigagent.tool.skills.SkillManifestParser;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** DefaultSkillContentScanner: name / size / credential / structure — reject reasons never echo a secret. */
class SkillContentScannerTest {

    private final SkillContentScanner scanner = SkillContentScanner.defaults();

    private static String md(String name, String desc, String body) {
        return new SkillDraft(name, desc, java.util.List.of(), body).toSkillMd();
    }

    @Test
    void cleanSkillPasses() {
        SkillScanResult r = scanner.scan("good", md("good", "A clean skill", "# Good\nStep 1."));
        assertThat(r.passed()).isTrue();
        assertThat(r.reasons()).isEmpty();
    }

    @Test
    void credentialBearingSkillRejected_reasonDoesNotEchoSecret() {
        String secret = "sk-abcDEF1234567890";
        SkillScanResult r = scanner.scan("leaky", md("leaky", "desc", "# Leaky\nkey is " + secret));
        assertThat(r.passed()).isFalse();
        assertThat(String.join(" ", r.reasons())).contains("credential").doesNotContain(secret);
    }

    @Test
    void oversizedSkillRejected() {
        SkillContentScanner small = new DefaultSkillContentScanner(
                new SkillLimits(64, 0, 0, 0), SkillManifestParser.defaults());
        String big = md("big", "desc", "# Big\n" + "x".repeat(500));
        SkillScanResult r = small.scan("big", big);
        assertThat(r.passed()).isFalse();
        assertThat(String.join(" ", r.reasons())).contains("too large");
    }

    @Test
    void invalidNameRejected() {
        SkillScanResult r = scanner.scan("../evil", md("evil", "desc", "# E\nbody"));
        assertThat(r.passed()).isFalse();
        assertThat(String.join(" ", r.reasons())).contains("invalid skill name");
    }

    @Test
    void missingDescriptionAndBodyRejected() {
        // Empty content → derived name may be blank, description blank, body blank.
        SkillScanResult r = scanner.scan("empty", "");
        assertThat(r.passed()).isFalse();
        assertThat(String.join(" ", r.reasons())).contains("empty skill body");
    }

    @Test
    void neverThrows() {
        assertThat(scanner.scan(null, null).passed()).isFalse();
    }
}
