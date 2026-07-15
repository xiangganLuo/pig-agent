package io.pigagent.tool.shell;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.pigagent.tool.contract.CredentialSanitizer;
import io.pigagent.tool.contract.ToolErrors;
import io.pigagent.tool.sandbox.CappedOutput;
import io.pigagent.tool.sandbox.CommandClassification;
import io.pigagent.tool.sandbox.CommandGuard;
import io.pigagent.tool.sandbox.SandboxPolicy;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shell execution tool. {@code executeCommand} runs a permitted command under the command-execution
 * sandbox (capability {@code exec-sandbox}): a {@link CommandGuard} (built from a {@link SandboxPolicy})
 * classifies the command into three tiers — <em>block</em> (refuse a catastrophic command before
 * spawning), <em>warn</em> (run a medium-risk-but-legitimate command, then append a {@code ⚠️ Warning}
 * note to the result so the model sees the risk), or <em>pass</em> (run silently) — and bounds captured
 * output, applies a configurable timeout, a credential-scrubbed environment, and an optional working
 * directory. This is the "constrained-run" layer, orthogonal to and stacked after the permission veto
 * (may-run) — a permitted {@code rm -rf /} is still blocked here; a normal command only gets the runtime
 * constraints. Warn is purely additive: it never changes the block/pass return contract (a successful
 * result stays a normal result with a note appended, never a {@code {"error"}}).
 */
public final class ShellTools {

    private static final Logger log = LoggerFactory.getLogger(ShellTools.class);

    /** Prefix of the note appended to a warn-tier command's result (see {@link #appendWarnNote}). */
    static final String WARN_PREFIX = "⚠️ Warning: ";

    private final SandboxPolicy policy;
    private final CommandGuard guard;

    /** Uses the conservative built-in default policy (used when no config is injected). */
    public ShellTools() {
        this(SandboxPolicy.defaults());
    }

    /** @param policy the sandbox policy; {@code null} falls back to {@link SandboxPolicy#defaults()}. */
    public ShellTools(SandboxPolicy policy) {
        this.policy = policy == null ? SandboxPolicy.defaults() : policy;
        this.guard = new CommandGuard(this.policy);
    }

    /**
     * Pick the OS-native shell to run {@code command}. On Windows this MUST NOT be {@code bash -c}:
     * that launches WSL bash, where {@code C:\} is remapped to {@code /mnt/c/…}, so any path the
     * command touches gets a spurious {@code /mnt/c} prefix. PowerShell keeps native {@code C:\}
     * paths. Extracted as a pure function so both branches are unit-tested without spawning a shell.
     */
    static List<String> buildInvocation(boolean windows, String command) {
        return windows
                ? List.of("powershell.exe", "-NoProfile", "-Command", command)
                : List.of("bash", "-c", command);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /** Credential-redacted, length-bounded command for the block audit log (never echoes a secret). */
    private static String auditSummary(String command) {
        if (command == null) {
            return "";
        }
        String redacted = CredentialSanitizer.sanitize(command);
        return redacted.length() > 200 ? redacted.substring(0, 200) + "…" : redacted;
    }

    @Tool(description = "Execute a shell command and return its output")
    public String executeCommand(@ToolParam(name = "command", description = "Shell command to execute") String command) {
        CommandClassification verdict = guard.classify(command);
        if (verdict.isBlocked()) {
            log.warn("Sandbox blocked command ({}): {}", verdict.reason(), auditSummary(command));
            return ToolErrors.message("blocked: " + verdict.reason());
        }
        if (verdict.isWarn()) {
            log.info("Sandbox flagged command ({}): {}", verdict.reason(), auditSummary(command));
        }
        try {
            ProcessBuilder pb = new ProcessBuilder(buildInvocation(isWindows(), command));
            pb.redirectErrorStream(true);
            guard.applyTo(pb); // scrub env + optional working dir
            Process process = pb.start();
            boolean finished = process.waitFor(policy.timeoutSeconds(), TimeUnit.SECONDS);
            CappedOutput captured = guard.capOutput(process.getInputStream());
            String output = captured.text();
            String base;
            if (!finished) {
                process.destroyForcibly();
                base = "Command timed out.\n" + output;
            } else {
                int exitCode = process.exitValue();
                base = exitCode == 0 ? output : "Exit code: " + exitCode + "\n" + output;
            }
            // Warn is purely additive: the command already ran; append a note without altering the
            // block/pass contract (a success stays a success, only with a trailing risk note).
            return verdict.isWarn() ? appendWarnNote(base, verdict.reason()) : base;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolErrors.message(e.getMessage());
        } catch (IOException e) {
            return ToolErrors.message(e.getMessage());
        }
    }

    /**
     * Append a {@code ⚠️ Warning: <reason>} note to an already-executed command's result, so a warn-tier
     * command surfaces its risk to the model without changing the block/pass contract. Pure + package
     * -private so the formatting is unit-tested offline (no spawn). An empty {@code base} yields the note
     * alone; otherwise the note trails after a blank line.
     */
    static String appendWarnNote(String base, String reason) {
        String note = WARN_PREFIX + reason;
        return (base == null || base.isEmpty()) ? note : base + "\n\n" + note;
    }
}
