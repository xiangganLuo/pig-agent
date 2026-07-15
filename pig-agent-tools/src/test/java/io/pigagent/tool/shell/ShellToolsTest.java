package io.pigagent.tool.shell;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the OS-native shell selection: on Windows the invocation MUST be PowerShell (native
 * {@code C:\} paths), NOT {@code bash -c} which would launch WSL and inject a {@code /mnt/c} prefix.
 */
class ShellToolsTest {

    @Test
    void windows_usesPowerShell_notWslBash() {
        List<String> inv = ShellTools.buildInvocation(true, "echo hi");
        assertThat(inv.get(0)).isEqualTo("powershell.exe");
        assertThat(inv).contains("-NoProfile", "-Command", "echo hi");
        assertThat(inv).doesNotContain("bash");
    }

    @Test
    void unix_usesBash() {
        List<String> inv = ShellTools.buildInvocation(false, "echo hi");
        assertThat(inv).containsExactly("bash", "-c", "echo hi");
    }

    // --- warn note: purely additive, appended to an already-executed result (offline, no spawn) ---

    @Test
    void appendWarnNote_addsWarningAfterBaseOutput() {
        String result = ShellTools.appendWarnNote("build ok", "package install (pip)");
        assertThat(result).startsWith("build ok");
        assertThat(result).contains(ShellTools.WARN_PREFIX + "package install (pip)");
        assertThat(result).contains("⚠"); // the warning-sign glyph is present
    }

    @Test
    void appendWarnNote_emptyBaseYieldsNoteOnly() {
        String result = ShellTools.appendWarnNote("", "PATH reassignment");
        assertThat(result).isEqualTo(ShellTools.WARN_PREFIX + "PATH reassignment");
    }

    @Test
    void appendWarnNote_nullBaseYieldsNoteOnly() {
        String result = ShellTools.appendWarnNote(null, "privilege escalation (sudo/su)");
        assertThat(result).isEqualTo(ShellTools.WARN_PREFIX + "privilege escalation (sudo/su)");
    }
}
