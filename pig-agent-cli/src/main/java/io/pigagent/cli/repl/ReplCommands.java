package io.pigagent.cli.repl;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.pigagent.cli.Ansi;
import io.pigagent.config.PigAgentConfig;
import io.pigagent.core.provider.AgentOnboardingProvider;
import io.pigagent.session.Session;
import io.pigagent.session.SessionManager;
import org.fusesource.jansi.Ansi.Color;
import org.jline.reader.LineReader;
import org.jline.terminal.Terminal;
import org.jline.utils.InfoCmp.Capability;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.IFactory;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * picocli command tree for the interactive REPL. Each slash command is a nested
 * {@link Command} whose instance carries the shared {@link ReplContext}; instances
 * are registered programmatically in {@link #build} so the context is available
 * without a custom factory, while picocli still performs field injection (e.g. the
 * positional argument of {@code /switch}).
 */
public final class ReplCommands {

    private ReplCommands() {
    }

    /**
     * Assemble the root {@link CommandLine} with all slash subcommands. Command
     * names include the leading {@code /} so completion and execution match exactly
     * what the user types.
     */
    public static CommandLine build(ReplContext ctx, IFactory factory) {
        CommandLine cmd = new CommandLine(new Root(), factory);
        cmd.addSubcommand(new HelpCommand(ctx));
        cmd.addSubcommand(new TasksCommand(ctx));
        cmd.addSubcommand(new SkillsCommand(ctx));
        cmd.addSubcommand(new ConfigCommand(ctx));
        cmd.addSubcommand(new ProvidersCommand(ctx));
        cmd.addSubcommand(new ModelCommand(ctx));
        cmd.addSubcommand(new SwitchCommand(ctx));
        cmd.addSubcommand(new ChannelsCommand(ctx));
        cmd.addSubcommand(new SessionCommand(ctx));
        cmd.addSubcommand(new MemoryCommand(ctx));
        cmd.addSubcommand(new StatusCommand(ctx));
        cmd.addSubcommand(new ClearCommand(ctx));
        cmd.addSubcommand(new QuitCommand(ctx));
        return cmd;
    }

    @Command(name = "", description = "Pig Agent interactive commands")
    static final class Root implements Runnable {
        @Override
        public void run() {
            // No-op: the root is only a container for slash subcommands.
        }
    }

    @Command(name = "/help", description = "Show this help")
    static final class HelpCommand implements Runnable {
        private final ReplContext ctx;

        HelpCommand(ReplContext ctx) {
            this.ctx = ctx;
        }

        @Override
        public void run() {
            Terminal t = ctx.terminal();
            Ansi.println(t, Ansi.heading("Commands:"));
            entry(t, "/help", "Show this help");
            entry(t, "/tasks", "List all tasks");
            entry(t, "/skills", "List available skills");
            entry(t, "/config", "Show current configuration");
            entry(t, "/providers", "List all LLM providers and availability");
            entry(t, "/model", "Show current model details");
            entry(t, "/switch <provider>", "Switch to a different provider");
            entry(t, "/channels", "Show connected channels and status");
            entry(t, "/session <action>", "Manage sessions (list|new|fork|switch|rename|clear|delete)");
            entry(t, "/memory <on|off>", "Toggle/show global + session memory loading");
            entry(t, "/status", "Show agent status summary");
            entry(t, "/clear", "Clear the screen");
            entry(t, "/quit", "Exit");
        }

        private static void entry(Terminal t, String cmd, String desc) {
            Ansi.println(t, "  " + Ansi.bold(String.format("%-20s", cmd), Color.CYAN) + Ansi.dim(desc));
        }
    }

    @Command(name = "/tasks", description = "List all tasks")
    static final class TasksCommand implements Runnable {
        private final ReplContext ctx;

        TasksCommand(ReplContext ctx) {
            this.ctx = ctx;
        }

        @Override
        public void run() {
            Ansi.println(ctx.terminal(), Ansi.info(ctx.agent().call(userMsg("List all tasks")).getTextContent()));
        }
    }

    @Command(name = "/skills", description = "List available skills")
    static final class SkillsCommand implements Runnable {
        private final ReplContext ctx;

        SkillsCommand(ReplContext ctx) {
            this.ctx = ctx;
        }

        @Override
        public void run() {
            Ansi.println(ctx.terminal(), Ansi.info(ctx.agent().call(userMsg("List available skills")).getTextContent()));
        }
    }

    @Command(name = "/config", description = "Show current configuration")
    static final class ConfigCommand implements Runnable {
        private final ReplContext ctx;

        ConfigCommand(ReplContext ctx) {
            this.ctx = ctx;
        }

        @Override
        public void run() {
            PigAgentConfig cfg = ctx.configManager().getConfig();
            Terminal t = ctx.terminal();
            Ansi.println(t, Ansi.heading("Configuration"));
            Ansi.println(t, line("Provider", cfg.getModel().getProvider()));
            Ansi.println(t, line("Model", cfg.getModel().getModelName()));
            Ansi.println(t, line("Agent", cfg.getAgent().getName()));
            Ansi.println(t, line("Max Iters", String.valueOf(cfg.getAgent().getMaxIters())));
            Ansi.println(t, line("MCP", String.valueOf(cfg.getMcp().getServers().keySet())));
            Ansi.println(t, line("Channels", String.valueOf(cfg.getChannels().keySet())));
        }
    }

    @Command(name = "/providers", description = "List all LLM providers and availability")
    static final class ProvidersCommand implements Runnable {
        private final ReplContext ctx;

        ProvidersCommand(ReplContext ctx) {
            this.ctx = ctx;
        }

        @Override
        public void run() {
            String current = ctx.configManager().getConfig().getModel().getProvider();
            Terminal t = ctx.terminal();
            Ansi.println(t, Ansi.heading("Registered providers:"));
            for (AgentOnboardingProvider p : ctx.registry().getAllProviders()) {
                boolean active = p.providerId().equals(current);
                String avail = p.isAvailable() ? Ansi.success("ready") : Ansi.warn("no API key");
                String marker = active ? Ansi.bold(" (active)", Color.GREEN) : "";
                Ansi.println(t, String.format("  %-12s  %-20s  ", p.providerId(), p.displayName()) + avail + marker);
            }
        }
    }

    @Command(name = "/model", description = "Show current model details")
    static final class ModelCommand implements Runnable {
        private final ReplContext ctx;

        ModelCommand(ReplContext ctx) {
            this.ctx = ctx;
        }

        @Override
        public void run() {
            PigAgentConfig cfg = ctx.configManager().getConfig();
            String providerId = cfg.getModel().getProvider();
            Terminal t = ctx.terminal();
            Ansi.println(t, Ansi.heading("Current Model"));
            Ansi.println(t, line("Provider", providerId));
            Ansi.println(t, line("Model", cfg.getModel().getModelName()));
            ctx.registry().findById(providerId).ifPresent(p -> {
                Ansi.println(t, line("Display", p.displayName()));
                Ansi.println(t, line("Description", p.description()));
                Ansi.println(t, line("Credentials", String.valueOf(p.requiredCredentialKeys())));
                Ansi.println(t, line("Available", String.valueOf(p.isAvailable())));
            });
        }
    }

    @Command(name = "/switch", description = "Switch to a different provider")
    static final class SwitchCommand implements Runnable {
        private final ReplContext ctx;

        @Parameters(index = "0", arity = "0..1", paramLabel = "<provider>",
                description = "Target provider id")
        String provider;

        SwitchCommand(ReplContext ctx) {
            this.ctx = ctx;
        }

        @Override
        public void run() {
            Terminal t = ctx.terminal();
            if (provider == null || provider.isBlank()) {
                Ansi.println(t, Ansi.warn("Usage: /switch <provider>"));
                Ansi.println(t, Ansi.dim("Available: " + ctx.registry().getAllProviders().stream()
                        .map(AgentOnboardingProvider::providerId).toList()));
                return;
            }
            String target = provider.toLowerCase();
            var providerOpt = ctx.registry().findById(target);
            if (providerOpt.isEmpty()) {
                Ansi.println(t, Ansi.error("Unknown provider: " + target));
                return;
            }
            var p = providerOpt.get();
            if (!p.isAvailable()) {
                Ansi.println(t, Ansi.error("Provider '" + target + "' is not available. Check API key."));
                return;
            }
            ctx.configManager().updateConfig(cfg -> {
                cfg.getModel().setProvider(target);
                cfg.getModel().setModelName(p.defaultModelName());
            });
            Ansi.println(t, Ansi.success("Switched to " + p.displayName() + " / " + p.defaultModelName()));
            Ansi.println(t, Ansi.dim("Note: restart required for model change to take effect."));
        }
    }

    @Command(name = "/channels", description = "Show connected channels and status")
    static final class ChannelsCommand implements Runnable {
        private final ReplContext ctx;

        ChannelsCommand(ReplContext ctx) {
            this.ctx = ctx;
        }

        @Override
        public void run() {
            Terminal t = ctx.terminal();
            if (ctx.bridges().isEmpty()) {
                Ansi.println(t, Ansi.dim("No channels connected."));
                return;
            }
            Ansi.println(t, Ansi.heading("Connected channels:"));
            ctx.bridges().forEach(bridge -> {
                var ch = bridge.getChannel();
                String state = ch.isRunning() ? Ansi.success("running") : Ansi.warn("stopped");
                Ansi.println(t, String.format("  %-12s  %-16s  ", ch.channelId(), ch.displayName()) + state);
            });
        }
    }

    @Command(name = "/status", description = "Show agent status summary")
    static final class StatusCommand implements Runnable {
        private final ReplContext ctx;

        StatusCommand(ReplContext ctx) {
            this.ctx = ctx;
        }

        @Override
        public void run() {
            PigAgentConfig cfg = ctx.configManager().getConfig();
            Terminal t = ctx.terminal();
            long runningChannels = ctx.bridges().stream().filter(b -> b.getChannel().isRunning()).count();
            SessionManager sm = ctx.sessionManager();
            String sessionLabel = sm.getCurrentSession()
                    .map(s -> s.name() + " [" + s.id() + "]")
                    .orElse("(none)");
            Ansi.println(t, Ansi.heading("Agent Status"));
            Ansi.println(t, line("Agent", ctx.agent().getAgentName()));
            Ansi.println(t, line("Provider", cfg.getModel().getProvider()));
            Ansi.println(t, line("Model", cfg.getModel().getModelName()));
            Ansi.println(t, line("Session", sessionLabel));
            Ansi.println(t, line("Memory", sm.isMemoryEnabled() ? "on" : "off"));
            Ansi.println(t, line("Channels", runningChannels + " running"));
            Ansi.println(t, line("MCP", cfg.getMcp().getServers().size() + " configured"));
        }
    }

    @Command(name = "/clear", description = "Clear the screen")
    static final class ClearCommand implements Runnable {
        private final ReplContext ctx;

        ClearCommand(ReplContext ctx) {
            this.ctx = ctx;
        }

        @Override
        public void run() {
            Terminal t = ctx.terminal();
            t.puts(Capability.clear_screen);
            t.flush();
        }
    }

    @Command(name = "/quit", aliases = "/exit", description = "Exit")
    static final class QuitCommand implements Runnable {
        private final ReplContext ctx;

        QuitCommand(ReplContext ctx) {
            this.ctx = ctx;
        }

        @Override
        public void run() {
            Ansi.println(ctx.terminal(), Ansi.success("Goodbye!"));
            ctx.running().set(false);
        }
    }

    @Command(name = "/session", description = "Manage conversation sessions")
    static final class SessionCommand implements Runnable {
        private static final DateTimeFormatter TS =
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

        private final ReplContext ctx;

        @Parameters(index = "0", arity = "0..1", paramLabel = "<action>",
                description = "list | new | fork | switch | rename | clear | delete")
        String action;

        @Parameters(index = "1..*", paramLabel = "<args>",
                description = "action arguments (name, id, or list index)")
        String[] args;

        @Option(names = "--with-memory", description = "with 'clear', also wipe temporary memory")
        boolean withMemory;

        SessionCommand(ReplContext ctx) {
            this.ctx = ctx;
        }

        @Override
        public void run() {
            Terminal t = ctx.terminal();
            SessionManager sm = ctx.sessionManager();
            String act = action == null ? "list" : action.toLowerCase();
            switch (act) {
                case "list" -> listSessions(t, sm);
                case "new" -> announce(t, "Created and switched to: ", sm.createBlank(joinArgs()));
                case "fork" -> announce(t, "Forked into: ", sm.fork(joinArgs()));
                case "switch" -> switchSession(t, sm);
                case "rename" -> renameSession(t, sm);
                case "clear" -> {
                    sm.clearConversation(withMemory);
                    Ansi.println(t, Ansi.success(withMemory
                            ? "Cleared conversation + temporary memory."
                            : "Cleared conversation (temporary memory kept)."));
                }
                case "delete" -> deleteSessions(t, sm);
                case "help" -> usage(t);
                default -> {
                    Ansi.println(t, Ansi.error("Unknown action: " + act));
                    usage(t);
                }
            }
        }

        private void listSessions(Terminal t, SessionManager sm) {
            List<Session> sessions = sm.list();
            String current = sm.getCurrentSessionId();
            Ansi.println(t, Ansi.heading("Sessions:"));
            if (sessions.isEmpty()) {
                Ansi.println(t, Ansi.dim("  (none)"));
                return;
            }
            int idx = 1;
            for (Session s : sessions) {
                String marker = s.id().equals(current) ? Ansi.bold(" *", Color.GREEN) : "  ";
                String name = s.corrupt() ? Ansi.error(s.name()) : Ansi.info(s.name());
                String meta = Ansi.dim(" [" + s.id() + "]  created " + TS.format(s.createdAt())
                        + "  active " + TS.format(s.lastActiveAt()));
                Ansi.println(t, String.format("  %2d)", idx) + marker + " " + name + meta);
                idx++;
            }
        }

        private void switchSession(Terminal t, SessionManager sm) {
            if (args == null || args.length == 0) {
                Ansi.println(t, Ansi.warn("Usage: /session switch <id|index>"));
                return;
            }
            String id = resolveId(sm, args[0]);
            if (id == null) {
                Ansi.println(t, Ansi.error("No such session: " + args[0]));
                return;
            }
            sm.activate(id);
            sm.getCurrentSession().ifPresent(s ->
                    Ansi.println(t, Ansi.success("Switched to: ") + Ansi.info(s.name() + " [" + s.id() + "]")));
        }

        private void renameSession(Terminal t, SessionManager sm) {
            String name = joinArgs();
            if (name == null || name.isBlank()) {
                Ansi.println(t, Ansi.warn("Usage: /session rename <new name>"));
                return;
            }
            sm.rename(name);
            Ansi.println(t, Ansi.success("Renamed current session to: ") + Ansi.info(name));
        }

        private void deleteSessions(Terminal t, SessionManager sm) {
            if (args == null || args.length == 0) {
                Ansi.println(t, Ansi.warn("Usage: /session delete <id|index> [more...]"));
                return;
            }
            List<String> ids = new ArrayList<>();
            for (String token : args) {
                String id = resolveId(sm, token);
                if (id == null) {
                    Ansi.println(t, Ansi.error("No such session: " + token));
                    return;
                }
                if (!ids.contains(id)) {
                    ids.add(id);
                }
            }
            LineReader reader = ctx.readerRef().get();
            String answer = "n";
            if (reader != null) {
                try {
                    answer = reader.readLine(Ansi.warn("Delete " + ids.size() + " session(s)? (y/N) "));
                } catch (Exception e) {
                    answer = "n";
                }
            }
            if (answer == null || !answer.strip().equalsIgnoreCase("y")) {
                Ansi.println(t, Ansi.dim("Cancelled."));
                return;
            }
            sm.delete(ids);
            Ansi.println(t, Ansi.success("Deleted " + ids.size() + " session(s)."));
            sm.getCurrentSession().ifPresent(s ->
                    Ansi.println(t, Ansi.dim("Current session: " + s.name() + " [" + s.id() + "]")));
        }

        /** Resolve a token that is either a 1-based list index or a session id. */
        private String resolveId(SessionManager sm, String token) {
            if (token.matches("\\d+")) {
                List<Session> sessions = sm.list();
                int i = Integer.parseInt(token) - 1;
                if (i >= 0 && i < sessions.size()) {
                    return sessions.get(i).id();
                }
                return null;
            }
            return sm.list().stream().anyMatch(s -> s.id().equals(token)) ? token : null;
        }

        private void announce(Terminal t, String prefix, Session s) {
            Ansi.println(t, Ansi.success(prefix) + Ansi.info(s.name() + " [" + s.id() + "]"));
        }

        private String joinArgs() {
            return (args == null || args.length == 0) ? null : String.join(" ", args).strip();
        }

        private static void usage(Terminal t) {
            Ansi.println(t, Ansi.heading("/session actions:"));
            Ansi.println(t, Ansi.dim("  list                    list all sessions (newest first; * = current)"));
            Ansi.println(t, Ansi.dim("  new [name]              create a blank session and switch to it"));
            Ansi.println(t, Ansi.dim("  fork [name]             copy the current session into a new one"));
            Ansi.println(t, Ansi.dim("  switch <id|index>       switch to another session"));
            Ansi.println(t, Ansi.dim("  rename <name>           rename the current session"));
            Ansi.println(t, Ansi.dim("  clear [--with-memory]   clear conversation (optionally temp memory)"));
            Ansi.println(t, Ansi.dim("  delete <id|index>...    delete sessions (asks for confirmation)"));
        }
    }

    @Command(name = "/memory", description = "Toggle or show global + session memory loading")
    static final class MemoryCommand implements Runnable {
        private final ReplContext ctx;

        @Parameters(index = "0", arity = "0..1", paramLabel = "<on|off|status>")
        String action;

        MemoryCommand(ReplContext ctx) {
            this.ctx = ctx;
        }

        @Override
        public void run() {
            Terminal t = ctx.terminal();
            SessionManager sm = ctx.sessionManager();
            String act = action == null ? "status" : action.toLowerCase();
            switch (act) {
                case "on" -> {
                    sm.setMemoryEnabled(true);
                    Ansi.println(t, Ansi.success("Memory enabled."));
                }
                case "off" -> {
                    sm.setMemoryEnabled(false);
                    Ansi.println(t, Ansi.warn("Memory disabled (global + session memory will not be loaded)."));
                }
                case "status" -> Ansi.println(t, Ansi.info("Memory: ")
                        + (sm.isMemoryEnabled() ? Ansi.success("on") : Ansi.warn("off")));
                default -> Ansi.println(t, Ansi.error("Usage: /memory <on|off|status>"));
            }
        }
    }

    private static Msg userMsg(String text) {
        return Msg.builder().name("user").role(MsgRole.USER)
                .content(TextBlock.builder().text(text).build()).build();
    }

    private static String line(String label, String value) {
        return "  " + Ansi.bold(String.format("%-12s", label), Color.CYAN) + Ansi.info(value);
    }
}
