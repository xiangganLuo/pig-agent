package io.pigagent.tool.shell;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

public final class ShellTools {
    @Tool(description = "Execute a shell command and return its output")
    public String executeCommand(@ToolParam(name = "command", description = "Shell command to execute") String command) {
        try {
            ProcessBuilder pb = new ProcessBuilder("bash", "-c", command);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            boolean finished = process.waitFor(30, TimeUnit.SECONDS);
            String output = new String(process.getInputStream().readAllBytes());
            if (!finished) { process.destroyForcibly(); return "Command timed out.\n" + output; }
            int exitCode = process.exitValue();
            return exitCode == 0 ? output : "Exit code: " + exitCode + "\n" + output;
        } catch (IOException | InterruptedException e) { return "Error: " + e.getMessage(); }
    }
}
