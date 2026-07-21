package io.pigagent.cli.tools;

import io.agentscope.core.tool.Tool;

/**
 * Test fixture with two {@code @Tool} methods named to match {@code ToolRiskClassifier} entries
 * ({@code readFile}=READ_ONLY, {@code writeFile}=WRITE), so {@link ToolsConsole} inventory + enable/
 * disable can be verified on a real Toolkit with deterministic risk labels.
 */
public final class ToolsSampleTools {

    @Tool(description = "Read a file.", readOnly = true)
    public String readFile() {
        return "content";
    }

    @Tool(description = "Write a file.")
    public String writeFile() {
        return "written";
    }
}
