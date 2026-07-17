package io.pigagent.tool.skills;

import java.io.IOException;
import java.nio.file.Path;
import java.util.regex.Pattern;

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

    /**
     * A safe skill (directory) name: starts with an alphanumeric, then alphanumerics / dot / underscore
     * / dash, up to 64 chars. This bounds a user- or agent-supplied name to a single directory segment
     * with no path separators, so it can never traverse ({@code ../}) or hit a reserved dot-prefixed
     * directory ({@code .pending} / {@code .archive}).
     */
    private static final Pattern VALID_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

    private SkillSecurity() {
    }

    /**
     * True iff {@code name} is a safe skill directory name: non-blank, single segment, no path
     * separators, not {@code .}/{@code ..}, and not a reserved dot-prefixed name. Used to bound
     * agent-/user-supplied skill names before they touch the filesystem (mirrors the input-validation
     * philosophy of the other guards). Pure, never throws.
     */
    public static boolean isValidSkillName(String name) {
        return name != null && VALID_NAME.matcher(name).matches();
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
