package io.pigagent.tool.filesystem;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/**
 * Immutable credential-file blacklist guard, extracted from {@link FileSystemTools} so the file
 * read/write/edit tools and the file-search tools ({@code FileSearchTools}) share one path-matching
 * implementation (DRY). The blacklist protects the workspace's stored secrets
 * ({@code models.json} / {@code mcp.json} and their {@code .bak} siblings) only — arbitrary project
 * files stay accessible (this is a coding agent, not a workspace sandbox).
 *
 * <p>Each denied file is remembered by both its normalized absolute form
 * ({@code toAbsolutePath().normalize()}) and, when it exists, its {@code toRealPath()}, so a request
 * that arrives via {@code ../} traversal or a symlink pointing at a credential file is caught
 * regardless of the path form.
 */
public final class CredentialFileGuard {

    private final Set<Path> deniedPaths;

    /**
     * @param credentialFiles paths that must never be read/written/searched (workspace credential
     *                        files); may be empty to disable the blacklist
     */
    public CredentialFileGuard(Set<Path> credentialFiles) {
        Set<Path> denied = new HashSet<>();
        for (Path p : credentialFiles) {
            Path abs = p.toAbsolutePath();
            denied.add(abs.normalize());
            Path real = realPathOrNull(abs);
            if (real != null) {
                denied.add(real);
            }
        }
        this.deniedPaths = Set.copyOf(denied);
    }

    /** A guard with no blacklist — nothing is denied (backward-compatible open mode). */
    public static CredentialFileGuard none() {
        return new CredentialFileGuard(Set.of());
    }

    /** True if the requested path string resolves to a blacklisted credential file. */
    public boolean isDenied(String path) {
        if (deniedPaths.isEmpty()) {
            return false;
        }
        Path abs = Path.of(path).toAbsolutePath();
        return matches(abs);
    }

    /** True if the given filesystem path resolves to a blacklisted credential file. */
    public boolean isDenied(Path path) {
        if (deniedPaths.isEmpty() || path == null) {
            return false;
        }
        return matches(path.toAbsolutePath());
    }

    /** Whether the blacklist is empty (no credential files are protected). */
    public boolean isEmpty() {
        return deniedPaths.isEmpty();
    }

    private boolean matches(Path abs) {
        if (deniedPaths.contains(abs.normalize())) {
            return true;
        }
        Path real = realPathOrNull(abs);
        return real != null && deniedPaths.contains(real);
    }

    private static Path realPathOrNull(Path p) {
        try {
            return p.toRealPath();
        } catch (IOException e) {
            return null; // does not exist / not resolvable — fall back to the normalized form
        }
    }
}
