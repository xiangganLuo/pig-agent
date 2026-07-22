package io.pigagent.tool.os;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.pigagent.tool.contract.CredentialSanitizer;
import io.pigagent.tool.contract.ToolErrors;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Host-independent, pure-Java environment inspection: read an environment variable ({@code
 * getEnvironment}) and locate an executable on {@code PATH} ({@code whichCommand}). Pure {@code
 * System.getenv} + {@code java.io.File} — no {@code printenv}/{@code $env:}/{@code which}/{@code where}.
 *
 * <p>Read-only and <b>secret-safe</b>: a variable whose name looks like a credential (mirroring the
 * command sandbox's env-scrub list — {@code *_KEY}/{@code *TOKEN*}/{@code *SECRET*}/{@code *PASSWORD*}/
 * …) is never echoed (its value is masked), and every returned value is additionally run through
 * {@link CredentialSanitizer}. Listing with no name returns variable <b>names only</b>, never a bulk
 * dump of values. Backs the {@code env-and-path} built-in skill.
 */
public final class EnvironmentTools {

    private final Map<String, String> env;

    /** Uses the real process environment. */
    public EnvironmentTools() {
        this(System.getenv());
    }

    /** Test seam: an explicit environment map (e.g. a controlled PATH). */
    public EnvironmentTools(Map<String, String> env) {
        this.env = env == null ? Map.of() : env;
    }

    @Tool(description = "Read an environment variable's value, or list all variable NAMES when no "
            + "name is given. Secret-looking variables (keys/tokens/passwords) are masked and never "
            + "echoed. Pure Java, cross-platform.", readOnly = true)
    public String getEnvironment(
            @ToolParam(name = "name", description = "Variable name to read (blank = list all names, "
                    + "values omitted)") String name) {
        if (name == null || name.isBlank()) {
            return listNames();
        }
        String key = name.trim();
        String value = lookup(key);
        if (value == null) {
            return key + " is not set";
        }
        if (isSecretName(key)) {
            return key + "=*** (redacted: looks like a secret)";
        }
        return key + "=" + CredentialSanitizer.sanitize(value);
    }

    @Tool(description = "Locate an executable on PATH and return its full path(s), like which/where "
            + "(honors PATHEXT on Windows). Pure Java, cross-platform.", readOnly = true)
    public String whichCommand(
            @ToolParam(name = "name", description = "Executable name to locate on PATH, e.g. git")
            String name) {
        if (name == null || name.isBlank()) {
            return ToolErrors.message("empty command name");
        }
        String cmd = name.trim();
        String path = lookup("PATH");
        if (path == null || path.isBlank()) {
            return ToolErrors.message("PATH is not set");
        }
        List<String> extensions = pathExtensions();
        List<String> found = new ArrayList<>();
        for (String dir : path.split(File.pathSeparator)) {
            if (dir.isBlank()) {
                continue;
            }
            for (String ext : extensions) {
                File candidate = new File(dir.trim(), cmd + ext);
                if (candidate.isFile()) {
                    String abs = candidate.getAbsolutePath();
                    if (!found.contains(abs)) {
                        found.add(abs);
                    }
                }
            }
        }
        if (found.isEmpty()) {
            return cmd + " not found on PATH";
        }
        return String.join("\n", found);
    }

    // --- helpers ---

    private String listNames() {
        if (env.isEmpty()) {
            return "No environment variables visible.";
        }
        TreeMap<String, String> sorted = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        sorted.putAll(env);
        StringBuilder sb = new StringBuilder();
        sb.append(sorted.size()).append(" variable(s) (names only; request a name for its value):\n");
        sb.append(String.join("\n", sorted.keySet()));
        return sb.toString();
    }

    /** Case-insensitive lookup (Windows exposes {@code Path}, POSIX {@code PATH}). */
    private String lookup(String key) {
        String direct = env.get(key);
        if (direct != null) {
            return direct;
        }
        for (Map.Entry<String, String> e : env.entrySet()) {
            if (e.getKey().equalsIgnoreCase(key)) {
                return e.getValue();
            }
        }
        return null;
    }

    /** Executable extensions to try: PATHEXT on Windows, else just the bare name. */
    private List<String> pathExtensions() {
        String pathExt = lookup("PATHEXT");
        if (pathExt == null || pathExt.isBlank()) {
            return List.of("");
        }
        List<String> out = new ArrayList<>();
        out.add(""); // allow an already-suffixed or extension-less name
        for (String ext : pathExt.split(File.pathSeparator)) {
            if (!ext.isBlank()) {
                out.add(ext.trim());
            }
        }
        return out;
    }

    /**
     * A variable name that looks like it carries a secret (case-insensitive). Mirrors the command
     * sandbox's env-scrub list ({@code CommandGuard.isSecretKey}) so both tools agree on what a
     * "secret" name is.
     */
    static boolean isSecretName(String name) {
        String upper = name.toUpperCase(Locale.ROOT);
        return upper.contains("TOKEN")
                || upper.contains("SECRET")
                || upper.contains("PASSWORD")
                || upper.contains("PASSWD")
                || upper.contains("CREDENTIAL")
                || upper.contains("APIKEY")
                || upper.contains("_KEY")
                || upper.endsWith("KEY");
    }
}
