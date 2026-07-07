package io.pigagent.tool.shell;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.pigagent.tool.contract.ToolErrors;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public final class ShellTools {

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

    @Tool(description = "Execute a shell command and return its output")
    public String executeCommand(@ToolParam(name = "command", description = "Shell command to execute") String command) {
        try {
            ProcessBuilder pb = new ProcessBuilder(buildInvocation(isWindows(), command));
            pb.redirectErrorStream(true);
            Process process = pb.start();
            boolean finished = process.waitFor(30, TimeUnit.SECONDS);
            String output = new String(process.getInputStream().readAllBytes());
            if (!finished) { process.destroyForcibly(); return "Command timed out.\n" + output; }
            int exitCode = process.exitValue();
            return exitCode == 0 ? output : "Exit code: " + exitCode + "\n" + output;
        } catch (IOException | InterruptedException e) { return ToolErrors.message(e.getMessage()); }
    }
}
