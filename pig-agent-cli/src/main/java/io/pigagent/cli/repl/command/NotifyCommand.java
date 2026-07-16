package io.pigagent.cli.repl.command;

import io.pigagent.cli.Ansi;
import io.pigagent.cli.repl.ReplContext;
import io.pigagent.config.PigAgentConfig;
import io.pigagent.core.outreach.Notification;
import io.pigagent.core.outreach.NotificationResult;
import io.pigagent.core.outreach.NotificationType;
import io.pigagent.core.outreach.Severity;
import org.fusesource.jansi.Ansi.Color;
import org.jline.terminal.Terminal;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

/**
 * {@code /notify} — operator command for proactive outreach: {@code test [message]} sends a test
 * notification through the notification service and shows the result; {@code status} shows the outreach
 * configuration. Never displays the recipient value (only whether one is configured).
 */
@Command(name = "/notify", description = "Proactive outreach (status|test)")
public final class NotifyCommand implements Runnable {

    private final ReplContext ctx;

    @Parameters(index = "0", arity = "0..1", paramLabel = "<action>", description = "status | test")
    String action;

    @Parameters(index = "1..*", paramLabel = "<message>")
    String[] args;

    public NotifyCommand(ReplContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public void run() {
        Terminal t = ctx.terminal();
        String act = action == null ? "status" : action.toLowerCase();
        switch (act) {
            case "status" -> status(t);
            case "test" -> test(t);
            case "help" -> usage(t);
            default -> {
                Ansi.println(t, Ansi.error("Unknown action: " + act));
                usage(t);
            }
        }
    }

    private void status(Terminal t) {
        PigAgentConfig.OutreachConfig o = ctx.configManager().getConfig().getOutreach();
        Ansi.println(t, Ansi.heading("Outreach"));
        Ansi.println(t, line("Enabled", o.isEnabled() ? "on" : "off"));
        Ansi.println(t, line("Channel", o.getChannel().isBlank() ? "(none)" : o.getChannel()));
        // Never print the recipient value — only whether one is configured.
        Ansi.println(t, line("Recipient", o.getRecipient().isBlank() ? "(none)" : "(set)"));
        PigAgentConfig.QuietHoursConfig q = o.getQuietHours();
        Ansi.println(t, line("Quiet hours", q.isEnabled()
                ? q.getStart() + "–" + q.getEnd() : "off"));
        Ansi.println(t, line("Rate limit", o.getRateLimit().getMaxPerWindow()
                + " / " + o.getRateLimit().getWindowMinutes() + "m"));
        Ansi.println(t, line("Dedup", o.getDedupWindowMinutes() + "m"));
        Ansi.println(t, line("Briefing", o.getBriefing().isEnabled()
                ? "on [" + o.getBriefing().getCron() + "]" : "off"));
        Ansi.println(t, line("Report push", o.getReportPush().isEnabled() ? "on" : "off"));
    }

    private void test(Terminal t) {
        String body = (args == null || args.length == 0) ? "This is a test notification."
                : String.join(" ", args).strip();
        Notification n = Notification.of(NotificationType.MESSAGE, Severity.NORMAL,
                "Test notification", body);
        NotificationResult r = ctx.notificationService().notify(n);
        if (r.delivered()) {
            Ansi.println(t, Ansi.success("Sent. ") + Ansi.dim(r.detail()));
        } else {
            Ansi.println(t, Ansi.warn("Not sent [" + r.outcome() + "]. ") + Ansi.dim(r.detail()));
        }
    }

    private static void usage(Terminal t) {
        Ansi.println(t, Ansi.heading("/notify actions:"));
        Ansi.println(t, Ansi.dim("  status            show outreach configuration"));
        Ansi.println(t, Ansi.dim("  test [message]    send a test notification + show the result"));
    }

    private static String line(String label, String value) {
        return "  " + Ansi.bold(String.format("%-12s", label), Color.CYAN) + Ansi.info(value);
    }
}
