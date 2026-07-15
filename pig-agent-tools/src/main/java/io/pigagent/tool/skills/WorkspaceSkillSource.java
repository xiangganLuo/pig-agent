package io.pigagent.tool.skills;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Discovers user-provided skills from a workspace directory (default {@code workspace/skills/}): each
 * immediate sub-directory that contains a {@code SKILL.md} is one skill, named by the sub-directory.
 * Mirrors {@code io.pigagent.plugin.DirectoryPluginSource}.
 *
 * <p>Fault-tolerant by design: a {@code null} / missing / non-directory path, an empty directory, or
 * an {@link IOException} while listing all return an empty list without throwing. A sub-directory
 * without {@code SKILL.md} is skipped — a skill is, by definition, a {@code SKILL.md}, so listing an
 * empty directory that {@code loadSkill} could not open would only mislead the model.
 */
public final class WorkspaceSkillSource implements SkillSource {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceSkillSource.class);
    private static final String SKILL_FILE = "SKILL.md";

    private final Path skillsDir;

    public WorkspaceSkillSource(Path skillsDir) {
        this.skillsDir = skillsDir;
    }

    @Override
    public String name() {
        return "workspace:" + skillsDir;
    }

    @Override
    public List<Skill> discover() {
        if (skillsDir == null || !Files.isDirectory(skillsDir)) {
            return List.of();
        }
        List<Skill> skills = new ArrayList<>();
        try (Stream<Path> entries = Files.list(skillsDir)) {
            entries.filter(Files::isDirectory)
                    .filter(dir -> Files.exists(dir.resolve(SKILL_FILE)))
                    .forEach(dir -> skills.add(
                            new FileSkill(dir.getFileName().toString(), dir.resolve(SKILL_FILE))));
        } catch (IOException e) {
            log.warn("Failed to scan skills directory {}: {}", skillsDir, e.toString());
            return List.of();
        }
        return skills;
    }
}
