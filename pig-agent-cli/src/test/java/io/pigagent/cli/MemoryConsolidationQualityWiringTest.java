package io.pigagent.cli;

import io.agentscope.harness.agent.memory.MemoryConfig;
import io.pigagent.config.PigAgentConfig;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wiring coverage for {@code AgentBootstrap.buildMemoryConfig} custom-prompt handling (capability
 * {@code memory-consolidation-quality}, task 6.2): default (blank) leaves the native default prompts
 * untouched, a valid custom consolidation prompt is applied, an invalid one safely falls back (never
 * reaching the native builder), and a custom flush prompt is applied. {@code modelManager} is unused
 * here (blank {@code model-id}), so {@code null} is safe.
 */
class MemoryConsolidationQualityWiringTest {

    private static PigAgentConfig.MemoryConfig memoryWith(String flushPrompt, String consolidationPrompt) {
        PigAgentConfig.MemoryConfig cfg = new PigAgentConfig.MemoryConfig();
        cfg.getConsolidationQuality().setFlushPrompt(flushPrompt);
        cfg.getConsolidationQuality().setConsolidationPrompt(consolidationPrompt);
        return cfg;
    }

    @Test
    void defaultBlankPrompts_leaveNativeDefaultsUntouched() {
        MemoryConfig mc = AgentBootstrap.buildMemoryConfig(new PigAgentConfig.MemoryConfig(), null);
        // Not calling the native builder setters → the fields stay null (native fills its DEFAULT_* at
        // consolidation/flush time). This is the byte-identical-to-today path.
        assertThat(mc.consolidationPrompt()).isNull();
        assertThat(mc.flushPrompt()).isNull();
    }

    @Test
    void validCustomConsolidationPrompt_isApplied() {
        String custom = "Merge into MEMORY.md within %d tokens (approximately %d characters).";
        MemoryConfig mc = AgentBootstrap.buildMemoryConfig(memoryWith("", custom), null);
        assertThat(mc.consolidationPrompt()).isEqualTo(custom);
    }

    @Test
    void invalidCustomConsolidationPrompt_fallsBackToNativeDefault() {
        // Only one %d → would break String.format(prompt, maxTokens, maxChars) in the background job.
        MemoryConfig mc = AgentBootstrap.buildMemoryConfig(memoryWith("", "Keep under %d tokens"), null);
        assertThat(mc.consolidationPrompt()).isNull(); // not applied → native default used at runtime
    }

    @Test
    void customFlushPrompt_isApplied() {
        String custom = "Extract durable facts as a markdown bullet list.";
        MemoryConfig mc = AgentBootstrap.buildMemoryConfig(memoryWith(custom, ""), null);
        assertThat(mc.flushPrompt()).isEqualTo(custom);
    }
}
