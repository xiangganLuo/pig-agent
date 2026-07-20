package io.pigagent.tool.filesystem;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.pigagent.tool.contract.ToolErrors;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/**
 * File read/write/edit/list tools. {@code readFile}/{@code writeFile}/{@code editFile} enforce a
 * credential-file blacklist (via {@link CredentialFileGuard}) so the agent can never read or
 * overwrite the workspace's stored secrets ({@code models.json} / {@code mcp.json} and their
 * {@code .bak} siblings). The blacklist protects credential files only — arbitrary project files
 * stay accessible (this is a coding agent whose working directory is an arbitrary user project, not
 * a workspace sandbox).
 *
 * <p>Matching is done on the normalized/resolved path, so {@code ../} traversal and symlinks that
 * point at a credential file are caught (see {@link CredentialFileGuard}).
 *
 * <p>{@code editFile} does a targeted in-place literal string replacement on an <em>existing</em>
 * file (distinct from {@code writeFile}, which creates or overwrites the whole file).
 */
public final class FileSystemTools {

    private final CredentialFileGuard guard;

    /** No blacklist — every path is accessible. Kept for backward compatibility / direct use. */
    public FileSystemTools() {
        this(Set.of());
    }

    /**
     * @param credentialFiles paths the agent must never read/write/edit (workspace credential
     *                        files); may be empty to disable the blacklist
     */
    public FileSystemTools(Set<Path> credentialFiles) {
        this.guard = new CredentialFileGuard(credentialFiles);
    }

    @Tool(description = "Read the contents of a file", readOnly = true)
    public String readFile(@ToolParam(name = "path", description = "Absolute file path") String path) {
        if (guard.isDenied(path)) {
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
        if (guard.isDenied(path)) {
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

    @Tool(description = "Edit an existing file by replacing an exact literal string. Unlike writeFile "
            + "(which creates or overwrites the whole file), editFile makes a targeted in-place "
            + "replacement: the file MUST already exist and, by default, oldString MUST match exactly "
            + "once (pass replace_all=true to replace every occurrence). Returns an error without "
            + "modifying the file if the target is missing, oldString is not found, or oldString is "
            + "ambiguous.")
    public String editFile(
            @ToolParam(name = "path", description = "Absolute path of the existing file to edit") String path,
            @ToolParam(name = "old_string", description = "The exact literal text to replace") String oldString,
            @ToolParam(name = "new_string", description = "The replacement text") String newString,
            @ToolParam(name = "replace_all", description = "true to replace all occurrences; default "
                    + "false requires oldString to appear exactly once") String replaceAll) {
        if (guard.isDenied(path)) {
            return ToolErrors.message("access denied: credential file");
        }
        if (oldString == null || oldString.isEmpty()) {
            return ToolErrors.message("oldString must not be empty");
        }
        String replacement = newString == null ? "" : newString;
        if (oldString.equals(replacement)) {
            return ToolErrors.message("no-op edit: oldString equals newString");
        }
        Path file = Path.of(path);
        if (!Files.isRegularFile(file)) {
            return ToolErrors.message("file not found: " + path);
        }
        try {
            String content = Files.readString(file);
            int count = countOccurrences(content, oldString);
            if (count == 0) {
                return ToolErrors.message("oldString not found");
            }
            boolean all = parseBoolean(replaceAll);
            if (count > 1 && !all) {
                return ToolErrors.message("oldString is ambiguous (" + count + " matches); "
                        + "pass replace_all=true or use a more specific string");
            }
            String updated = all
                    ? content.replace(oldString, replacement)
                    : replaceFirst(content, oldString, replacement);
            Files.writeString(file, updated);
            int replaced = all ? count : 1;
            return "Edited %s (%d replacement%s)".formatted(path, replaced, replaced == 1 ? "" : "s");
        } catch (IOException e) {
            return ToolErrors.message(e.getMessage());
        }
    }

    @Tool(description = "List files in a directory", readOnly = true)
    public String listDirectory(@ToolParam(name = "path", description = "Directory path") String path) {
        try (var stream = Files.list(Path.of(path))) {
            return stream.map(p -> p.getFileName().toString() + (Files.isDirectory(p) ? "/" : ""))
                    .reduce((a, b) -> a + "\n" + b).orElse("Empty directory");
        } catch (IOException e) {
            return ToolErrors.message(e.getMessage());
        }
    }

    /** Count non-overlapping literal occurrences of {@code needle} in {@code haystack}. */
    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    /** Replace the first literal occurrence of {@code oldString} (guaranteed present by the caller). */
    private static String replaceFirst(String content, String oldString, String newString) {
        int idx = content.indexOf(oldString);
        return content.substring(0, idx) + newString + content.substring(idx + oldString.length());
    }

    private static boolean parseBoolean(String s) {
        if (s == null) {
            return false;
        }
        String t = s.trim();
        return t.equalsIgnoreCase("true") || t.equals("1") || t.equalsIgnoreCase("yes");
    }
}
