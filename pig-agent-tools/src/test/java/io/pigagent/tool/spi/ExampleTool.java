package io.pigagent.tool.spi;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

/**
 * A test-only builtin tool. It is discovered purely via the test-scoped
 * {@code META-INF/services/io.pigagent.tool.spi.ToolProvider} file — proving a new tool is picked up
 * by "dropping a file", with no edit to the assembly wiring.
 */
public final class ExampleTool {

    @Tool(description = "Echo back the given text (test tool)")
    public String exampleEcho(@ToolParam(name = "text", description = "text to echo") String text) {
        return text;
    }
}
