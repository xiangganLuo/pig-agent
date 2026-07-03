package io.pigagent.tool.permission;

import io.pigagent.config.PermissionMode;
import io.pigagent.config.PigAgentConfig.PermissionConfig;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** PermissionResolver 端到端判定（含 ASK 确认三态、allowlist 记忆、命令粒度、非交互 fail-closed）。 */
class PermissionResolverTest {

    /** 记录被写入的 allowlist 键。 */
    private static final class RecordingWriter implements AllowlistWriter {
        final List<String> tools = new ArrayList<>();
        final List<String> commands = new ArrayList<>();
        public void rememberTool(String t) { tools.add(t); }
        public void rememberCommand(String c) { commands.add(c); }
    }

    private static PermissionConfig cfg(String mode) {
        PermissionConfig c = new PermissionConfig();
        c.setMode(mode);
        return c;
    }

    @Test
    void planDeniesWriteWithoutAskingConfirmer() {
        boolean allow = PermissionResolver.resolve(cfg("plan"), PermissionMode.PLAN,
                "writeFile", Map.of("path", "x"), p -> { throw new AssertionError("不应确认"); }, null);
        assertThat(allow).isFalse();
    }

    @Test
    void bypassAllowsExecWithoutAsking() {
        boolean allow = PermissionResolver.resolve(cfg("bypass"), PermissionMode.BYPASS,
                "executeCommand", Map.of("command", "rm -rf /"),
                p -> { throw new AssertionError("不应确认"); }, null);
        assertThat(allow).isTrue();
    }

    @Test
    void askAllowOnceDoesNotPersist() {
        RecordingWriter w = new RecordingWriter();
        boolean allow = PermissionResolver.resolve(cfg("ask"), PermissionMode.ASK,
                "executeCommand", Map.of("command", "git status"),
                p -> PermissionConfirmer.Outcome.ALLOW_ONCE, w);
        assertThat(allow).isTrue();
        assertThat(w.commands).isEmpty();
        assertThat(w.tools).isEmpty();
    }

    @Test
    void askAllowAlwaysPersistsCommandKeyForExec() {
        RecordingWriter w = new RecordingWriter();
        boolean allow = PermissionResolver.resolve(cfg("ask"), PermissionMode.ASK,
                "executeCommand", Map.of("command", "git status"),
                p -> PermissionConfirmer.Outcome.ALLOW_ALWAYS, w);
        assertThat(allow).isTrue();
        assertThat(w.commands).containsExactly("git"); // 规范化为首 token
        assertThat(w.tools).isEmpty();
    }

    @Test
    void askAllowAlwaysPersistsToolNameForNonExec() {
        RecordingWriter w = new RecordingWriter();
        boolean allow = PermissionResolver.resolve(cfg("ask"), PermissionMode.ASK,
                "writeFile", Map.of("path", "a.txt"),
                p -> PermissionConfirmer.Outcome.ALLOW_ALWAYS, w);
        assertThat(allow).isTrue();
        assertThat(w.tools).containsExactly("writeFile");
        assertThat(w.commands).isEmpty();
    }

    @Test
    void askDenyBlocks() {
        boolean allow = PermissionResolver.resolve(cfg("ask"), PermissionMode.ASK,
                "writeFile", Map.of(), p -> PermissionConfirmer.Outcome.DENY, null);
        assertThat(allow).isFalse();
    }

    @Test
    void allowlistedCommandSkipsConfirm() {
        PermissionConfig c = cfg("ask");
        c.getAllowlist().getCommands().add("git");
        boolean allow = PermissionResolver.resolve(c, PermissionMode.ASK,
                "executeCommand", Map.of("command", "git push"),
                p -> { throw new AssertionError("已 allowlist，不应确认"); }, null);
        assertThat(allow).isTrue();
    }

    @Test
    void nonInteractiveAskFailsClosed() {
        // confirmer=null 模拟渠道无终端：ASK 决策 → 拒绝
        boolean allow = PermissionResolver.resolve(cfg("auto"), PermissionMode.AUTO,
                "executeCommand", Map.of("command", "ls"), null, null);
        assertThat(allow).isFalse();
    }

    @Test
    void autoAllowsWriteWithoutConfirm() {
        boolean allow = PermissionResolver.resolve(cfg("auto"), PermissionMode.AUTO,
                "writeFile", Map.of("path", "a"), null, null);
        assertThat(allow).isTrue();
    }
}
