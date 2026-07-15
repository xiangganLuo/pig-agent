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
 *   <li>{@link #classify(String)} — the three-tier verdict (block &gt; warn &gt; pass) for a command.</li>
 *   <li>{@link #checkDenied(String)} — a block-only view of {@link #classify(String)} (the pre-existing
 *       block/pass contract; a warn-matching command reads as "not denied" here).</li>
 *   <li>{@link #capOutput(InputStream)} — bounded output read (OOM/pipe-deadlock safe).</li>
 *   <li>{@link #buildEnv(Map)} — strip credential-bearing environment variables.</li>
 *   <li>{@link #applyTo(ProcessBuilder)} — set working dir + scrub env on a builder (assertable
 *       without spawning a process).</li>
 * </ul>
 *
 * <p><strong>Two-pass, three-tier, most-severe-wins classification.</strong> A flat whole-string
 * denylist has bypass holes (e.g. {@code echo ok && rm -rf /}). {@link #classify(String)} therefore
 * (1) runs cheap <em>input validation</em> (reject empty / oversized / null-byte commands → block),
 * (2) scans the <em>whole</em> normalized command for <em>structural</em> block patterns that span
 * operators (fork bombs, {@code while true … & done} loops, download-pipe-to-shell), and (3) splits the
 * command on shell operators ({@code ; && || | &}, quote-aware) and matches each sub-command against the
 * single-command rules. The same two passes carry three tiers: <em>block</em> (catastrophic floor —
 * refuse) outranks <em>warn</em> (medium-risk but legitimate — run, then append a ⚠️ note) outranks
 * <em>pass</em> (clean — run silently). Block is scanned first and short-circuits, so a command matching
 * both a block and a warn pattern is blocked. Warn is purely additive — it never changes the block/pass
 * contract, and {@link #checkDenied(String)} keeps reporting block only.
 *
 * <p><strong>Security posture (deliberately best-effort, not a boundary):</strong> the built-in
 * denylist is a <em>conservative floor</em> that blocks obviously catastrophic commands. It is a
 * "don't shoot your foot / resist prompt-injected disasters" filter, <em>not</em> a barrier against a
 * determined adversary (base64/alias/variable tricks defeat any regex denylist — OS-level isolation is
 * a later phase). It matches conservatively so it never blocks normal dev commands
 * (mvn/git/npm/ls/`rm -rf target`/…). The floor is compiled in and <em>cannot be weakened</em> by
 * config; user {@code exec.denylist} regexes only add to it. A parallel built-in <em>warn</em> set flags
 * medium-risk-but-legitimate commands (pip/apt install, sudo/su, non-root chmod 777, {@code PATH=}
 * reassignment, {@code npm install -g}) — same best-effort caveat; {@code exec.warnlist} only adds to it.
 */
public final class CommandGuard {

    private static final Logger log = LoggerFactory.getLogger(CommandGuard.class);

    /** Collapses any whitespace run to a single space so patterns match a normalized command. */
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private static final int FLAGS = Pattern.CASE_INSENSITIVE;

    /** Reject commands longer than this (cheap input validation before regex work). */
    private static final int MAX_COMMAND_LENGTH = 10_000;

    /** A compiled classification rule (pattern + category reason), shared by the block and warn tiers. */
    private record Rule(Pattern pattern, String reason) {
    }

    /**
     * Structural rules matched against the <em>whole</em> normalized command — these deliberately span
     * shell operators, so they MUST NOT be split into sub-commands.
     */
    private static final List<Rule> STRUCTURAL = List.of(
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
    private static final List<Rule> PER_COMMAND = List.of(
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

    /**
     * Medium-risk (warn) rules matched against <em>each</em> sub-command (like {@link #PER_COMMAND}).
     * These commands are legitimate but risky enough to surface: a match runs the command normally but
     * appends a ⚠️ note to the tool result (see {@link #classify(String)}). They are anchored to the
     * sub-command start (optionally after {@code sudo}/{@code python -m }) so they do not fire on the same
     * tokens quoted inside an argument, and stay conservative so normal dev commands never warn. The
     * root/home {@code chmod 777} case is a <em>block</em> and wins by most-severe ordering, so the warn
     * chmod rule only fires on non-root paths. More specific rules (pip/apt/npm) precede the generic
     * {@code sudo/su} rule so a {@code sudo apt install} reports the more informative category.
     */
    private static final List<Rule> WARN = List.of(
            // Package installs (pip / apt) — legitimate, but mutate the machine.
            rule("^(?:sudo\\s+)?(?:python[0-9.]*\\s+-m\\s+)?pip[0-9.]*\\s+install\\b",
                    "package install (pip)"),
            rule("^(?:sudo\\s+)?apt(?:-get)?\\s+install\\b", "package install (apt)"),
            // Global npm install (mutates the global prefix / installs a binary onto PATH).
            rule("^(?:sudo\\s+)?npm\\s+(?:install|i)\\b[^\\n]*(?:\\s-g\\b|--global\\b)",
                    "global npm install"),
            // Privilege escalation.
            rule("^(?:sudo|su)\\b", "privilege escalation (sudo/su)"),
            // World-writable chmod 777 on a non-root path (root/home 777 is a block and wins).
            rule("^(?=.*\\bchmod\\b)(?=.*\\b0*777\\b).*$", "permissive chmod 777"),
            // PATH reassignment (shadows system binaries / injects a lookup dir).
            rule("(?:^(?:export\\s+|set\\s+)?PATH\\s*=|\\$env:PATH\\s*=)", "PATH reassignment"));

    private static Rule rule(String regex, String reason) {
        return new Rule(Pattern.compile(regex, FLAGS), reason);
    }

    private final SandboxPolicy policy;
    private final List<Rule> extraDenyRules;
    private final List<Rule> extraWarnRules;

    public CommandGuard(SandboxPolicy policy) {
        this.policy = policy == null ? SandboxPolicy.defaults() : policy;
        this.extraDenyRules = compileExtra(this.policy.extraDenyPatterns(),
                "matched configured denylist pattern", "denylist");
        this.extraWarnRules = compileExtra(this.policy.extraWarnPatterns(),
                "matched configured warnlist pattern", "warnlist");
    }

    /**
     * Compile user-supplied regexes into rules, appended on top of the built-in tier. Fault-tolerant: a
     * bad pattern is skipped (never weakens the built-in floor/set, never crashes the guard); the pattern
     * text is not logged verbatim to keep logs clean.
     */
    private static List<Rule> compileExtra(List<String> patterns, String reason, String kind) {
        List<Rule> compiled = new ArrayList<>();
        for (String extra : patterns) {
            if (extra == null || extra.isBlank()) {
                continue;
            }
            try {
                compiled.add(new Rule(Pattern.compile(extra, FLAGS), reason));
            } catch (PatternSyntaxException e) {
                log.warn("Skipping invalid sandbox {} pattern: {}", kind, e.getDescription());
            }
        }
        return List.copyOf(compiled);
    }

    /**
     * Three-tier, most-severe-wins classification. Returns {@code BLOCK} (with a category reason) when
     * {@code command} fails input validation or matches a block pattern; else {@code WARN} (with a
     * category reason) when it matches a warn pattern; else {@code PASS}. Both tiers reuse the same
     * two-pass structure (whole-command structural scan + quote-aware sub-command split). Block is
     * evaluated first and short-circuits, so a command matching both a block and a warn pattern is
     * {@code BLOCK}. The reason names the category only — it never echoes the command.
     */
    public CommandClassification classify(String command) {
        Optional<String> invalid = validateInput(command);
        if (invalid.isPresent()) {
            return CommandClassification.block(invalid.get());
        }
        String normalized = WHITESPACE.matcher(command).replaceAll(" ").trim();
        List<String> subs = splitSubCommands(normalized); // split once, reused by both tiers

        Optional<String> blocked = scanBlock(normalized, subs);
        if (blocked.isPresent()) {
            return CommandClassification.block(blocked.get());
        }
        Optional<String> warned = scanWarn(subs);
        if (warned.isPresent()) {
            return CommandClassification.warn(warned.get());
        }
        return CommandClassification.PASS;
    }

    /**
     * The pre-existing block/pass contract, unchanged: a category reason iff {@code command} is
     * {@code BLOCK}, empty otherwise. A warn-matching command reads as "not denied" here (its warning is
     * surfaced additively by the caller via {@link #classify(String)}).
     */
    public Optional<String> checkDenied(String command) {
        CommandClassification verdict = classify(command);
        return verdict.isBlocked() ? Optional.of(verdict.reason()) : Optional.empty();
    }

    /** Block tier: Pass 1 structural + configured deny over the whole command; Pass 2 per sub-command. */
    private Optional<String> scanBlock(String normalized, List<String> subs) {
        for (Rule r : STRUCTURAL) {
            if (r.pattern().matcher(normalized).find()) {
                return Optional.of(r.reason());
            }
        }
        for (Rule r : extraDenyRules) {
            if (r.pattern().matcher(normalized).find()) {
                return Optional.of(r.reason());
            }
        }
        for (String sub : subs) {
            String piece = sub.trim();
            if (piece.isEmpty()) {
                continue;
            }
            for (Rule r : PER_COMMAND) {
                if (r.pattern().matcher(piece).find()) {
                    return Optional.of(r.reason());
                }
            }
        }
        return Optional.empty();
    }

    /** Warn tier: built-in + configured warn rules against each operator-split sub-command. */
    private Optional<String> scanWarn(List<String> subs) {
        for (String sub : subs) {
            String piece = sub.trim();
            if (piece.isEmpty()) {
                continue;
            }
            for (Rule r : WARN) {
                if (r.pattern().matcher(piece).find()) {
                    return Optional.of(r.reason());
                }
            }
            for (Rule r : extraWarnRules) {
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
