package io.pigagent.core.memory;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.middleware.MiddlewareBase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Injects the AgentScope 2.0 native consolidated long-term memory ({@code MEMORY.md}) into the
 * <b>system prompt</b> (capability {@code pa-memory-native}, OD2-A). This is the injection half of
 * the native two-layer memory: the native {@code MemoryFlushMiddleware} appends extracted facts to
 * the daily ledger ({@code memory/YYYY-MM-DD.md}) and {@code MemoryMaintenanceMiddleware} consolidates
 * them into the workspace-level {@code MEMORY.md}; this middleware surfaces that curated file to the
 * model.
 *
 * <p><b>Why pig injects instead of the native {@code WorkspaceContextMiddleware}.</b> The native
 * system-prompt injection path ({@code disableWorkspaceContext=false}) drags in a large workspace
 * context block whose guidance references native tool names ({@code read_file}/{@code grep}/{@code
 * glob}/{@code write_file}/{@code edit_file}) that pig does <em>not</em> register (pig uses
 * {@code readFile}/{@code writeFile}/{@code listDirectory}/{@code executeCommand}) — injecting
 * instructions about non-existent tools is a model-quality regression. So pig keeps
 * {@code disableWorkspaceContext()} (it owns its byte-stable system prompt) and injects ONLY the
 * consolidated {@code MEMORY.md} here.
 *
 * <p><b>Prefix-cache behaviour (OD2-A / R1).</b> {@code onSystemPrompt} runs once per {@code call()}.
 * {@code MEMORY.md} is stable within a session (consolidation is background-throttled, min gap
 * defaults to 30 min), so the system prompt is byte-stable turn-to-turn <em>within</em> a session and
 * only changes the once after a consolidation rewrites {@code MEMORY.md} (a rare, accepted cache-miss).
 * Every other middleware stage is identity here.
 *
 * <p><b>Fault-tolerant.</b> A missing/blank/unreadable {@code MEMORY.md} yields the prompt unchanged
 * (no injection); an IO error is logged at debug and swallowed — memory injection never breaks a turn.
 * The file is read fresh each call so a consolidation is picked up on the next turn.
 */
public final class NativeMemoryContextMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(NativeMemoryContextMiddleware.class);

    /** Header prefixing the injected memory block in the system prompt. */
    static final String MEMORY_HEADER = "## Long-term Memory (MEMORY.md)";

    private final Path memoryFile;

    /** @param memoryFile the workspace-level {@code MEMORY.md} (native consolidated memory). */
    public NativeMemoryContextMiddleware(Path memoryFile) {
        this.memoryFile = Objects.requireNonNull(memoryFile, "memoryFile");
    }

    @Override
    public Mono<String> onSystemPrompt(Agent agent, RuntimeContext ctx, String currentPrompt) {
        String memory = readMemory();
        if (memory.isBlank()) {
            return Mono.justOrEmpty(currentPrompt);
        }
        String base = currentPrompt != null ? currentPrompt : "";
        String separator = base.isEmpty() || base.endsWith("\n") ? "" : "\n\n";
        return Mono.just(base + separator + MEMORY_HEADER + "\n" + memory.strip() + "\n");
    }

    /** Read {@code MEMORY.md} (empty when absent/unreadable — never throws). */
    private String readMemory() {
        try {
            if (!Files.isRegularFile(memoryFile)) {
                return "";
            }
            return Files.readString(memoryFile, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            log.debug("Could not read MEMORY.md at {}: {}", memoryFile, e.getMessage());
            return "";
        }
    }
}
