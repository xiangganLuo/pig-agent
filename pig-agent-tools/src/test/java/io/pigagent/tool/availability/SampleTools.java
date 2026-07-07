package io.pigagent.tool.availability;

import io.agentscope.core.tool.Tool;

/** Test fixture exposing two {@code @Tool} methods so gate filtering can be verified on a real Toolkit. */
public final class SampleTools {

    @Tool(description = "A tool that may be gated by availability.")
    public String gatedTool() {
        return "gated";
    }

    @Tool(description = "A tool that is always available.")
    public String alwaysTool() {
        return "always";
    }
}
