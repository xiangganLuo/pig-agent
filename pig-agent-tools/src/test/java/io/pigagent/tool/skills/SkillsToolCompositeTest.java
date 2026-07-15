package io.pigagent.tool.skills;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SkillsTool} composite-skill behaviour: progressive listing (metadata only, never the body),
 * description-enriched listing, and bounded supporting-file surfacing in {@code loadSkill} (inline
 * small text, list binary / over-limit without reading them). The plain-skill contracts (bare-name
 * listing, verbatim body when there are no supporting files) stay backward compatible.
 */
class SkillsToolCompositeTest {

    /** A skill whose body read blows up — proves {@code listSkills} only touches metadata. */
    private static Skill listingSpy(String name, String description) {
        return new Skill() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String content() {
                throw new AssertionError("listSkills must not read the skill body");
            }

            @Override
            public SkillMetadata metadata() {
                return new SkillMetadata(name, description, List.of(), "1.0.0");
            }
        };
    }

    private static SkillResource resource(String path, long size, boolean text, String content) {
        return new SkillResource() {
            @Override
            public String path() {
                return path;
            }

            @Override
            public long size() {
                return size;
            }

            @Override
            public boolean isText() {
                return text;
            }

            @Override
            public String read() {
                if (content == null) {
                    throw new AssertionError("resource " + path + " must not be inlined");
                }
                return content;
            }
        };
    }

    @Test
    void listSkills_readsMetadataOnly_notBody() {
        // Arrange — the body read would throw if listing touched it
        SkillsTool tool = new SkillsTool(new SkillRegistry(List.of(
                () -> List.of(listingSpy("spy", "a cheap description")))));

        // Act / Assert — listing succeeds from metadata alone, showing the description
        assertThat(tool.listSkills()).isEqualTo("- spy — a cheap description");
    }

    @Test
    void listSkills_descriptionlessSkill_isBareName() {
        // Arrange — FixedSkill has no metadata → default (blank description)
        SkillsTool tool = new SkillsTool(new SkillRegistry(List.of(
                () -> List.of(new FixedSkill("plain", "# body")))));

        // Act / Assert — backward-compatible bare-name format
        assertThat(tool.listSkills()).isEqualTo("- plain");
    }

    @Test
    void loadSkill_surfacesSupportingFiles_inlineSmallTextOnly() {
        // Arrange — one small text (inlined), one oversized text (listed), one binary (listed)
        SkillResource small = resource("notes.txt", 5, true, "hello");
        SkillResource huge = resource("big.txt", 40_000, true, null);   // over 32 KiB inline cap
        SkillResource bin = resource("img.png", 2048, false, null);     // binary, never inlined
        Skill skill = new Skill() {
            @Override
            public String name() {
                return "bundle";
            }

            @Override
            public String content() {
                return "# Bundle\nbody";
            }

            @Override
            public List<SkillResource> supportingFiles() {
                return List.of(small, huge, bin);
            }
        };
        SkillsTool tool = new SkillsTool(new SkillRegistry(List.of(() -> List.of(skill))));

        // Act
        String out = tool.loadSkill("bundle");

        // Assert — body first, then a bounded listing; only the small text is inlined
        assertThat(out).startsWith("# Bundle\nbody");
        assertThat(out).contains("Supporting files (3):");
        assertThat(out).contains("- notes.txt (5 B)");
        assertThat(out).contains("hello");
        assertThat(out).contains("- big.txt (39.1 KB, not inlined)");
        assertThat(out).contains("- img.png (2.0 KB, binary)");
    }

    @Test
    void loadSkill_noSupportingFiles_returnsBodyVerbatim() {
        // Arrange — a plain skill (default empty supportingFiles)
        SkillsTool tool = new SkillsTool(new SkillRegistry(List.of(
                () -> List.of(new FixedSkill("plain", "# Plain\nbody")))));

        // Act / Assert — exactly the body, no appended section (backward compatible)
        assertThat(tool.loadSkill("plain")).isEqualTo("# Plain\nbody");
    }
}
