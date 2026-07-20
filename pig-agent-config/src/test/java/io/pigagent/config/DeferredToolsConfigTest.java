package io.pigagent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** tools.deferred 配置：默认「随规模智能开」(enabled=true)/清单空/阈值 25/auto-defer-mcp 开；YAML 反序列化;容错。 */
class DeferredToolsConfigTest {

    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

    @Test
    void defaults_whenNoDeferredBlock() {
        // 默认智能开：enabled=true + auto-defer-mcp + threshold 25（backward-safe 由阈值制在接线层保证）
        PigAgentConfig.DeferredToolsConfig d = new PigAgentConfig().getTools().getDeferred();
        assertThat(d.isEnabled()).isTrue();
        assertThat(d.getTools()).isEmpty();
        assertThat(d.isAutoDeferMcp()).isTrue();
        assertThat(d.getThreshold()).isEqualTo(25);
    }

    @Test
    void missingBlock_yieldsDefaults() throws Exception {
        PigAgentConfig cfg = yaml.readValue("agent:\n  name: X\n", PigAgentConfig.class);
        assertThat(cfg.getTools().getDeferred().isEnabled()).isTrue();
        assertThat(cfg.getTools().getDeferred().getThreshold()).isEqualTo(25);
    }

    @Test
    void explicitlyDisabled_isHonoured() throws Exception {
        // 显式关闭是完全禁用逃生口
        PigAgentConfig cfg = yaml.readValue("tools:\n  deferred:\n    enabled: false\n", PigAgentConfig.class);
        assertThat(cfg.getTools().getDeferred().isEnabled()).isFalse();
    }

    @Test
    void parsesConfiguredValues() throws Exception {
        String src = """
                tools:
                  deferred:
                    enabled: true
                    auto-defer-mcp: false
                    threshold: 10
                    tools:
                      - bigTool
                      - rareTool
                """;
        PigAgentConfig cfg = yaml.readValue(src, PigAgentConfig.class);
        PigAgentConfig.DeferredToolsConfig d = cfg.getTools().getDeferred();
        assertThat(d.isEnabled()).isTrue();
        assertThat(d.isAutoDeferMcp()).isFalse();
        assertThat(d.getThreshold()).isEqualTo(10);
        assertThat(d.getTools()).containsExactly("bigTool", "rareTool");
    }

    @Test
    void nullSettersTolerated() {
        PigAgentConfig.DeferredToolsConfig d = new PigAgentConfig.DeferredToolsConfig();
        d.setTools(null);
        assertThat(d.getTools()).isEmpty();
        PigAgentConfig.ToolsConfig t = new PigAgentConfig.ToolsConfig();
        t.setDeferred(null);
        assertThat(t.getDeferred()).isNotNull();
    }
}
