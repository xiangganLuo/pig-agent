package io.pigagent.tool.sandbox;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The command-execution sandbox strategy (capability {@code exec-sandbox}): the single, unit-testable
 * home of the denylist / output-cap / env-scrub logic that {@link io.pigagent.tool.shell.ShellTools}
 * composes. Built from a {@link SandboxPolicy}; exposes three pure capabilities plus one
 * {@link ProcessBuilder} adapter:
 *
 * <ul>
 *   <li>{@link #checkDenied(String)} — refuse a catastrophic command before it is ever spawned.</li>
 *   <li>{@link #capOutput(InputStream)} — bounded output read (OOM/pipe-deadlock safe).</li>
 *   <li>{@link #buildEnv(Map)} — strip credential-bearing environment variables.</li>
 *   <li>{@link #applyTo(ProcessBuilder)} — set working dir + scrub env on a builder (assertable
 *       without spawning a process).</li>
 * </ul>
 *
 * <p><strong>Two-pass, most-severe-wins classification.</strong> A flat whole-string denylist has
 * bypass holes (e.g. {@code echo ok && rm -rf /}). {@link #checkDenied(String)} therefore (1) runs
 * cheap <em>input validation</em> (reject empty / oversized / null-byte commands), (2) scans the
 * <em>whole</em> normalized command for <em>structural</em> patterns that span operators (fork bombs,
 * {@code while true … & done} loops, download-pipe-to-shell), and (3) splits the command on shell
 * operators ({@code ; && || | &}, quote-aware) and matches each sub-command independently against the
 * single-command rules. Any match blocks (block outranks allow).
 *
 * <p><strong>Security posture (deliberately best-effort, not a boundary):</strong> the built-in
 * denylist is a <em>conservative floor</em> that blocks obviously catastrophic commands. It is a
 * "don't shoot your foot / resist prompt-injected disasters" filter, <em>not</em> a barrier against a
 * determined adversary (base64/alias/variable tricks defeat any regex denylist — OS-level isolation is
 * a later phase). It matches conservatively so it never blocks normal dev commands
 * (mvn/git/npm/ls/`rm -rf target`/…). The floor is compiled in and <em>cannot be weakened</em> by
 * config; user {@code exec.denylist} regexes only add to it.
 */
public final class CommandGuard {

    private static final Logger log = LoggerFactory.getLogger(CommandGuard.class);

    /** Collapses any whitespace run to a single space so patterns match a normalized command. */
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private static final int FLAGS = Pattern.CASE_INSENSITIVE;

    /** Reject commands longer than this (cheap input validation before regex work). */
    private static final int MAX_COMMAND_LENGTH = 10_000;

    private record DenyRule(Pattern pattern, String reason) {
    }

    /**
     * Structural rules matched against the <em>whole</em> normalized command — these deliberately span
     * shell operators, so they MUST NOT be split into sub-commands.
     */
    private static final List<DenyRule> STRUCTURAL = List.of(
            // Pipe a download straight into a shell (curl … | sh, wget … | bash).
            rule("\\b(?:curl|wget)\\b[^|]*\\|\\s*(?:sudo\\s+)?(?:sh|bash|zsh|dash|ksh|fish)\\b",
                    "pipe-to-shell install"),
            // PowerShell download-and-execute (iex + a download; both directions).
            rule("\\biex\\b.*(?:downloadstring|downloadfile|invoke-webrequest|invoke-restmethod"
                            + "|\\biwr\\b|\\birm\\b|new-object\\s+net\\.webclient|https?://)",
                    "download-and-execute"),
            rule("(?:downloadstring|downloadfile|invoke-webrequest|invoke-restmethod|\\biwr\\b|\\birm\\b)"
                            + "\\b[^\\n]*\\|\\s*iex\\b",
                    "download-and-execute"),
            // Classic fork bomb :(){ :|:& };:
            rule(":\\s*\\(\\s*\\)\\s*\\{\\s*:\\s*\\|\\s*:\\s*&\\s*\\}\\s*;\\s*:", "fork bomb"),
            // Infinite loop spawning background jobs (while true; do … & done) — fork-bomb-like.
            rule("\\bwhile\\s+true\\b.*\\bdo\\b.*&.*\\bdone\\b", "fork bomb"));

    /**
     * Single-command rules matched against <em>each</em> sub-command after splitting on shell
     * operators. Multi-signal rules (rm / Remove-Item / chmod) use zero-width look-aheads to require
     * command-name AND a recursive/dangerous flag AND a root/home target <em>together</em>, so a
     * normal recursive delete of a project dir never matches.
     */
    private static final List<DenyRule> PER_COMMAND = List.of(
            // Recursive delete of root / home (Unix rm): rm + recursive flag + root/home target.
            rule("^(?=.*\\brm\\b)(?=.*\\s-{1,2}\\S*r\\S*)"
                            + "(?=.*\\s(?:/|~|\\$home)(?:[\\s*;/&|]|$)).*$",
                    "recursive delete of root/home"),
            // Recursive delete of drive root / home (Windows Remove-Item + -Recurse/-r + root target).
            rule("^(?=.*\\bremove-item\\b)(?=.*\\s-(?:recurse|r)\\b)"
                            + "(?=.*\\s(?:[a-z]:\\\\?|/|~|\\$home|\\$env:userprofile|\\$env:systemdrive)"
                            + "(?:[\\s*;&|]|$)).*$",
                    "recursive delete of root/home"),
            // Disk format / partition.
            rule("\\bmkfs(?:\\.\\w+)?\\b", "disk format/partition"),
            rule("\\bformat\\s+[a-z]:", "disk format/partition"),
            rule("\\bdiskpart\\b", "disk format/partition"),
            // System shutdown / reboot (each sub-command starts with the verb, optionally after sudo).
            rule("(?:^|\\bsudo\\s+)(?:shutdown|reboot|halt|poweroff)\\b", "system shutdown/reboot"),
            // Overwrite a block device (dd of=/dev/…).
            rule("\\bdd\\b[^;|]*\\bof=/dev/", "overwrite block device"),
            // World-writable chmod on root/home (chmod … 777 … / or ~).
            rule("^(?=.*\\bchmod\\b)(?=.*\\b0*777\\b)"
                            + "(?=.*\\s(?:/|~|\\$home)(?:[\\s*;&|]|$)).*$",
                    "world-writable chmod on root/home"));

    private static DenyRule rule(String regex, String reason) {
        return new DenyRule(Pattern.compile(regex, FLAGS), reason);
    }

    private final SandboxPolicy policy;
    private final List<DenyRule> extraRules;

    public CommandGuard(SandboxPolicy policy) {
        this.policy = policy == null ? SandboxPolicy.defaults() : policy;
        List<DenyRule> compiled = new ArrayList<>();
        for (String extra : this.policy.extraDenyPatterns()) {
            if (extra == null || extra.isBlank()) {
                continue;
            }
            try {
                compiled.add(new DenyRule(Pattern.compile(extra, FLAGS),
                        "matched configured denylist pattern"));
            } catch (PatternSyntaxException e) {
                // Fault-tolerant: a bad user pattern is skipped (never weakens the built-in floor,
                // never crashes the guard). The pattern text is not logged verbatim to keep logs clean.
                log.warn("Skipping invalid sandbox denylist pattern: {}", e.getDescription());
            }
        }
        this.extraRules = List.copyOf(compiled);
    }

    /**
     * Two-pass classification. Returns a category reason if {@code command} is rejected (input
     * validation), matches a structural pattern (whole command), or matches a single-command rule in
     * any sub-command; empty otherwise. The reason names the category only — it never echoes the
     * command.
     */
    public Optional<String> checkDenied(String command) {
        Optional<String> invalid = validateInput(command);
        if (invalid.isPresent()) {
            return invalid;
        }
        String normalized = WHITESPACE.matcher(command).replaceAll(" ").trim();

        // Pass 1: structural patterns + configured extras over the whole command.
        for (DenyRule r : STRUCTURAL) {
            if (r.pattern().matcher(normalized).find()) {
                return Optional.of(r.reason());
            }
        }
        for (DenyRule r : extraRules) {
            if (r.pattern().matcher(normalized).find()) {
                return Optional.of(r.reason());
            }
        }

        // Pass 2: single-command rules against each operator-split sub-command.
        for (String sub : splitSubCommands(normalized)) {
            String piece = sub.trim();
            if (piece.isEmpty()) {
                continue;
            }
            for (DenyRule r : PER_COMMAND) {
                if (r.pattern().matcher(piece).find()) {
                    return Optional.of(r.reason());
                }
            }
        }
        return Optional.empty();
    }

    /** Cheap input validation run before any regex work. */
    private static Optional<String> validateInput(String command) {
        if (command == null || command.isBlank()) {
            return Optional.of("empty command");
        }
        if (command.length() > MAX_COMMAND_LENGTH) {
            return Optional.of("command too long");
        }
        if (command.indexOf('\0') >= 0) {
            return Optional.of("null byte in command");
        }
        return Optional.empty();
    }

    /**
     * Split a normalized command on shell operators ({@code ; && || | &}) while respecting single/double
     * quotes, so {@code echo ok && rm -rf /} yields two sub-commands but {@code echo "a | b"} stays one.
     */
    private static List<String> splitSubCommands(String normalized) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
                current.append(c);
            } else if (c == '\'' || c == '"') {
                quote = c;
                current.append(c);
            } else if (c == ';' || c == '&' || c == '|') {
                parts.add(current.toString());
                current.setLength(0);
                if (i + 1 < normalized.length() && normalized.charAt(i + 1) == c) {
                    i++; // consume the second char of && or ||
                }
            } else {
                current.append(c);
            }
        }
        parts.add(current.toString());
        return parts;
    }

    /**
     * Read {@code in} but never buffer more than the policy's cap: content beyond the cap is drained
     * and discarded (so the child does not block on a full pipe) and a truncation marker is appended.
     * This replaces {@code readAllBytes()} to bound memory at the source.
     */
    public CappedOutput capOutput(InputStream in) throws IOException {
        if (in == null) {
            return new CappedOutput("", false);
        }
        long cap = policy.maxOutputBytes();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        long kept = 0;
        boolean truncated = false;
        int read;
        while ((read = in.read(chunk)) != -1) {
            if (kept < cap) {
                int room = (int) Math.min(read, cap - kept);
                buffer.write(chunk, 0, room);
                kept += room;
                if (room < read) {
                    truncated = true;
                }
            } else if (read > 0) {
                truncated = true; // drain remainder to avoid pipe deadlock, but keep nothing
            }
        }
        String text = buffer.toString(StandardCharsets.UTF_8);
        if (truncated) {
            text = text + "\n\n[output truncated: exceeded " + cap + " bytes]";
        }
        return new CappedOutput(text, truncated);
    }

    /**
     * Build the child environment. When scrubbing is on, credential-bearing variables are removed and
     * everything else (PATH/HOME/LANG/…) is kept, so normal commands keep working while a shell command
     * can't {@code echo $ANTHROPIC_API_KEY}. Always returns a new map; the parent map is not mutated.
     */
    public Map<String, String> buildEnv(Map<String, String> parentEnv) {
        Map<String, String> source = parentEnv == null ? Map.of() : parentEnv;
        Map<String, String> result = new LinkedHashMap<>();
        boolean scrub = policy.scrubEnv();
        for (Map.Entry<String, String> entry : source.entrySet()) {
            String key = entry.getKey();
            if (key == null) {
                continue;
            }
            if (scrub && isSecretKey(key)) {
                continue;
            }
            result.put(key, entry.getValue());
        }
        return result;
    }

    /**
     * Apply the working-dir and env-scrub constraints to {@code pb} (leaving the OS-shell invocation
     * untouched). Extracted so the configuration can be asserted in tests without spawning a process.
     */
    public void applyTo(ProcessBuilder pb) {
        if (pb == null) {
            return;
        }
        String dir = policy.workingDir();
        if (dir != null) {
            pb.directory(new java.io.File(dir));
        }
        if (policy.scrubEnv()) {
            Map<String, String> scrubbed = buildEnv(pb.environment());
            pb.environment().clear();
            pb.environment().putAll(scrubbed);
        }
    }

    /** Names that look like they carry a secret (case-insensitive) — stripped when scrubbing. */
    private static boolean isSecretKey(String name) {
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
