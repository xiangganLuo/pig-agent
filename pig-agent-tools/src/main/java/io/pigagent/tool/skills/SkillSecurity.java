package io.pigagent.tool.skills;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Path-egress guard for workspace skills. Pure, side-effect-free (beyond resolving real paths),
 * offline-testable — mirroring {@code SsrfGuard}'s and {@code FileSystemTools}' real-path
 * normalization philosophy: resolve to the canonical real path ({@code toRealPath}, falling back to
 * {@code toAbsolutePath().normalize()} when it does not resolve) so a {@code ../} traversal or a
 * symlink escaping the skill directory is caught rather than trusted.
 *
 * <p>{@code WorkspaceSkillSource} resolves user-dropped directories, so this bounds what a skill can
 * reference to inside its own directory (and thus inside the {@code skills/} root).
 */
public final class SkillSecurity {

    private SkillSecurity() {
    }

    /** Canonical real path, falling back to a normalized absolute path when it cannot be resolved. */
    public static Path realOrNormalized(Path p) {
        if (p == null) {
            return null;
        }
        try {
            return p.toRealPath();
        } catch (IOException | RuntimeException e) {
            return p.toAbsolutePath().normalize();
        }
    }

    /**
     * True iff {@code candidate} resolves to a path inside (or equal to) {@code root}. Both are
     * resolved via {@link #realOrNormalized(Path)} first, so traversal and symlink escapes are caught.
     */
    public static boolean isWithin(Path root, Path candidate) {
        if (root == null || candidate == null) {
            return false;
        }
        Path r = realOrNormalized(root);
        Path c = realOrNormalized(candidate);
        return c.startsWith(r);
    }

    /** True iff {@code candidate} resolves to a <em>direct</em> child of {@code root}. */
    public static boolean isDirectChild(Path root, Path candidate) {
        if (root == null || candidate == null) {
            return false;
        }
        Path r = realOrNormalized(root);
        Path c = realOrNormalized(candidate);
        return r.equals(c.getParent());
    }
}
