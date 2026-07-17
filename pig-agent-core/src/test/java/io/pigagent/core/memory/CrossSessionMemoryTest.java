package io.pigagent.core.memory;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.harness.agent.memory.MemoryConfig;
import io.agentscope.harness.agent.tool.MemorySaveTool;
import io.agentscope.harness.agent.tool.MemorySearchTool;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import io.pigagent.core.agent.PigAgent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The cross-session memory bug fix (capability {@code pa-memory-native}), migrated from the RED repro
 * on {@code bug/20260717-cross-session-memory} to the AgentScope-2.0 <b>native two-layer memory</b>.
 *
 * <p><b>Original bug.</b> pig's self-built {@code CompositeLongTermMemory.record()} routed every recorded
 * fact to the <b>session tier only</b>; the global tier was never auto-grown, so after {@code /session
 * new} swapped the session tier a durable fact stated in session A was lost in session B. The
 * self-built two-tier stack (and its incidental {@code MIN_TEXT_LENGTH=20} short-fact filter) is retired.
 *
 * <p><b>The fix, proven here (deterministic, offline — no live model).</b> pig now adopts AgentScope
 * 2.0 native memory, whose consolidated {@code MEMORY.md} + daily ledger live at the <b>workspace
 * level</b> (not per-session). A fact saved via the native {@code memory_save} tool under session A's
 * {@link RuntimeContext} is recalled via {@code memory_search} under session B's — because both hit the
 * same workspace-level {@code MEMORY.md}. The tools are pure file IO (no model), so this is a real
 * unit-level repro of the north-star "remembers you across sessions" behaviour. Real per-turn flush +
 * consolidation quality (which do call the cheap model) are validated by the live-model {@code *IT}.
 */
class CrossSessionMemoryTest {

    /** Stub model: never actually called (we exercise the memory tools' file IO, not a chat turn). */
    private static Model stubModel() {
        return new Model() {
            @Override public String getModelName() { return "stub"; }
            @Override public Flux<ChatResponse> stream(List<Msg> m, List<ToolSchema> t, GenerateOptions o) {
                return Flux.empty();
            }
        };
    }

    private static RuntimeContext session(String id) {
        return RuntimeContext.builder().userId("pig").sessionId(id).build();
    }

    @Test
    void personalFactSavedInSessionA_isRecalledInSessionB(@TempDir Path workspace) {
        // Arrange: a PigAgent with native two-layer memory enabled over a real (temp) workspace.
        PigAgent agent = PigAgent.builder()
                .name("t").sysPrompt("s").model(stubModel())
                .memory(MemoryConfig.defaults())
                .workspace(workspace)
                .build();
        WorkspaceManager ws = agent.getHarnessAgent().getWorkspaceManager();
        MemorySaveTool save = new MemorySaveTool(ws);
        MemorySearchTool search = new MemorySearchTool(ws);

        // Act 1 (Session A): the user's name is persisted to workspace-level long-term memory.
        save.memorySave(session("session-A"), "- 我叫罗湘赣，请牢牢记住我的名字");

        // Act 2 (/session new → Session B): a DIFFERENT session searches long-term memory.
        String recalledInB = search.memorySearch(session("session-B"), "罗湘赣");

        // Assert the north-star behaviour: a personal fact stated in session A is recalled in session B.
        assertThat(recalledInB)
                .as("name saved in session A must be recalled in session B (workspace-level MEMORY.md)")
                .contains("罗湘赣");
        // And it is on the workspace-level consolidated layer (the system-prompt injection source),
        // visible regardless of the reading session id.
        assertThat(ws.readMemoryMd(session("session-B")))
                .as("fact lives in the workspace-level MEMORY.md, not a session-scoped tier")
                .contains("罗湘赣");

        agent.close();
    }

    @Test
    void shortFact_notDroppedByLengthFilter(@TempDir Path workspace) {
        // The retired FileSystemLongTermMemory dropped facts shorter than 20 chars (MIN_TEXT_LENGTH).
        // Native memory has no such filter — a tiny fact is stored and found.
        PigAgent agent = PigAgent.builder()
                .name("t").sysPrompt("s").model(stubModel())
                .memory(MemoryConfig.defaults())
                .workspace(workspace)
                .build();
        WorkspaceManager ws = agent.getHarnessAgent().getWorkspaceManager();

        new MemorySaveTool(ws).memorySave(session("A"), "- 我叫 X");
        String recalled = new MemorySearchTool(ws).memorySearch(session("B"), "我叫 X");

        assertThat(recalled)
                .as("a fact shorter than the old 20-char threshold is not silently dropped")
                .contains("我叫 X");
        agent.close();
    }
}
