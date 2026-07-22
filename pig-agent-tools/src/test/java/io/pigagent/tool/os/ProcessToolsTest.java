package io.pigagent.tool.os;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ProcessTools}: lists live processes (the current JVM is always among them), honors a
 * filter and the limit bounds — all offline via {@link ProcessHandle}.
 */
class ProcessToolsTest {

    private final ProcessTools tools = new ProcessTools();

    @Test
    void listProcesses_includesCurrentProcess() {
        long myPid = ProcessHandle.current().pid();

        String out = tools.listProcesses("", "");

        assertThat(out).contains("process(es):");
        assertThat(out).contains("PID " + myPid);
    }

    @Test
    void listProcesses_respectsLimit() {
        String out = tools.listProcesses("", "1");

        long rows = out.lines().filter(l -> l.startsWith("PID ")).count();
        assertThat(rows).isEqualTo(1);
    }

    @Test
    void listProcesses_unmatchedFilterReportsNoMatch() {
        String out = tools.listProcesses("zzz-no-such-process-zzz-" + System.nanoTime(), "");

        assertThat(out).startsWith("No processes match:");
    }

    @Test
    void listProcesses_invalidLimitFallsBackToDefault() {
        // A non-numeric / negative / over-cap limit must not throw; it clamps to the safe range.
        assertThat(tools.listProcesses("", "abc")).contains("process(es):");
        assertThat(tools.listProcesses("", "-5")).contains("process(es):");

        String capped = tools.listProcesses("", "99999");
        long rows = capped.lines().filter(l -> l.startsWith("PID ")).count();
        assertThat(rows).isLessThanOrEqualTo(ProcessTools.MAX_LIMIT);
    }
}
