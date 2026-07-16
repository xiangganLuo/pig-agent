package io.pigagent.tool.permission;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EXEC 命令键规范化（首 token）——保留自 {@code PermissionResolverTest} 的命令粒度覆盖。
 * 用于 Phase-4 HITL「始终允许」时把 {@code executeCommand} 调用持久化为命令键 allowlist 条目。
 */
class CommandKeysTest {

    @Test
    void firstTokenOfMultiWordCommand() {
        assertThat(CommandKeys.of(Map.of("command", "git status"))).isEqualTo("git");
    }

    @Test
    void singleWordCommandIsItself() {
        assertThat(CommandKeys.of(Map.of("command", "ls"))).isEqualTo("ls");
    }

    @Test
    void leadingWhitespaceTrimmed() {
        assertThat(CommandKeys.of(Map.of("command", "   mvn test"))).isEqualTo("mvn");
    }

    @Test
    void nullInputOrMissingCommandOrBlankIsNull() {
        assertThat(CommandKeys.of(null)).isNull();
        assertThat(CommandKeys.of(Map.of("other", "x"))).isNull();
        assertThat(CommandKeys.of(Map.of("command", "   "))).isNull();
    }

    @Test
    void nullCommandValueIsNull() {
        Map<String, Object> input = new HashMap<>();
        input.put("command", null);
        assertThat(CommandKeys.of(input)).isNull();
    }

    @Test
    void commandToolNameConstantIsExecuteCommand() {
        assertThat(CommandKeys.COMMAND_TOOL_NAME).isEqualTo("executeCommand");
    }

    @Test
    void tricksDoNotNormalizeToAPlainCommand() {
        // Path-prefix / quoting / operator-gluing yield a first token that is NOT the bare command, so
        // an allowlist of "git" can never be satisfied by these — the check falls through (safe).
        assertThat(CommandKeys.of(Map.of("command", "/usr/bin/git push"))).isEqualTo("/usr/bin/git");
        assertThat(CommandKeys.of(Map.of("command", "\"git\" push"))).isEqualTo("\"git\"");
        assertThat(CommandKeys.of(Map.of("command", "git;rm -rf /"))).isEqualTo("git;rm");
    }
}
