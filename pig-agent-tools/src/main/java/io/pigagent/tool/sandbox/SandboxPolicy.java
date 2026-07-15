package io.pigagent.tool.sandbox;

import java.util.List;

/**
 * Immutable, config-backed policy for the command-execution sandbox (capability {@code exec-sandbox}).
 * It carries the runtime constraints applied to {@code executeCommand}: a captured-output byte cap
 * (OOM guard), a timeout, an <em>additional</em> user denylist (regex, layered on top of
 * {@link CommandGuard}'s built-in catastrophic-command floor — never replacing it), an <em>additional</em>
 * user warnlist (regex, layered on top of {@link CommandGuard}'s built-in medium-risk warn set — never
 * replacing it), an env-scrub toggle, and an optional working directory.
 *
 * <p>Value object: fields are final and every mutator is a {@code withXxx()} copy method (per the
 * repo's immutability convention). The constructor is <strong>fault-tolerant</strong>: an out-of-range
 * cap/timeout ({@code <= 0}) clamps back to the safe default rather than disabling the constraint, so
 * a malformed config never weakens the sandbox — it degrades to conservative defaults.
 *
 * <p>This is the "constrained-run" layer and is orthogonal to {@code tool-permissions} (may-run) and
 * {@code tool-availability} (visibility): permission decides whether a command runs, this decides how
 * constrained it is while it runs.
 */
public final class SandboxPolicy {

    /** Default captured-output cap in bytes (200 KB) — enough for typical commands, bounds OOM. */
    public static final long DEFAULT_MAX_OUTPUT_BYTES = 200_000L;

    /** Default command timeout in seconds (the previously hardcoded value). */
    public static final int DEFAULT_TIMEOUT_SECONDS = 30;

    /** Default env-scrub state: strip credential-bearing environment variables from the child. */
    public static final boolean DEFAULT_SCRUB_ENV = true;

    private final long maxOutputBytes;
    private final int timeoutSeconds;
    private final List<String> extraDenyPatterns;
    private final List<String> extraWarnPatterns;
    private final boolean scrubEnv;
    private final String workingDir;

    /**
     * Canonical constructor.
     *
     * @param maxOutputBytes   captured-output cap; {@code <= 0} clamps to {@link #DEFAULT_MAX_OUTPUT_BYTES}
     * @param timeoutSeconds   command timeout; {@code <= 0} clamps to {@link #DEFAULT_TIMEOUT_SECONDS}
     * @param extraDenyPatterns user-supplied denylist regexes appended to the built-in block floor; {@code null} → empty
     * @param extraWarnPatterns user-supplied warnlist regexes appended to the built-in warn set; {@code null} → empty
     * @param scrubEnv         when true, strip credential-bearing env vars from the child process
     * @param workingDir       optional child working directory; {@code null}/blank → inherit current
     */
    public SandboxPolicy(long maxOutputBytes, int timeoutSeconds, List<String> extraDenyPatterns,
                         List<String> extraWarnPatterns, boolean scrubEnv, String workingDir) {
        this.maxOutputBytes = maxOutputBytes > 0 ? maxOutputBytes : DEFAULT_MAX_OUTPUT_BYTES;
        this.timeoutSeconds = timeoutSeconds > 0 ? timeoutSeconds : DEFAULT_TIMEOUT_SECONDS;
        this.extraDenyPatterns = extraDenyPatterns == null ? List.of() : List.copyOf(extraDenyPatterns);
        this.extraWarnPatterns = extraWarnPatterns == null ? List.of() : List.copyOf(extraWarnPatterns);
        this.scrubEnv = scrubEnv;
        this.workingDir = (workingDir == null || workingDir.isBlank()) ? null : workingDir;
    }

    /**
     * Backward-compatible constructor (no warnlist) — delegates with an empty {@code extraWarnPatterns}
     * so pre-warn callers keep compiling and the built-in warn set alone applies.
     */
    public SandboxPolicy(long maxOutputBytes, int timeoutSeconds, List<String> extraDenyPatterns,
                         boolean scrubEnv, String workingDir) {
        this(maxOutputBytes, timeoutSeconds, extraDenyPatterns, List.of(), scrubEnv, workingDir);
    }

    /** The conservative built-in default policy (used when no config / no policy is injected). */
    public static SandboxPolicy defaults() {
        return new SandboxPolicy(DEFAULT_MAX_OUTPUT_BYTES, DEFAULT_TIMEOUT_SECONDS, List.of(),
                List.of(), DEFAULT_SCRUB_ENV, null);
    }

    public long maxOutputBytes() {
        return maxOutputBytes;
    }

    public int timeoutSeconds() {
        return timeoutSeconds;
    }

    /** Extra user denylist regexes (immutable); the built-in catastrophic floor is applied regardless. */
    public List<String> extraDenyPatterns() {
        return extraDenyPatterns;
    }

    /** Extra user warnlist regexes (immutable); the built-in medium-risk warn set is applied regardless. */
    public List<String> extraWarnPatterns() {
        return extraWarnPatterns;
    }

    public boolean scrubEnv() {
        return scrubEnv;
    }

    /** The child working directory, or {@code null} to inherit the current one. */
    public String workingDir() {
        return workingDir;
    }

    public SandboxPolicy withMaxOutputBytes(long bytes) {
        return new SandboxPolicy(bytes, timeoutSeconds, extraDenyPatterns, extraWarnPatterns, scrubEnv, workingDir);
    }

    public SandboxPolicy withTimeoutSeconds(int seconds) {
        return new SandboxPolicy(maxOutputBytes, seconds, extraDenyPatterns, extraWarnPatterns, scrubEnv, workingDir);
    }

    public SandboxPolicy withExtraDenyPatterns(List<String> patterns) {
        return new SandboxPolicy(maxOutputBytes, timeoutSeconds, patterns, extraWarnPatterns, scrubEnv, workingDir);
    }

    public SandboxPolicy withExtraWarnPatterns(List<String> patterns) {
        return new SandboxPolicy(maxOutputBytes, timeoutSeconds, extraDenyPatterns, patterns, scrubEnv, workingDir);
    }

    public SandboxPolicy withScrubEnv(boolean scrub) {
        return new SandboxPolicy(maxOutputBytes, timeoutSeconds, extraDenyPatterns, extraWarnPatterns, scrub, workingDir);
    }

    public SandboxPolicy withWorkingDir(String dir) {
        return new SandboxPolicy(maxOutputBytes, timeoutSeconds, extraDenyPatterns, extraWarnPatterns, scrubEnv, dir);
    }
}
