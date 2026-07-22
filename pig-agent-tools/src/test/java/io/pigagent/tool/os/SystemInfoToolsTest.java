package io.pigagent.tool.os;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SystemInfoTools}: {@code systemInfo} returns a populated host snapshot, {@code diskUsage}
 * reports space for a real path and errors on a missing one, and the byte/duration formatters are
 * correct — all offline, pure Java.
 */
class SystemInfoToolsTest {

    private final SystemInfoTools tools = new SystemInfoTools();

    @Test
    void systemInfo_reportsHostSnapshot() {
        String out = tools.systemInfo();

        assertThat(out).contains("OS:", "CPU cores:", "JVM:", "Heap:", "Memory:", "Load avg:", "Uptime:");
        assertThat(out).doesNotContain("\"error\"");
    }

    @Test
    void diskUsage_reportsSpaceForExistingPath(@TempDir Path dir) {
        String out = tools.diskUsage(dir.toString());

        assertThat(out).contains("Total:", "Used:", "Free:");
        assertThat(out).contains(dir.toString());
    }

    @Test
    void diskUsage_blankPathUsesCurrentDirectory() {
        String out = tools.diskUsage("  ");

        assertThat(out).contains("Total:").contains(System.getProperty("user.dir"));
    }

    @Test
    void diskUsage_missingPathReturnsCanonicalError() {
        String out = tools.diskUsage("/no/such/path/really/unlikely/xyzzy");

        assertThat(out).startsWith("{\"error\":").contains("does not exist");
    }

    @Test
    void humanBytes_formatsAcrossUnits() {
        assertThat(SystemInfoTools.humanBytes(512)).isEqualTo("512 B");
        assertThat(SystemInfoTools.humanBytes(1536)).isEqualTo("1.5 KB");
        assertThat(SystemInfoTools.humanBytes(5L * 1024 * 1024)).isEqualTo("5.0 MB");
        assertThat(SystemInfoTools.humanBytes(3L * 1024 * 1024 * 1024)).isEqualTo("3.0 GB");
    }

    @Test
    void humanDuration_formatsDaysHoursMinutes() {
        assertThat(SystemInfoTools.humanDuration(90_000)).isEqualTo("1m");
        assertThat(SystemInfoTools.humanDuration(3_600_000)).isEqualTo("1h 0m");
        long threeDaysPlus = ((3L * 24 + 4) * 60 + 12) * 60 * 1000;
        assertThat(SystemInfoTools.humanDuration(threeDaysPlus)).isEqualTo("3d 4h 12m");
    }
}
