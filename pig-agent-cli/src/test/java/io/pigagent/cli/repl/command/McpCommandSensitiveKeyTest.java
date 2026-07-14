package io.pigagent.cli.repl.command;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 敏感键判定：值需掩码录入的键（token/key/authorization/secret/password），不区分大小写；
 * 普通配置键（如 REGION）不掩码。
 */
class McpCommandSensitiveKeyTest {

    @Test
    void sensitiveKeys_areDetected() {
        assertThat(McpCommand.isSensitiveKey("Authorization")).isTrue();
        assertThat(McpCommand.isSensitiveKey("GITHUB_TOKEN")).isTrue();
        assertThat(McpCommand.isSensitiveKey("API_KEY")).isTrue();
        assertThat(McpCommand.isSensitiveKey("client_secret")).isTrue();
        assertThat(McpCommand.isSensitiveKey("DB_PASSWORD")).isTrue();
        assertThat(McpCommand.isSensitiveKey("x-subscription-token")).isTrue();
    }

    @Test
    void ordinaryKeys_areNotSensitive() {
        assertThat(McpCommand.isSensitiveKey("REGION")).isFalse();
        assertThat(McpCommand.isSensitiveKey("HOST")).isFalse();
        assertThat(McpCommand.isSensitiveKey("timeout")).isFalse();
    }
}
