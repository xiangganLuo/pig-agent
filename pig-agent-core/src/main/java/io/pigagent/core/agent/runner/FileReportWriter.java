package io.pigagent.core.agent.runner;

import io.pigagent.core.agent.AgentSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Writes a morning report to {@code {reportsDir}/{date}/{agentId}.md}. Fault-tolerant: a write
 * failure is logged, never thrown (a report failing to persist must not crash the run).
 */
public final class FileReportWriter implements AgentRunner.ReportWriter {

    private static final Logger log = LoggerFactory.getLogger(FileReportWriter.class);

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
            log.error("Failed to write report for '{}': {}", spec.id(), e.getMessage(), e);
        }
    }
}
