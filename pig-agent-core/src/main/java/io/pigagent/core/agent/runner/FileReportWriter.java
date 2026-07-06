package io.pigagent.core.agent.runner;

import io.pigagent.core.agent.AgentSpec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Writes a morning report to {@code {reportsDir}/{date}/{agentId}.md}. Fault-tolerant: a write
 * failure is logged to stderr, never thrown (a report failing to persist must not crash the run).
 */
public final class FileReportWriter implements AgentRunner.ReportWriter {

    private final Path reportsDir;

    public FileReportWriter(Path reportsDir) {
        this.reportsDir = Objects.requireNonNull(reportsDir, "reportsDir");
    }

    @Override
    public void write(AgentSpec spec, AgentReport report) {
        try {
            Path dateDir = reportsDir.resolve(LocalDate.now().toString());
            Files.createDirectories(dateDir);
            Files.writeString(dateDir.resolve(spec.id() + ".md"), report.render());
        } catch (IOException e) {
            System.err.println("[Report] Failed to write report for '" + spec.id() + "': " + e.getMessage());
        }
    }
}
