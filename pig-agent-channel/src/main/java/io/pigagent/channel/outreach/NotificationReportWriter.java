package io.pigagent.channel.outreach;

import io.pigagent.core.agent.AgentSpec;
import io.pigagent.core.agent.runner.AgentReport;
import io.pigagent.core.agent.runner.AgentRunner;
import io.pigagent.core.outreach.Notification;
import io.pigagent.core.outreach.NotificationService;
import io.pigagent.core.outreach.NotificationType;
import io.pigagent.core.outreach.Severity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.Objects;

/**
 * An <b>event</b> outreach trigger: pushes a digital-employee morning report to a channel by mapping
 * the {@link AgentReport} to a {@link Notification} and handing it to the {@link NotificationService}.
 *
 * <p>Implements {@link AgentRunner.ReportWriter} so it composes with {@code FileReportWriter} through
 * {@code CompositeReportWriter} — the report is still always written to disk; this is an additional,
 * opt-in push. Fault-tolerant: a notify failure is logged, never thrown (it must not break the run).
 */
public final class NotificationReportWriter implements AgentRunner.ReportWriter {

    private static final Logger log = LoggerFactory.getLogger(NotificationReportWriter.class);

    private static final int SUMMARY_LIMIT = 500;

    private final NotificationService service;

    public NotificationReportWriter(NotificationService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    @Override
    public void write(AgentSpec spec, AgentReport report) {
        try {
            Notification n = toNotification(spec, report);
            service.notify(n);
        } catch (Exception e) {
            log.warn("Report push for '{}' failed: {}", spec == null ? "?" : spec.id(), e.getMessage());
        }
    }

    private static Notification toNotification(AgentSpec spec, AgentReport report) {
        String name = report.agentName() == null ? spec.name() : report.agentName();
        Severity severity = report.outcome() == AgentReport.Outcome.SUCCESS ? Severity.NORMAL : Severity.HIGH;
        String title = "晨报 · " + name + " · " + report.outcome();
        String body = summarize(report);
        // Stable per (agent, day) so a re-run on the same day is de-duped by the guardrail.
        String dedupKey = "report:" + report.agentId() + ":" + LocalDate.now();
        return Notification.of(NotificationType.REPORT, severity, title, body).withDedupKey(dedupKey);
    }

    private static String summarize(AgentReport report) {
        StringBuilder sb = new StringBuilder();
        String bodyText = report.body() == null ? "" : report.body().strip();
        if (bodyText.isEmpty()) {
            sb.append("（无输出）");
        } else if (bodyText.length() > SUMMARY_LIMIT) {
            sb.append(bodyText, 0, SUMMARY_LIMIT).append("…");
        } else {
            sb.append(bodyText);
        }
        if (!report.pending().isEmpty()) {
            sb.append("\n\n⏳ 等你决定：").append(report.pending().size()).append(" 项");
        }
        return sb.toString();
    }
}
