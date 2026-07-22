package io.pigagent.skills.builtin;

import io.pigagent.tool.skills.Skill;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SkillCatalog} is the single source of truth for the built-in skill set. These tests guard:
 * the curated skills are declared with unique names, each ships a non-empty {@code SKILL.md}
 * with a title, and the catalog names match the actual classpath resource directories (a drift guard
 * mirroring {@code PluginCatalogTest}).
 */
class SkillCatalogTest {

    private static final Set<String> EXPECTED = Set.of(
            // Engineering methods
            "code-review", "systematic-debugging", "tdd", "refactoring",
            "git-commit", "security-review", "planning",
            // Operating-system operations
            "shell-commands", "file-operations", "process-and-ports", "log-triage",
            "system-health", "networking-diagnostics", "env-and-path", "archive-and-compress",
            // Environment / toolchain setup
            "python-environment", "install-tools",
            // Windows usage scenarios
            "windows-services", "windows-scheduled-tasks", "windows-startup-apps", "windows-firewall",
            "windows-network-config", "windows-wifi", "windows-user-accounts", "windows-event-logs",
            "windows-updates", "windows-disk-management", "windows-registry", "windows-defender",
            "windows-power", "windows-printers", "windows-remote-access", "windows-file-permissions",
            "windows-disk-cleanup", "windows-hosts-file", "windows-datetime", "windows-slow-boot-triage");

    @Test
    void skillNames_areTheCuratedSkills_unique() {
        // Assert
        assertThat(SkillCatalog.SKILL_NAMES).hasSize(EXPECTED.size());
        assertThat(Set.copyOf(SkillCatalog.SKILL_NAMES)).hasSameSizeAs(SkillCatalog.SKILL_NAMES);
        assertThat(Set.copyOf(SkillCatalog.SKILL_NAMES)).isEqualTo(EXPECTED);
    }

    @Test
    void everyBuiltinSkill_hasNonEmptyContentWithTitle() throws IOException {
        // Act / Assert — content loads and looks like a skill guide
        for (Skill skill : SkillCatalog.all()) {
            String content = skill.content();
            assertThat(content).as("content of %s", skill.name()).isNotBlank();
            assertThat(content.stripLeading()).as("%s starts with a markdown title", skill.name())
                    .startsWith("# ");
        }
    }

    @Test
    void catalogNames_matchClasspathResourceDirectories() throws Exception {
        // Arrange — the skill directories actually shipped on the classpath
        Set<String> resourceDirs = classpathSkillDirectories();

        // Assert — no drift: adding a resource dir without a catalog entry (or vice-versa) fails here
        assertThat(resourceDirs).isEqualTo(Set.copyOf(SkillCatalog.SKILL_NAMES));
    }

    /** Skill sub-directories under the classpath {@code skills/} that contain a {@code SKILL.md}. */
    private static Set<String> classpathSkillDirectories() throws Exception {
        URL url = SkillCatalog.class.getClassLoader().getResource("skills");
        assertThat(url).as("skills/ resource directory is on the classpath").isNotNull();
        Path skillsRoot = Paths.get(url.toURI());
        try (Stream<Path> entries = Files.list(skillsRoot)) {
            return entries
                    .filter(Files::isDirectory)
                    .filter(dir -> Files.exists(dir.resolve("SKILL.md")))
                    .map(dir -> dir.getFileName().toString())
                    .collect(Collectors.toSet());
        }
    }

    @Test
    void all_returnsOneSkillPerName() {
        List<Skill> skills = SkillCatalog.all();

        assertThat(skills).extracting(Skill::name)
                .containsExactlyElementsOf(SkillCatalog.SKILL_NAMES);
    }
}
