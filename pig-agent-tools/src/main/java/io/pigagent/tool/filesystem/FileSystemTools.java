package io.pigagent.tool.filesystem;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.pigagent.tool.contract.ToolErrors;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/**
 * File read/write/list tools. {@code readFile}/{@code writeFile} enforce a credential-file
 * blacklist so the agent can never read or overwrite the workspace's stored secrets
 * ({@code models.json} / {@code mcp.json} and their {@code .bak} siblings). The blacklist protects
 * credential files only — arbitrary project files stay accessible (this is a coding agent whose
 * working directory is an arbitrary user project, not a workspace sandbox).
 *
 * <p>Matching is done on the normalized/resolved path, so {@code ../} traversal and symlinks that
 * point at a credential file are caught. Each denied file is remembered by both its normalized
 * absolute form and (when it exists) its real path, so a request that arrives in either form is
 * blocked regardless of case/short-name differences.
 */
public final class FileSystemTools {

    private final Set<Path> deniedPaths;

    /** No blacklist — every path is accessible. Kept for backward compatibility / direct use. */
    public FileSystemTools() {
        this(Set.of());
    }

    /**
     * @param credentialFiles paths the agent must never read/write (workspace credential files);
     *                        may be empty to disable the blacklist
     */
    public FileSystemTools(Set<Path> credentialFiles) {
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

    @Tool(description = "Read the contents of a file")
    public String readFile(@ToolParam(name = "path", description = "Absolute file path") String path) {
        if (isDenied(path)) {
            return ToolErrors.message("access denied: credential file");
        }
        try {
            return Files.readString(Path.of(path));
        } catch (IOException e) {
            return ToolErrors.message(e.getMessage());
        }
    }

    @Tool(description = "Write content to a file (creates or overwrites)")
    public String writeFile(
            @ToolParam(name = "path", description = "Absolute file path") String path,
            @ToolParam(name = "content", description = "Content to write") String content) {
        if (isDenied(path)) {
            return ToolErrors.message("access denied: credential file");
        }
        try {
            Files.createDirectories(Path.of(path).getParent());
            Files.writeString(Path.of(path), content);
            return "Written %d bytes to %s".formatted(content.length(), path);
        } catch (IOException e) {
            return ToolErrors.message(e.getMessage());
        }
    }

    @Tool(description = "List files in a directory")
    public String listDirectory(@ToolParam(name = "path", description = "Directory path") String path) {
        try (var stream = Files.list(Path.of(path))) {
            return stream.map(p -> p.getFileName().toString() + (Files.isDirectory(p) ? "/" : ""))
                    .reduce((a, b) -> a + "\n" + b).orElse("Empty directory");
        } catch (IOException e) {
            return ToolErrors.message(e.getMessage());
        }
    }

    /** True if the requested path resolves to a blacklisted credential file. */
    private boolean isDenied(String path) {
        if (deniedPaths.isEmpty()) {
            return false;
        }
        Path abs = Path.of(path).toAbsolutePath();
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
