package io.pigagent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** PermissionConfig / PermissionMode 的纯单测（YAML 往返 + 容错解析 + 缺省安全）。 */
class PermissionConfigTest {

    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

    @Test
    void defaultsAreAskAndAuto() {
        // Arrange
        PigAgentConfig.PermissionConfig p = new PigAgentConfig.PermissionConfig();

        // Assert
        assertThat(p.resolveMode()).isEqualTo(PermissionMode.ASK);
        assertThat(p.resolveChannelMode()).isEqualTo(PermissionMode.AUTO);
        assertThat(p.getToolOverrides()).isEmpty();
        assertThat(p.getAllowlist().getTools()).isEmpty();
        assertThat(p.getAllowlist().getCommands()).isEmpty();
    }

    @Test
    void fromStringIsTolerant() {
        assertThat(PermissionMode.fromString("nope", PermissionMode.ASK)).isEqualTo(PermissionMode.ASK);
        assertThat(PermissionMode.fromString(null, PermissionMode.AUTO)).isEqualTo(PermissionMode.AUTO);
        assertThat(PermissionMode.fromString("   ", PermissionMode.PLAN)).isEqualTo(PermissionMode.PLAN);
        assertThat(PermissionMode.fromString("  plan ", PermissionMode.ASK)).isEqualTo(PermissionMode.PLAN);
        assertThat(PermissionMode.fromString("BYPASS", PermissionMode.ASK)).isEqualTo(PermissionMode.BYPASS);
    }

    @Test
    void yamlRoundTripReadsAllFields() throws Exception {
        // Arrange
        String doc = """
                permissions:
                  mode: plan
                  channel-mode: bypass
                  tool-overrides:
                    fetchUrl: high
                  allowlist:
                    tools: [readFile, listDirectory]
                    commands: ["git status"]
                """;

        // Act
        PigAgentConfig cfg = yaml.readValue(doc, PigAgentConfig.class);
        PigAgentConfig.PermissionConfig p = cfg.getPermissions();

        // Assert
        assertThat(p.resolveMode()).isEqualTo(PermissionMode.PLAN);
        assertThat(p.resolveChannelMode()).isEqualTo(PermissionMode.BYPASS);
        assertThat(p.getToolOverrides()).containsEntry("fetchUrl", "high");
        assertThat(p.getAllowlist().getTools()).contains("readFile", "listDirectory");
        assertThat(p.getAllowlist().getCommands()).contains("git status");
    }

    @Test
    void missingPermissionsBlockFallsBackToDefaults() throws Exception {
        // Act：配置里没有 permissions 块
        PigAgentConfig cfg = yaml.readValue("agent:\n  name: X\n", PigAgentConfig.class);

        // Assert
        assertThat(cfg.getPermissions()).isNotNull();
        assertThat(cfg.getPermissions().resolveMode()).isEqualTo(PermissionMode.ASK);
        assertThat(cfg.getPermissions().resolveChannelMode()).isEqualTo(PermissionMode.AUTO);
    }

    @Test
    void unknownModeStringResolvesToAskNotCrash() throws Exception {
        PigAgentConfig cfg = yaml.readValue("permissions:\n  mode: garbage\n", PigAgentConfig.class);
        assertThat(cfg.getPermissions().resolveMode()).isEqualTo(PermissionMode.ASK);
    }
}
