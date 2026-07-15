package io.pigagent.plugin.collection.tool;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.pigagent.tool.contract.ToolErrors;

import java.util.UUID;

/** Pure-compute UUID generator. No I/O. Failures return the canonical {@code {"error"}}. */
public final class UuidTool {

    private static final int MAX_COUNT = 100;

    @Tool(name = "generateUuid",
            description = "Generate one or more random (v4) UUIDs; count defaults to 1, max 100.")
    public String generateUuid(
            @ToolParam(name = "count", required = false,
                    description = "how many UUIDs to generate (1..100, default 1)") Integer count) {
        int n = count == null ? 1 : count;
        if (n < 1 || n > MAX_COUNT) {
            return ToolErrors.message("count out of range (1.." + MAX_COUNT + "): " + n);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(UUID.randomUUID());
        }
        return sb.toString();
    }
}
