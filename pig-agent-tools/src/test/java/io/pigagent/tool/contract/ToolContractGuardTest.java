package io.pigagent.tool.contract;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * End-to-end over a real {@link Toolkit}: after {@link ToolContractGuard#install}, dispatching a
 * tool whose {@code callAsync} fails yields a canonical {@code {"error":...}} result and never
 * aborts the call, while a healthy tool still returns its normal output.
 */
class ToolContractGuardTest {

    /** A raw AgentTool that always fails asynchronously — stands in for a tool violating the contract. */
    private static AgentTool failing(String name) {
        return new AgentTool() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public String getDescription() {
                return "always fails";
            }

            @Override
            public Map<String, Object> getParameters() {
                return Map.of();
            }

            @Override
            public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                return Mono.error(new RuntimeException("contract violation"));
            }
        };
    }

    public static final class Healthy {
        @Tool(description = "echoes back")
        public String echo(@ToolParam(name = "msg", description = "text") String msg) {
            return "echo:" + msg;
        }
    }

    private static String textOf(ToolResultBlock block) {
        return block.getOutput().stream()
                .filter(b -> b instanceof TextBlock)
                .map(b -> ((TextBlock) b).getText())
                .findFirst().orElse("");
    }

    private static ToolResultBlock call(Toolkit tk, String tool, Map<String, Object> input) {
        return tk.callTool(ToolCallParam.builder()
                .toolUseBlock(ToolUseBlock.builder().id("id-1").name(tool)
                        .input(input).content(toJson(input)).build())
                .input(input)
                .build()).block();
    }

    /** Minimal JSON object of string-valued inputs — the raw content the schema validator reads. */
    private static String toJson(Map<String, Object> input) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : input.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            sb.append('"').append(e.getKey()).append("\":\"").append(e.getValue()).append('"');
            first = false;
        }
        return sb.append('}').toString();
    }

    @Test
    void guardsFailingToolIntoCanonicalError() {
        Toolkit tk = new Toolkit();
        tk.registration().agentTool(failing("boom")).apply();

        ToolContractGuard.install(tk);

        ToolResultBlock result = call(tk, "boom", Map.of());
        assertThat(textOf(result)).startsWith("{\"error\":\"");
        assertThat(textOf(result)).contains("contract violation");
    }

    @Test
    void failingToolDoesNotAbortTheCall() {
        Toolkit tk = new Toolkit();
        tk.registration().agentTool(failing("boom")).apply();
        ToolContractGuard.install(tk);

        assertThatCode(() -> call(tk, "boom", Map.of())).doesNotThrowAnyException();
    }

    @Test
    void healthyToolStillReturnsNormalOutput() {
        Toolkit tk = new Toolkit();
        tk.registration().tool(new Healthy()).apply();

        ToolContractGuard.install(tk);

        ToolResultBlock result = call(tk, "echo", Map.of("msg", "hi"));
        assertThat(textOf(result)).contains("echo:hi");
    }

    @Test
    void installIsIdempotent() {
        Toolkit tk = new Toolkit();
        tk.registration().agentTool(failing("boom")).apply();

        ToolContractGuard.install(tk);
        AgentTool afterFirst = tk.getTool("boom");
        ToolContractGuard.install(tk);
        AgentTool afterSecond = tk.getTool("boom");

        assertThat(afterFirst).isInstanceOf(GuardedAgentTool.class);
        assertThat(afterSecond).isSameAs(afterFirst);
    }
}
