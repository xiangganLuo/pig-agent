package io.pigagent.tool.os;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.pigagent.tool.contract.CredentialSanitizer;
import io.pigagent.tool.contract.ToolErrors;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Host-independent, pure-Java process introspection via {@link ProcessHandle} — lists running
 * processes with pid / owner / command, optionally filtered by a substring. NEVER shells out to
 * {@code ps}/{@code Get-Process}/{@code tasklist}, so it behaves the same on Windows and POSIX.
 *
 * <p>Read-only and bounded. Command lines are run through {@link CredentialSanitizer} so a process
 * started with a secret on its command line (e.g. {@code --password=...}) is never echoed back to the
 * model. Backs the {@code process-and-ports} built-in skill. Terminating a process is intentionally
 * NOT offered here — use the permission-gated {@code executeCommand} for that.
 */
public final class ProcessTools {

    /** Hard cap on rows returned regardless of the requested limit. */
    static final int MAX_LIMIT = 200;
    /** Default number of rows when the caller gives no limit. */
    static final int DEFAULT_LIMIT = 50;
    /** A command string longer than this is truncated in the output. */
    private static final int MAX_COMMAND_LEN = 200;

    @Tool(description = "List running processes (pid, owner, command), newest first, optionally "
            + "filtered by a case-insensitive substring of the command line. Pure Java via "
            + "ProcessHandle — works on Windows and POSIX; secrets on command lines are redacted.",
            readOnly = true)
    public String listProcesses(
            @ToolParam(name = "filter", description = "Optional case-insensitive substring to match "
                    + "against the command line (blank = all processes)") String filter,
            @ToolParam(name = "limit", description = "Maximum rows to return (default 50, max 200)")
            String limit) {
        int max = parseLimit(limit);
        String needle = filter == null ? "" : filter.trim().toLowerCase(Locale.ROOT);
        try {
            // Newest-first so a just-started process (and the recent activity a user cares about) is
            // near the top and survives the row cap; unknown start times sort last.
            List<ProcessHandle> handles = ProcessHandle.allProcesses()
                    .sorted(Comparator.comparingLong(ProcessTools::startMillis).reversed())
                    .toList();
            List<String> rows = new ArrayList<>();
            for (ProcessHandle handle : handles) {
                if (rows.size() >= max) {
                    break;
                }
                String row = describe(handle);
                if (row == null || (!needle.isEmpty() && !row.toLowerCase(Locale.ROOT).contains(needle))) {
                    continue;
                }
                rows.add(row);
            }
            if (rows.isEmpty()) {
                return needle.isEmpty() ? "No processes visible." : "No processes match: " + filter;
            }
            StringBuilder sb = new StringBuilder();
            sb.append(rows.size()).append(needle.isEmpty() ? " process(es):\n" : " matching process(es):\n");
            for (String r : rows) {
                sb.append(r).append('\n');
            }
            return sb.toString();
        } catch (RuntimeException e) {
            return ToolErrors.message(e.getMessage());
        }
    }

    /** Render one process as {@code PID <pid>  <user>  <command>}; {@code null} if it vanished. */
    private static String describe(ProcessHandle handle) {
        try {
            ProcessHandle.Info info = handle.info();
            String user = info.user().orElse("?");
            String command = info.commandLine().or(info::command).orElse("(unknown)");
            command = CredentialSanitizer.sanitize(command);
            if (command.length() > MAX_COMMAND_LEN) {
                command = command.substring(0, MAX_COMMAND_LEN) + "…";
            }
            return "PID " + handle.pid() + "  " + user + "  " + command;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Process start time as epoch millis for newest-first sorting; unknown → {@link Long#MIN_VALUE}. */
    private static long startMillis(ProcessHandle handle) {
        try {
            return handle.info().startInstant().map(Instant::toEpochMilli).orElse(Long.MIN_VALUE);
        } catch (RuntimeException e) {
            return Long.MIN_VALUE;
        }
    }

    private static int parseLimit(String limit) {
        if (limit == null || limit.isBlank()) {
            return DEFAULT_LIMIT;
        }
        try {
            int n = Integer.parseInt(limit.trim());
            if (n <= 0) {
                return DEFAULT_LIMIT;
            }
            return Math.min(n, MAX_LIMIT);
        } catch (NumberFormatException e) {
            return DEFAULT_LIMIT;
        }
    }
}
