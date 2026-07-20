package io.pigagent.tool.skills;

import io.agentscope.core.skill.repository.FileSystemSkillRepository;
import io.agentscope.core.skill.util.MarkdownSkillParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * native-skill-engine-bridge — the load-bearing SPIKE, parts A (directory-layout compatibility) and
 * C (parser equivalence), run offline against the real AgentScope 2.0 <b>core</b> artifacts only
 * (no harness — {@code pig-agent-tools} deliberately does not depend on it for S1).
 *
 * <ul>
 *   <li><b>C — parser equivalence:</b> pig's {@link FrontMatterManifestParser} (via
 *       {@link SkillManifestParser#defaults()}) and the native
 *       {@link MarkdownSkillParser#parse(String)} agree on the discovery-driving core keys
 *       ({@code name}/{@code description}) and both strip the front-matter from the body. Known,
 *       documented divergence: pig derives a missing description from the first body line; the native
 *       parser leaves it absent.</li>
 *   <li><b>A — layout compatibility:</b> a native {@link FileSystemSkillRepository} pointed at pig's
 *       {@code workspace/skills/<name>/SKILL.md} layout discovers the same skill names as pig's
 *       {@link WorkspaceSkillSource} (excluding the dot-prefixed staging dir). The native repo's own
 *       behaviour toward the {@code .pending} dot-dir is recorded — pig's source filters it regardless
 *       (D5), so the adapter defends the autonomous-skills staging invariant either way.</li>
 * </ul>
 */
class NativeSkillParserSpikeTest {

    private static final String WITH_FM =
            "---\nname: alpha\ndescription: the alpha skill\nkeywords: a, b\n---\n# Alpha\nbody line\n";

    // ---- Part C: parser equivalence -------------------------------------------------------------

    @Test
    void parsers_agreeOnCoreKeys_andStripBody() {
        // Act — parse the same SKILL.md with both parsers.
        SkillManifest pig = SkillManifestParser.defaults().parse(WITH_FM, "alpha");
        MarkdownSkillParser.ParsedMarkdown native_ = MarkdownSkillParser.parse(WITH_FM);

        // Assert — core keys (name/description) agree.
        assertThat(native_.hasFrontmatter()).isTrue();
        assertThat(String.valueOf(native_.getMetadata().get("name")))
                .as("both parsers read the same name").isEqualTo(pig.metadata().name()).isEqualTo("alpha");
        assertThat(String.valueOf(native_.getMetadata().get("description")))
                .as("both parsers read the same description")
                .isEqualTo(pig.metadata().description()).isEqualTo("the alpha skill");
        // Both strip the front-matter, leaving the body opening with its heading.
        assertThat(pig.body().stripLeading()).startsWith("# Alpha");
        assertThat(native_.getContent().stripLeading()).startsWith("# Alpha");
    }

    @Test
    void parsers_bothTolerateNoFrontMatter_documentedDescriptionDivergence() {
        // Arrange — plain SKILL.md, no front-matter.
        String plain = "# Beta\n\nthe beta body\n";

        // Act
        SkillManifest pig = SkillManifestParser.defaults().parse(plain, "beta");
        MarkdownSkillParser.ParsedMarkdown native_ = MarkdownSkillParser.parse(plain);

        // Assert — neither throws; native reports no front-matter; pig derives a description from the
        // first body line (documented divergence — does NOT affect S1, which uses AgentSkill's own
        // name/description from the native repo, not pig's parser).
        assertThat(native_.hasFrontmatter()).isFalse();
        assertThat(pig.metadata().description()).isEqualTo("Beta");
        Object nativeDesc = native_.getMetadata().get("description");
        assertThat(nativeDesc == null || String.valueOf(nativeDesc).isBlank())
                .as("native parser leaves description absent when there is no front-matter").isTrue();
    }

    // ---- Part A: directory-layout compatibility -------------------------------------------------

    @Test
    void nativeFileSystemRepo_discoversSameLayout_documentedStrictnessDivergence(@TempDir Path ws)
            throws IOException {
        // Arrange — pig's workspace/skills/<name>/SKILL.md layout: two front-matter skills (alpha,
        // gamma), one bare skill with NO front-matter (beta), plus a .pending staging draft.
        Path skills = Files.createDirectories(ws.resolve("skills"));
        writeSkill(skills.resolve("alpha"), WITH_FM);
        writeSkill(skills.resolve("gamma"),
                "---\nname: gamma\ndescription: the gamma skill\n---\n# Gamma\nbody\n");
        writeSkill(skills.resolve("beta"), "# Beta\nbody\n"); // no front-matter
        writeSkill(skills.resolve(".pending").resolve("draft"), "# Draft\nstaged\n");

        // Act — native core repo vs pig's workspace source over the same directory.
        List<String> nativeNames = new FileSystemSkillRepository(skills).getAllSkillNames();
        List<String> pigNames = new WorkspaceSkillSource(skills).discover().stream()
                .map(Skill::name).sorted().toList();

        // Assert — the <name>/SKILL.md layout is structurally compatible: the native repo reads the
        // front-matter skills, and NEITHER source surfaces the dot-prefixed staging dir.
        assertThat(nativeNames).as("native FileSystemSkillRepository reads the same <name>/SKILL.md layout")
                .contains("alpha", "gamma");
        assertThat(nativeNames).as("native repo does not surface the .pending staging dir/draft")
                .doesNotContain(".pending", "draft");
        assertThat(pigNames).as("pig's lenient source surfaces every SKILL.md dir")
                .containsExactly("alpha", "beta", "gamma");
        assertThat(pigNames).doesNotContain(".pending", "draft");

        // DOCUMENTED DIVERGENCE (spike A / design D6): the native repo is STRICTER — it requires a
        // parseable front-matter (name/description), so the bare "beta" SKILL.md is skipped, whereas
        // pig's WorkspaceSkillSource is lenient (derives name from the dir). This is safe for S1's
        // same-root adoption: the native source sits at LOWEST priority behind pig's WorkspaceSkillSource
        // (D4), which already surfaces bare skills — so the deduped union is unchanged. (A future
        // different-root native repo would simply require authors to add front-matter.)
        assertThat(nativeNames).as("native repo is stricter: a front-matter-less SKILL.md is skipped")
                .doesNotContain("beta");
    }

    private static void writeSkill(Path dir, String content) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"), content);
    }
}
