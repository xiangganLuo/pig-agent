package io.pigagent.tool.filesystem;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.pigagent.tool.contract.ToolErrors;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;

/**
 * Host-independent, pure-Java file search tools: content/regex search ({@code searchFiles}) and
 * glob filename lookup ({@code findFiles}). Implemented entirely with {@code java.nio} +
 * {@code java.util.regex} — they NEVER shell out to {@code grep}/{@code find}, so they work on hosts
 * without POSIX search commands (Windows / PowerShell), and a search never re-pays the command
 * sandbox + permission cost.
 *
 * <p>Both reuse the {@link CredentialFileGuard} blacklist so results never leak a workspace
 * credential file, and both bound their output (max matches / files / line length / file size) so a
 * search can't bloat the model context or exhaust memory.
 */
public final class FileSearchTools {

    /** Total match lines returned by {@code searchFiles} before truncation. */
    static final int MAX_MATCHES = 200;
    /** Match lines returned per file by {@code searchFiles}. */
    static final int MAX_MATCHES_PER_FILE = 20;
    /** A matched line longer than this is truncated in the output. */
    static final int MAX_LINE_LEN = 500;
    /** Files larger than this (bytes) are skipped by {@code searchFiles}. */
    static final long MAX_FILE_SIZE = 5_000_000L;
    /** Paths returned by {@code findFiles} before truncation. */
    static final int MAX_RESULTS = 500;
    /** Bytes sniffed at the head of a file to detect binary content (a NUL byte). */
    private static final int BINARY_SNIFF_BYTES = 8192;

    private final CredentialFileGuard guard;

    /** No blacklist — every file is searchable. Kept for backward compatibility / direct use. */
    public FileSearchTools() {
        this(java.util.Set.of());
    }

    /**
     * @param credentialFiles credential files that must never appear in results (mirrors
     *                        {@code FileSystemTools}); may be empty to disable the blacklist
     */
    public FileSearchTools(java.util.Set<Path> credentialFiles) {
        this.guard = new CredentialFileGuard(credentialFiles);
    }

    @Tool(description = "Search file contents across a directory tree for a regular-expression "
            + "pattern (pure Java — works even where grep/find are unavailable, e.g. Windows). "
            + "Returns matching files with line numbers; output is bounded. Optionally restrict to "
            + "files whose name matches a glob (file_pattern) and/or search case-insensitively.",
            readOnly = true)
    public String searchFiles(
            @ToolParam(name = "pattern", description = "Regular expression to search for in file contents")
            String pattern,
            @ToolParam(name = "path", description = "Absolute directory path to search under") String path,
            @ToolParam(name = "file_pattern", description = "Optional glob restricting which files are "
                    + "searched by name, e.g. *.java (blank = all files)") String filePattern,
            @ToolParam(name = "ignore_case", description = "true for case-insensitive search; default false")
            String ignoreCase) {
        if (pattern == null || pattern.isEmpty()) {
            return ToolErrors.message("empty search pattern");
        }
        if (path == null || path.isBlank()) {
            return ToolErrors.message("empty search path");
        }
        Path root = Path.of(path);
        if (!Files.isDirectory(root)) {
            return ToolErrors.message("not a directory: " + path);
        }
        Pattern regex;
        try {
            int flags = parseBoolean(ignoreCase) ? Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE : 0;
            regex = Pattern.compile(pattern, flags);
        } catch (PatternSyntaxException e) {
            return ToolErrors.message("invalid regex: " + e.getMessage());
        }
        PathMatcher nameMatcher;
        try {
            nameMatcher = globOrNull(filePattern);
        } catch (RuntimeException e) {
            return ToolErrors.message("invalid file_pattern glob: " + e.getMessage());
        }
        try {
            return runSearch(root, regex, nameMatcher);
        } catch (IOException e) {
            return ToolErrors.message(e.getMessage());
        }
    }

    @Tool(description = "Find files by name across a directory tree using a glob pattern (pure Java; "
            + "supports *, **, ?, [...], {a,b}). Returns matching file paths (relative to the search "
            + "root), bounded. Use ** to cross directories, e.g. **/*.java or src/**/Test*.java.",
            readOnly = true)
    public String findFiles(
            @ToolParam(name = "pattern", description = "Glob pattern matched against the path relative "
                    + "to the search root, e.g. **/*.java") String pattern,
            @ToolParam(name = "path", description = "Absolute directory path to search under") String path) {
        if (pattern == null || pattern.isBlank()) {
            return ToolErrors.message("empty glob pattern");
        }
        if (path == null || path.isBlank()) {
            return ToolErrors.message("empty search path");
        }
        Path root = Path.of(path);
        if (!Files.isDirectory(root)) {
            return ToolErrors.message("not a directory: " + path);
        }
        PathMatcher matcher;
        try {
            matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
        } catch (RuntimeException e) {
            return ToolErrors.message("invalid glob: " + e.getMessage());
        }
        try {
            return runFind(root, matcher, pattern);
        } catch (IOException e) {
            return ToolErrors.message(e.getMessage());
        }
    }

