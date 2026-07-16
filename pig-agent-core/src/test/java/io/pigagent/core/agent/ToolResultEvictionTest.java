package io.pigagent.core.agent;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.memory.compaction.ToolResultEvictionConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * av2 Phase 5b — proves the native tool-result eviction turned on through the {@code HarnessAgent}
 * wrap actually fires: a tool result larger than the configured threshold is spooled to disk under
 * the workspace and replaced in the conversation by a read-back placeholder (the capability pig
 * previously lacked). Fully offline with a fake model + a no-arg tool + a {@code @TempDir} workspace.
 */
class ToolResultEvictionTest {

    private static final String TOOL_NAME = "bigOutput";
    private static final int RESULT_CHARS = 4000; // > the 500-char threshold below → evicted
    private static final String BLOB_SENTINEL = "BLOBLINE_XYZZY"; // marks the full (evicted) content

    /** A no-arg tool returning a large blob (no @ToolParam → no reflective arg-binding concern). */
    static final class BigTool {
        final AtomicInteger invoked = new AtomicInteger();

        @Tool(name = TOOL_NAME, description = "Return a large blob (eviction test).")
        public String bigOutput() {
            invoked.incrementAndGet();
            return (BLOB_SENTINEL + " ").repeat(RESULT_CHARS / (BLOB_SENTINEL.length() + 1) + 1);
        }
    }

    /** Fake model: call #1 asks for the big tool, call #2 finishes; captures each message list. */
    static final class ToolThenTextModel implements Model {
        final List<List<Msg>> captured = new ArrayList<>();
        final AtomicInteger calls = new AtomicInteger();

        @Override public String getModelName() { return "fake-tool"; }

        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            captured.add(new ArrayList<>(messages));
            int n = calls.incrementAndGet();
            if (n == 1) {
                ContentBlock call = ToolUseBlock.builder()
                        .id("call-1").name(TOOL_NAME).input(Map.of()).build();
                return Flux.just(ChatResponse.builder()
                        .content(List.of(call)).finishReason("tool_calls").build());
            }
            return Flux.just(ChatResponse.builder()
                    .content(List.of(TextBlock.builder().text("done").build()))
                    .finishReason("stop").build());
        }
    }

    private static Msg user(String text) {
        return Msg.builder().name("user").role(MsgRole.USER)
                .content(TextBlock.builder().text(text).build()).build();
    }

    /** All text in a message list, recursing into tool-result output blocks (where eviction lands). */
    private static String allText(List<Msg> msgs) {
        StringBuilder sb = new StringBuilder();
        for (Msg m : msgs) {
            for (ContentBlock b : m.getContent()) {
                appendBlockText(sb, b);
            }
        }
        return sb.toString();
    }

    private static void appendBlockText(StringBuilder sb, ContentBlock b) {
        if (b instanceof TextBlock t) {
            sb.append(t.getText()).append('\n');
        } else if (b instanceof ToolResultBlock tr) {
            for (ContentBlock inner : tr.getOutput()) {
                appendBlockText(sb, inner);
            }
        }
    }

    @Test
    void largeToolResultIsEvictedToDisk_andPlaceholderRoundTrips(@TempDir Path workspace) {
        BigTool tools = new BigTool();
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(tools);

        ToolResultEvictionConfig eviction = ToolResultEvictionConfig.builder()
                .maxResultChars(500)
                .previewChars(40)
                .evictionPath("/large_tool_results")
                .excludedToolNames(Set.of()) // do not exclude our tool
                .build();

        ToolThenTextModel model = new ToolThenTextModel();
        PigAgent agent = PigAgent.builder()
                .name("evict").sysPrompt("sp").model(model)
                .toolkit(toolkit)
                .workspace(workspace)
                .maxIters(6)
                .toolResultEviction(eviction)
                .build();

        agent.stream(user("go")).blockLast();

        // The tool actually executed (produced the oversized result in context).
        assertThat(tools.invoked.get()).as("the big tool executed").isGreaterThanOrEqualTo(1);

        // Eviction mutates the RETAINED conversation: the oversized tool result is replaced in the
        // persisted context by a read-back placeholder (so it never bloats future turns). Inspect the
        // conversation the agent kept after the turn.
        List<Msg> retained = agent.getReactAgent().getAgentState().getContext();
        String retainedText = allText(retained);
        assertThat(retainedText)
                .as("the retained tool result is a read-back placeholder")
                .contains("Tool output was too large")
                .contains("read_file");
        // The full 4000-char blob is gone from the context — only the short preview survives (the
        // preview of the first 40 chars may echo the sentinel a couple of times, but not ~260 times).
        int sentinelHits = retainedText.split(BLOB_SENTINEL, -1).length - 1;
        assertThat(sentinelHits)
                .as("the full blob was evicted from the retained context (only a tiny preview remains)")
                .isLessThan(10);

        // The full content was spooled to disk under the workspace eviction dir.
        try (Stream<Path> walk = Files.walk(workspace)) {
            boolean spooled = walk.filter(Files::isRegularFile)
                    .anyMatch(p -> p.toString().replace('\\', '/').contains("large_tool_results"));
            assertThat(spooled).as("full tool result spooled under workspace/large_tool_results").isTrue();
        } catch (Exception e) {
            throw new AssertionError("walking the workspace failed", e);
        }
        agent.close();
    }
}
