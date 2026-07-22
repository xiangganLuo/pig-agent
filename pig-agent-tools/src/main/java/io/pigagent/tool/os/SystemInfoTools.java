package io.pigagent.tool.os;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.pigagent.tool.contract.ToolErrors;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.RuntimeMXBean;

/**
 * Host-independent, pure-Java system introspection: an at-a-glance host snapshot ({@code systemInfo})
 * and filesystem free-space ({@code diskUsage}). Implemented entirely with {@code java.lang.management}
 * + {@code java.io.File} — they NEVER shell out to {@code df}/{@code free}/{@code top}, so they give the
 * same answer on Windows/PowerShell and POSIX without the agent having to remember platform-specific
 * commands, and never re-pay the command sandbox + permission cost.
 *
 * <p>Both are read-only and bounded. Backs the {@code system-health} built-in skill.
 */
public final class SystemInfoTools {

    /** Physical-memory readings degrade to this when the JVM does not expose them. */
    private static final String UNKNOWN = "n/a";

    @Tool(description = "Report an at-a-glance snapshot of the host: OS name/version/arch, CPU core "
            + "count, JVM version, heap usage, physical memory, system load and uptime. Pure Java — "
            + "works identically on Windows and POSIX, no shell needed.", readOnly = true)
    public String systemInfo() {
        try {
            OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
            RuntimeMXBean rt = ManagementFactory.getRuntimeMXBean();
            Runtime runtime = Runtime.getRuntime();

            StringBuilder sb = new StringBuilder();
            sb.append("OS:        ").append(os.getName()).append(' ')
                    .append(os.getVersion()).append(" (").append(os.getArch()).append(")\n");
            sb.append("CPU cores: ").append(os.getAvailableProcessors()).append('\n');
            sb.append("JVM:       ").append(System.getProperty("java.version"))
                    .append(" (").append(System.getProperty("java.vendor")).append(")\n");
            sb.append("Heap:      ").append(humanBytes(runtime.totalMemory() - runtime.freeMemory()))
                    .append(" used / ").append(humanBytes(runtime.maxMemory())).append(" max\n");

            long[] mem = physicalMemory(os);
            sb.append("Memory:    ")
                    .append(mem == null ? UNKNOWN : humanBytes(mem[0] - mem[1]) + " used / " + humanBytes(mem[0]))
                    .append('\n');

            double load = os.getSystemLoadAverage();
            sb.append("Load avg:  ").append(load < 0 ? UNKNOWN : String.format("%.2f", load)).append('\n');
            sb.append("Uptime:    ").append(humanDuration(rt.getUptime()));
            return sb.toString();
        } catch (RuntimeException e) {
            return ToolErrors.message(e.getMessage());
        }
    }

    @Tool(description = "Report total / used / free space of the filesystem that holds the given path "
            + "(blank = the current working directory). Pure Java — no df/Get-PSDrive.", readOnly = true)
    public String diskUsage(
            @ToolParam(name = "path", description = "A path on the filesystem to measure "
                    + "(blank = current working directory)") String path) {
        String target = (path == null || path.isBlank()) ? System.getProperty("user.dir") : path.trim();
        File f = new File(target);
        if (!f.exists()) {
            return ToolErrors.message("path does not exist: " + target);
        }
        long total = f.getTotalSpace();
        if (total <= 0) {
            return ToolErrors.message("cannot read filesystem for: " + target);
        }
        long usable = f.getUsableSpace();
        long used = total - f.getFreeSpace();
        double pct = 100.0 * used / total;
        return "Path:  " + target + '\n'
                + "Total: " + humanBytes(total) + '\n'
                + "Used:  " + humanBytes(used) + String.format(" (%.1f%%)%n", pct)
                + "Free:  " + humanBytes(usable) + " usable";
    }

    /** Physical {total, free} memory via {@code com.sun.management}, or {@code null} if unavailable. */
    private static long[] physicalMemory(OperatingSystemMXBean os) {
        if (os instanceof com.sun.management.OperatingSystemMXBean sun) {
            try {
                return new long[]{sun.getTotalMemorySize(), sun.getFreeMemorySize()};
            } catch (RuntimeException | LinkageError ignore) {
                // older/non-HotSpot JVM without these accessors — degrade to n/a
            }
        }
        return null;
    }

    /** Format a byte count as a compact human-readable string (KB/MB/GB/TB, base 1024). */
    static String humanBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        String[] units = {"KB", "MB", "GB", "TB", "PB"};
        double value = bytes;
        int i = -1;
        do {
            value /= 1024.0;
            i++;
        } while (value >= 1024 && i < units.length - 1);
        return String.format("%.1f %s", value, units[i]);
    }

    /** Format a millisecond duration as e.g. {@code 3d 4h 12m}. */
    static String humanDuration(long millis) {
        long seconds = millis / 1000;
        long days = seconds / 86400;
        long hours = (seconds % 86400) / 3600;
        long minutes = (seconds % 3600) / 60;
        StringBuilder sb = new StringBuilder();
        if (days > 0) {
            sb.append(days).append("d ");
        }
        if (days > 0 || hours > 0) {
            sb.append(hours).append("h ");
        }
        sb.append(minutes).append('m');
        return sb.toString();
    }
}
