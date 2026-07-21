package io.pigagent.cli;

import io.agentscope.harness.agent.memory.MemoryConfig;
import io.pigagent.config.PigAgentConfig;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wiring coverage for {@code AgentBootstrap.buildMemoryConfig} {@code dailyFileRetentionDays} exposure
 * (capability {@code memory-layering-and-decay}, M-C, task 5.3): the default (0) leaves the native
 * default (90 days) untouched — byte-identical to today — and a configured positive value reaches the
 * native {@link MemoryConfig}. {@code modelManager} is unused (blank {@code model-id}), so {@code null}
 * is safe.
 */
class MemoryLayeringDecayWiringTest {

    @Test
    void dailyFileRetentionDays_default_leavesNativeDefault() {
        MemoryConfig mc = AgentBootstrap.buildMemoryConfig(new PigAgentConfig.MemoryConfig(), null);
        // 0 (default) → pig does not call the native builder → the native default (90) is in force.
        assertThat(mc.dailyFileRetentionDays()).isEqualTo(90);
    }

    @Test
    void dailyFileRetentionDays_configured_reachesNative() {
        PigAgentConfig.MemoryConfig cfg = new PigAgentConfig.MemoryConfig();
        cfg.setDailyFileRetentionDays(120);
        MemoryConfig mc = AgentBootstrap.buildMemoryConfig(cfg, null);
        assertThat(mc.dailyFileRetentionDays()).isEqualTo(120);
    }
}
