package io.pigagent.tool.contract;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The dispatch-layer safety net: whatever a tool does — throw synchronously, return a failing
 * {@code Mono}, or return null — {@link GuardedAgentTool} MUST turn it into a canonical
 * {@code {"error":...}} result without ever letting the exception escape (which would abort the
 * turn). A normally-returning tool passes through unchanged.
 */
class GuardedAgentToolTest {

    private static ToolCallParam param() {
        return ToolCallParam.builder()
                .toolUseBlock(ToolUseBlock.builder().id("call-1").name("boom").input(Map.of()).build())
                .input(Map.of())
                .build();
    }

    private static String textOf(ToolResultBlock block) {
        return block.getOutput().stream()
                .filter(b -> b instanceof TextBlock)
                .map(b -> ((TextBlock) b).getText())
                .findFirst().orElse("");
    }

    private static AgentTool delegate(String name, java.util.function.Function<ToolCallParam, Mono<ToolResultBlock>> body) {
        return new AgentTool() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public String getDescription() {
                return "desc-" + name;
            }

            @Override
            public Map<String, Object> getParameters() {
                return Map.of("k", "v");
            }

            @Override
            public Mono<ToolResultBlock> callAsync(ToolCallParam callParam) {
                return body.apply(callParam);
            }
        };
    }

    @Test
    void synchronousThrowBecomesCanonicalError() {
        AgentTool throwing = delegate("boom", p -> {
            throw new IllegalStateException("kaboom");
        });
        GuardedAgentTool guarded = new GuardedAgentTool(throwing);

        ToolResultBlock result = guarded.callAsync(param()).block();

        assertThat(textOf(result)).startsWith("{\"error\":\"");
        assertThat(textOf(result)).contains("kaboom");
        assertThat(result.getId()).isEqualTo("call-1");
        assertThat(result.getName()).isEqualTo("boom");
    }

    @Test
    void monoErrorBecomesCanonicalError() {
        AgentTool erroring = delegate("boom", p -> Mono.error(new RuntimeException("async failure")));
        GuardedAgentTool guarded = new GuardedAgentTool(erroring);

        ToolResultBlock result = guarded.callAsync(param()).block();

        assertThat(textOf(result)).startsWith("{\"error\":\"");
        assertThat(textOf(result)).contains("async failure");
    }

    @Test
    void doesNotPropagateException() {
        AgentTool throwing = delegate("boom", p -> {
            throw new IllegalStateException("kaboom");
        });
        GuardedAgentTool guarded = new GuardedAgentTool(throwing);

        assertThatCode(() -> guarded.callAsync(param()).block()).doesNotThrowAnyException();
    }

    @Test
    void redactsCredentialsInEscapedException() {
        AgentTool leaking = delegate("boom",
                p -> Mono.error(new RuntimeException("bad api_key=LEAKEDSECRET999")));
        GuardedAgentTool guarded = new GuardedAgentTool(leaking);

        String text = textOf(guarded.callAsync(param()).block());

        assertThat(text).doesNotContain("LEAKEDSECRET999");
        assertThat(text).contains("***");
    }

    @Test
    void normalResultPassesThroughUnchanged() {
        ToolResultBlock ok = ToolResultBlock.text("all good").withIdAndName("call-1", "boom");
        AgentTool healthy = delegate("boom", p -> Mono.just(ok));
        GuardedAgentTool guarded = new GuardedAgentTool(healthy);

        ToolResultBlock result = guarded.callAsync(param()).block();

        assertThat(textOf(result)).isEqualTo("all good");
    }

    @Test
    void delegatesSchemaMethods() {
        GuardedAgentTool guarded = new GuardedAgentTool(delegate("boom", p -> Mono.empty()));

        assertThat(guarded.getName()).isEqualTo("boom");
        assertThat(guarded.getDescription()).isEqualTo("desc-boom");
        assertThat(guarded.getParameters()).containsEntry("k", "v");
    }
}
