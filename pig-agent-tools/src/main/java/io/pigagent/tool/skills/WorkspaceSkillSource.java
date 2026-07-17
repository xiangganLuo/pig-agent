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
 * <p><b>Hardened against untrusted input.</b> A skill directory is resolved to its real path and MUST
 * be a direct child of the skills root, its {@code SKILL.md} MUST stay within that directory, and its
 * {@code SKILL.md} MUST be within the {@link SkillLimits} size cap — so a {@code ../} traversal, a
 * symlink escaping the root, or a pathologically large skill is rejected. Fault-tolerant by design: a
 * {@code null} / missing / non-directory path, an empty directory, an {@link IOException} while
 * listing, or a single malformed / oversized / escaping skill are all skipped (the bad one with a
 * {@code warn}) without throwing — a broken skill never crashes {@code listSkills}. A sub-directory
 * without {@code SKILL.md} is silently skipped (it is not a skill).
 */
public final class WorkspaceSkillSource implements SkillSource {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceSkillSource.class);
    private static final String SKILL_FILE = "SKILL.md";

    private final Path skillsDir;
    private final SkillLimits limits;

    public WorkspaceSkillSource(Path skillsDir) {
        this(skillsDir, SkillLimits.defaults());
    }

    public WorkspaceSkillSource(Path skillsDir, SkillLimits limits) {
        this.skillsDir = skillsDir;
        this.limits = limits == null ? SkillLimits.defaults() : limits;
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
        Path root = SkillSecurity.realOrNormalized(skillsDir);
        List<Skill> skills = new ArrayList<>();
        try (Stream<Path> entries = Files.list(skillsDir)) {
            entries.filter(Files::isDirectory).forEach(dir -> tryAdd(skills, root, dir));
        } catch (IOException e) {
            log.warn("Failed to scan skills directory {}: {}", skillsDir, e.toString());
            return List.of();
        }
        return skills;
    }

    /** Validate one candidate directory and add it as a skill, skipping (with a warn) on any breach. */
    private void tryAdd(List<Skill> skills, Path root, Path dir) {
        String dirName = String.valueOf(dir.getFileName());
        // Reserved dot-prefixed directories (e.g. the autonomous-skills staging ".pending" and any
        // ".archive") are NEVER surfaced as skills — silently skipped so a staged draft can't leak into
        // listSkills. (Staged drafts also live one level deeper, so they're invisible either way.)
        if (dirName.startsWith(".")) {
            return;
        }
        try {
            if (!SkillSecurity.isDirectChild(root, dir)) {
                log.warn("Skipping skill '{}': directory escapes the skills root (traversal/symlink)", dirName);
                return;
            }
            Path realDir = SkillSecurity.realOrNormalized(dir);
            Path skillMd = realDir.resolve(SKILL_FILE);
            if (!Files.isRegularFile(skillMd)) {
                return; // not a skill (no SKILL.md) — silent, mirrors original behaviour
            }
            if (!SkillSecurity.isWithin(realDir, skillMd)) {
                log.warn("Skipping skill '{}': SKILL.md escapes its directory (symlink)", dirName);
                return;
            }
            long size = Files.size(skillMd);
            if (size > limits.maxSkillBytes()) {
                log.warn("Skipping skill '{}': SKILL.md too large ({} bytes > {})",
                        dirName, size, limits.maxSkillBytes());
                return;
            }
            skills.add(new FileSkill(dirName, realDir, limits));
        } catch (IOException | RuntimeException e) {
            log.warn("Skipping malformed skill '{}': {}", dirName, e.toString());
        }
    }
}