    // --- searchFiles internals ---

    private String runSearch(Path root, Pattern regex, PathMatcher nameMatcher) throws IOException {
        StringBuilder out = new StringBuilder();
        int total = 0;
        boolean truncated = false;
        try (Stream<Path> walk = Files.walk(root)) {
            Iterator<Path> it = walk.filter(Files::isRegularFile).iterator();
            while (it.hasNext()) {
                if (total >= MAX_MATCHES) {
                    truncated = true;
                    break;
                }
                Path file = it.next();
                if (nameMatcher != null && !nameMatcher.matches(file.getFileName())) {
                    continue;
                }
                if (guard.isDenied(file) || !isSearchable(file)) {
                    continue;
                }
                total += searchOneFile(root, file, regex, out, MAX_MATCHES - total);
            }
        }
        if (out.length() == 0) {
            return "No matches for /" + regex.pattern() + "/";
        }
        if (truncated) {
            out.append("… (truncated at ").append(MAX_MATCHES).append(" matches)\n");
        }
        return out.toString();
    }

    private static int searchOneFile(Path root, Path file, Pattern regex, StringBuilder out, int budget) {
        int found = 0;
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            String rel = relativize(root, file);
            int lineNo = 0;
            for (String line : lines) {
                lineNo++;
                if (found >= MAX_MATCHES_PER_FILE || found >= budget) {
                    break;
                }
                if (regex.matcher(line).find()) {
                    out.append(rel).append(':').append(lineNo).append(": ").append(trimLine(line)).append('\n');
                    found++;
                }
            }
        } catch (IOException | RuntimeException e) {
            // unreadable / non-UTF-8 file — skip (best-effort, never abort the whole search)
        }
        return found;
    }

    // --- findFiles internals ---

    private String runFind(Path root, PathMatcher matcher, String pattern) throws IOException {
        List<String> results = new ArrayList<>();
        boolean truncated = false;
        try (Stream<Path> walk = Files.walk(root)) {
            Iterator<Path> it = walk.filter(Files::isRegularFile).iterator();
            while (it.hasNext()) {
                if (results.size() >= MAX_RESULTS) {
                    truncated = true;
                    break;
                }
                Path file = it.next();
                if (guard.isDenied(file)) {
                    continue;
                }
                Path rel = root.relativize(file);
                if (matcher.matches(rel)) {
                    results.add(rel.toString().replace('\\', '/'));
                }
            }
        }
        if (results.isEmpty()) {
            return "No files match: " + pattern;
        }
        StringBuilder out = new StringBuilder();
        for (String r : results) {
            out.append(r).append('\n');
        }
        if (truncated) {
            out.append("… (truncated at ").append(MAX_RESULTS).append(" results)\n");
        }
        return out.toString();
    }

    // --- helpers ---

    private static PathMatcher globOrNull(String glob) {
        if (glob == null || glob.isBlank()) {
            return null;
        }
        return FileSystems.getDefault().getPathMatcher("glob:" + glob);
    }

    private static boolean isSearchable(Path file) {
        try {
            return Files.size(file) <= MAX_FILE_SIZE && !looksBinary(file);
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean looksBinary(Path file) throws IOException {
        byte[] buf = new byte[BINARY_SNIFF_BYTES];
        try (InputStream in = Files.newInputStream(file)) {
            int n = in.read(buf);
            for (int i = 0; i < n; i++) {
                if (buf[i] == 0) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String relativize(Path root, Path file) {
        try {
            return root.relativize(file).toString().replace('\\', '/');
        } catch (RuntimeException e) {
            return file.toString().replace('\\', '/');
        }
    }

    private static String trimLine(String line) {
        String s = line.strip();
        return s.length() > MAX_LINE_LEN ? s.substring(0, MAX_LINE_LEN) + "…" : s;
    }

    private static boolean parseBoolean(String s) {
        if (s == null) {
            return false;
        }
        String t = s.trim();
        return t.equalsIgnoreCase("true") || t.equals("1") || t.equalsIgnoreCase("yes");
    }
}
