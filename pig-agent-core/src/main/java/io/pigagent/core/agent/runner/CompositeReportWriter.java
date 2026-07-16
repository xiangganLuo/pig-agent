package io.pigagent.core.agent.runner;

import io.pigagent.core.agent.AgentSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * A {@link AgentRunner.ReportWriter} that fans a morning report out to several delegates (Composite):
 * e.g. the file writer plus a channel-push writer. Fault-tolerant — a delegate that throws is logged
 * and skipped so the others still run (a failing push must never stop the report from being written
 * to disk, and vice versa).
 */
public final class CompositeReportWriter implements AgentRunner.ReportWriter {

    private static final Logger log = LoggerFactory.getLogger(CompositeReportWriter.class);

    private final List<AgentRunner.ReportWriter> delegates;

    public CompositeReportWriter(AgentRunner.ReportWriter... delegates) {
        this.delegates = List.of(delegates);
    }

    @Override
    public void write(AgentSpec spec, AgentReport report) {
        for (AgentRunner.ReportWriter delegate : delegates) {
            try {
                delegate.write(spec, report);
            } catch (Exception e) {
                log.warn("Report writer {} failed: {}", delegate.getClass().getSimpleName(), e.getMessage());
            }
        }
    }
}
