package io.pigagent.tool.skills.authoring;

import io.pigagent.tool.skills.SkillLimits;
import io.pigagent.tool.skills.SkillSecurity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The write half of the autonomous-skills path: a Repository-flavored home for <b>staged</b> skill
 * drafts under {@code workspace/skills/<stagingDir>/<name>/SKILL.md} (default {@code stagingDir} =
 * {@code .pending}). Staged drafts are deliberately one level deeper than {@code WorkspaceSkillSource}
 * scans <em>and</em> live under a reserved dot-prefixed directory, so they are never surfaced as active
 * skills until promoted — reusing the existing read stack rather than a second read system.
 *
 * <p><b>Atomic + owner-restricted.</b> A draft is written via a temp file + {@code ATOMIC_MOVE} and, on
 * POSIX, restricted to {@code 0600} (fulfilling the composite-skill D8 covenant, mirroring
 * {@code JsonModelStore.persist}). {@link #promote(String)} atomically moves the whole draft directory
 * out of staging into {@code workspace/skills/<name>/}, where {@code WorkspaceSkillSource} discovers it
 * on the next scan (no restart). Names are validated with {@link SkillSecurity#isValidSkillName} so a
 * draft can never traverse or hit a reserved name.
 */
public final class SkillStagingArea {

    private static final Logger log = LoggerFactory.getLogger(SkillStagingArea.class);
    private static final String SKILL_FILE = "SKILL.md";
    private static final Set<PosixFilePermission> OWNER_ONLY =
            EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    private final Path skillsRoot;
    private final String stagingDir;
    private final SkillLimits limits;

    public SkillStagingArea(Path skillsRoot, String stagingDir, SkillLimits limits) {
        this.skillsRoot = skillsRoot;
        this.stagingDir = (stagingDir == null || stagingDir.isBlank()) ? ".pending" : stagingDir;
        this.limits = limits == null ? SkillLimits.defaults() : limits;
    }

    /** The staging root ({@code skills/<stagingDir>/}). */
    public Path stagingRoot() {
        return skillsRoot.resolve(stagingDir);
    }

    /**
     * Stage a draft: validate the name, then write {@code SKILL.md} atomically (temp + {@code
     * ATOMIC_MOVE}) and restrict it to the owner. Overwrites any existing staged draft of the same name.
     *
     * @throws IOException on an invalid name or a write failure
     */
    public void stage(SkillDraft draft) throws IOException {
        if (draft == null || !SkillSecurity.isValidSkillName(draft.name())) {
            throw new IOException("invalid skill name");
        }
        Path dir = stagingRoot().resolve(draft.name());
        Files.createDirectories(dir);
        Path skillMd = dir.resolve(SKILL_FILE);
        Path tmp = dir.resolve(SKILL_FILE + ".tmp");
        Files.writeString(tmp, draft.toSkillMd(), StandardCharsets.UTF_8);
        move(tmp, skillMd);
        restrictToOwner(skillMd);
    }

    /** Read a staged draft's {@code SKILL.md}, or empty when the name is invalid / not staged. */
    public Optional<String> read(String name) {
        if (!SkillSecurity.isValidSkillName(name)) {
            return Optional.empty();
        }
        Path skillMd = stagingRoot().resolve(name).resolve(SKILL_FILE);
        if (!Files.isRegularFile(skillMd)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readString(skillMd, StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.warn("Failed to read staged skill '{}': {}", name, e.toString());
            return Optional.empty();
        }
    }

    /** Byte size of a staged draft's {@code SKILL.md} (0 when missing/unreadable). */
    public long size(String name) {
        if (!SkillSecurity.isValidSkillName(name)) {
            return 0;
        }
        Path skillMd = stagingRoot().resolve(name).resolve(SKILL_FILE);
        try {
            return Files.isRegularFile(skillMd) ? Files.size(skillMd) : 0;
        } catch (IOException e) {
            return 0;
        }
    }

    /** True iff a draft with this name is currently staged. */
    public boolean exists(String name) {
        return SkillSecurity.isValidSkillName(name)
                && Files.isRegularFile(stagingRoot().resolve(name).resolve(SKILL_FILE));
    }

    /** Names of staged drafts (each a sub-directory of staging holding a {@code SKILL.md}), sorted. */
    public List<String> list() {
        Path root = stagingRoot();
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        try (Stream<Path> entries = Files.list(root)) {
            entries.filter(Files::isDirectory)
                    .filter(d -> Files.isRegularFile(d.resolve(SKILL_FILE)))
                    .map(d -> String.valueOf(d.getFileName()))
                    .filter(SkillSecurity::isValidSkillName)
                    .sorted(Comparator.naturalOrder())
                    .forEach(names::add);
        } catch (IOException e) {
            log.warn("Failed to list staged skills: {}", e.toString());
            return List.of();
        }
        return names;
    }

    /** Discard a staged draft (delete its directory). Returns true iff something was removed. */
    public boolean discard(String name) {
        if (!SkillSecurity.isValidSkillName(name)) {
            return false;
        }
        Path dir = stagingRoot().resolve(name);
        if (!Files.isDirectory(dir)) {
            return false;
        }
        try {
            deleteRecursively(dir);
            return true;
        } catch (IOException e) {
            log.warn("Failed to discard staged skill '{}': {}", name, e.toString());
            return false;
        }
    }

    /**
     * Atomically promote a staged draft to {@code workspace/skills/<name>/}, where
     * {@code WorkspaceSkillSource} discovers it on the next scan. Fails if the name is invalid, not
     * staged, or the target already exists (the gate's dedup guards the latter first).
     *
     * @throws IOException on any failure (invalid/missing draft, target exists, move failure)
     */
    public void promote(String name) throws IOException {
        if (!SkillSecurity.isValidSkillName(name)) {
            throw new IOException("invalid skill name");
        }
        Path src = stagingRoot().resolve(name);
        if (!Files.isDirectory(src) || !Files.isRegularFile(src.resolve(SKILL_FILE))) {
            throw new IOException("no staged skill: " + name);
        }
        Path target = skillsRoot.resolve(name);
        if (Files.exists(target)) {
            throw new IOException("target already exists: " + name);
        }
        // Confine both paths to the skills root (defence in depth against a symlinked staging dir).
        if (!SkillSecurity.isWithin(skillsRoot, src) || !SkillSecurity.isWithin(skillsRoot, target)) {
            throw new IOException("path escapes skills root");
        }
        Files.createDirectories(skillsRoot);
        move(src, target);
    }

    /** Atomic move with a copy+delete fallback when the filesystem can't move atomically/across stores. */
    private static void move(Path src, Path target) throws IOException {
        try {
            Files.move(src, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(src, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Restrict a file to the owner ({@code 0600}) on POSIX; ignored on non-POSIX filesystems. */
    private static void restrictToOwner(Path file) {
        try {
            Files.setPosixFilePermissions(file, OWNER_ONLY);
        } catch (UnsupportedOperationException | IOException ignored) {
            // Non-POSIX (e.g. Windows) — rely on directory perms, same as JsonModelStore.
        }
    }

    private static void deleteRecursively(Path dir) throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            List<Path> paths = walk.sorted(Comparator.reverseOrder()).toList();
            for (Path p : paths) {
                Files.deleteIfExists(p);
            }
        }
    }
}
