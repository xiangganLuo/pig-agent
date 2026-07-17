package io.pigagent.cli.repl;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.pigagent.cli.Ansi;
import io.pigagent.cli.repl.command.AgentCommand;
import io.pigagent.cli.repl.command.McpCommand;
import io.pigagent.cli.repl.command.NotifyCommand;
import io.pigagent.cli.repl.command.PermissionCommand;
import io.pigagent.cli.repl.command.PlanCommand;
import io.pigagent.config.PigAgentConfig;
import io.pigagent.core.compression.CompressionStatus;
import io.pigagent.core.protocol.ModelProtocol;
import io.pigagent.model.ModelManager;
import io.pigagent.model.StoredModel;
import io.pigagent.session.Session;
import io.pigagent.session.SessionManager;
import io.pigagent.tool.availability.ToolAvailabilityReport;
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
        cmd.addSubcommand(new ProtocolsCommand(ctx));
        cmd.addSubcommand(new ModelCommand(ctx));
        cmd.addSubcommand(new AgentCommand(ctx));
        cmd.addSubcommand(new ChannelsCommand(ctx));
        cmd.addSubcommand(new SessionCommand(ctx));
        cmd.addSubcommand(new McpCommand(ctx));
        cmd.addSubcommand(new PermissionCommand(ctx));
        cmd.addSubcommand(new PlanCommand(ctx));
        cmd.addSubcommand(new MemoryCommand(ctx));
        cmd.addSubcommand(new CompressCommand(ctx));
        cmd.addSubcommand(new NotifyCommand(ctx));
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
            entry(t, "/protocols", "List all model protocol types");
            entry(t, "/model <action>", "Manage models (list|add|switch|edit|delete)");
            entry(t, "/agent <action>", "Manage agents (list|use|new|model)");
            entry(t, "/channels", "Show connected channels and status");
            entry(t, "/session <action>", "Manage sessions (list|new|fork|switch|rename|clear|delete)");
            entry(t, "/mcp <action>", "Manage MCP servers (list|add|remove|edit|enable|disable|test)");
            entry(t, "/permission <action>", "Tool permissions (status|mode|allow|revoke|reset|list)");
            entry(t, "/plan <action>", "Native Plan Mode (enter|exit|status)");
            entry(t, "/memory <on|off>", "Toggle/show native long-term memory (MEMORY.md)");
            entry(t, "/compress <action>", "Context compression (now|status|off|on)");
            entry(t, "/notify <action>", "Proactive outreach (status|test)");
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

    @Command(name = "/protocols", description = "List all model protocol types")
    static final class ProtocolsCommand implements Runnable {
        private final ReplContext ctx;

        ProtocolsCommand(ReplContext ctx) {
            this.ctx = ctx;
        }

        @Override
        public void run() {
            String current = ctx.modelManager().getCurrentModel()
                    .map(StoredModel::protocolId).orElse(null);
            Terminal t = ctx.terminal();
            Ansi.println(t, Ansi.heading("Protocol types:"));
            for (ModelProtocol p : ctx.registry().getAllProtocols()) {
                String marker = p.protocolId().equals(current) ? Ansi.bold(" (active)", Color.GREEN) : "";
                Ansi.println(t, String.format("  %-12s  %-20s", p.protocolId(), p.displayName())
                        + Ansi.dim("  " + p.description()) + marker);
            }
        }
    }

    @Command(name = "/model", description = "Manage models (list|add|switch|edit|delete)")
    static final class ModelCommand implements Runnable {
        private final ReplContext ctx;

        @Parameters(index = "0", arity = "0..1", paramLabel = "<action>",
                description = "list | add | switch | edit | delete")
        String action;

        @Parameters(index = "1..*", paramLabel = "<args>")
        String[] args;

        @Option(names = "--global", description = "with 'switch': set as the global default (permanent)")
        boolean global;

        ModelCommand(ReplContext ctx) {
            this.ctx = ctx;
        }

        @Override
        public void run() {
            Terminal t = ctx.terminal();
            ModelManager mm = ctx.modelManager();
            if (action == null) {
                ModelSelection.interactive(ctx);
                return;
            }
            String act = action.toLowerCase();
            switch (act) {
                case "list" -> listModels(t, mm);
                case "add" -> addModel(t, mm);
                case "switch" -> switchModel(t, mm);
                case "edit" -> editModel(t, mm);
                case "delete" -> deleteModel(t, mm);
                case "help" -> usage(t);
                default -> {
                    Ansi.println(t, Ansi.error("Unknown action: " + act));
                    usage(t);
                }
            }
        }

        private void listModels(Terminal t, ModelManager mm) {
            List<StoredModel> models = mm.list();
            Ansi.println(t, Ansi.heading("Models:"));
            if (models.isEmpty()) {
                Ansi.println(t, Ansi.dim("  (none — use /model add)"));
                return;
            }
            String def = mm.getDefaultId();
            String cur = mm.getCurrentModelId();
            int idx = 1;
            for (StoredModel m : models) {
                String marker = m.id().equals(cur) ? Ansi.bold(" *", Color.GREEN) : "  ";
                String tag = m.id().equals(def) ? Ansi.success(" [default]") : "";
                Ansi.println(t, String.format("  %2d)", idx) + marker + " " + Ansi.info(m.label())
                        + tag + Ansi.dim(" [" + m.id() + "]"));
                idx++;
            }
        }

        private void addModel(Terminal t, ModelManager mm) {
            LineReader reader = ctx.readerRef().get();
            if (reader == null) {
                Ansi.println(t, Ansi.error("Interactive input is unavailable."));
                return;
            }
            List<ModelProtocol> protocols = ctx.registry().getAllProtocols();
            Ansi.println(t, Ansi.heading("Add model — choose a protocol:"));
            for (int i = 0; i < protocols.size(); i++) {
                ModelProtocol p = protocols.get(i);
                Ansi.println(t, String.format("  %d) %-18s %s", i + 1, p.displayName(), p.description()));
            }
            ModelProtocol protocol;
            try {
                int idx = Integer.parseInt(reader.readLine("Protocol number: ").trim()) - 1;
                protocol = protocols.get(idx);
            } catch (Exception e) {
                Ansi.println(t, Ansi.error("Invalid selection."));
                return;
            }
            String apiKey = null;
            if (protocol.requiresApiKey()) {
                apiKey = reader.readLine("API key: ", '*').trim(); // masked: no echo / scrollback
                if (apiKey.isBlank()) {
                    Ansi.println(t, Ansi.error("API key is required."));
                    return;
                }
            }
            String baseUrl = null;
            if (protocol.supportsBaseUrl()) {
                baseUrl = reader.readLine("Base URL (optional): ").trim();
                if (baseUrl.isBlank()) {
                    baseUrl = null;
                }
            }
            String modelName = reader.readLine("Model name [" + protocol.defaultModelName() + "]: ").trim();
            if (modelName.isBlank()) {
                modelName = protocol.defaultModelName();
            }
            StoredModel m = StoredModel.create(protocol.protocolId(), apiKey, baseUrl, modelName);
            Ansi.println(t, Ansi.dim("Testing " + m.label() + " ..."));
            ModelManager.TestResult test = mm.test(m);
            if (!test.ok()) {
                Ansi.println(t, Ansi.error("Test failed: " + test.error() + " (not saved)"));
                return;
            }
            mm.add(m);
            Ansi.println(t, Ansi.success("Added " + m.label() + " [" + m.id() + "]"));
        }

        private void switchModel(Terminal t, ModelManager mm) {
            if (args == null || args.length == 0) {
                Ansi.println(t, Ansi.warn("Usage: /model switch <id|index> [--global]"));
                return;
            }
            String id = resolveId(mm, args[0]);
            StoredModel m = id == null ? null : mm.findById(id).orElse(null);
            if (m == null) {
                Ansi.println(t, Ansi.error("No such model: " + args[0]));
                return;
            }
            ModelSelection.apply(t, mm, ctx.sessionManager(), m, global);
        }

        private void editModel(Terminal t, ModelManager mm) {
            if (args == null || args.length == 0) {
                Ansi.println(t, Ansi.warn("Usage: /model edit <id|index>"));
                return;
            }
            LineReader reader = ctx.readerRef().get();
            if (reader == null) {
                Ansi.println(t, Ansi.error("Interactive input is unavailable."));
                return;
            }
            String id = resolveId(mm, args[0]);
            StoredModel m = id == null ? null : mm.findById(id).orElse(null);
            if (m == null) {
                Ansi.println(t, Ansi.error("No such model: " + args[0]));
                return;
            }
            Ansi.println(t, Ansi.dim("Editing " + m.label() + " (press Enter to keep a value)"));
            String key = reader.readLine("API key [keep]: ", '*').trim(); // masked: no echo / scrollback
            String url = reader.readLine("Base URL [keep]: ").trim();
            String name = reader.readLine("Model name [" + m.modelName() + "]: ").trim();
            StoredModel updated = m;
            if (!key.isBlank()) {
                updated = updated.withApiKey(key);
            }
            if (!url.isBlank()) {
                updated = updated.withBaseUrl(url);
            }
            if (!name.isBlank()) {
                updated = updated.withModelName(name);
            }
            mm.edit(updated);
            Ansi.println(t, Ansi.success("Updated " + updated.label() + "."));
            if (updated.id().equals(mm.getCurrentModelId())) {
                Ansi.println(t, Ansi.dim("Re-switch to this model or restart for changes to take effect."));
            }
        }

        private void deleteModel(Terminal t, ModelManager mm) {
            if (args == null || args.length == 0) {
                Ansi.println(t, Ansi.warn("Usage: /model delete <id|index>"));
                return;
            }
            String id = resolveId(mm, args[0]);
            if (id == null) {
                Ansi.println(t, Ansi.error("No such model: " + args[0]));
                return;
            }
            if (id.equals(mm.getCurrentModelId())) {
                Ansi.println(t, Ansi.error("Cannot delete the model currently in use."));
                return;
            }
            LineReader reader = ctx.readerRef().get();
            String answer = reader != null ? reader.readLine(Ansi.warn("Delete model " + id + "? (y/N) ")) : "n";
            if (answer == null || !answer.strip().equalsIgnoreCase("y")) {
                Ansi.println(t, Ansi.dim("Cancelled."));
                return;
            }
            mm.delete(id);
            Ansi.println(t, Ansi.success("Deleted " + id + "."));
        }

        private String resolveId(ModelManager mm, String token) {
            List<StoredModel> models = mm.list();
            if (token.matches("\\d+")) {
                int i = Integer.parseInt(token) - 1;
                return (i >= 0 && i < models.size()) ? models.get(i).id() : null;
            }
            return models.stream().anyMatch(m -> m.id().equals(token)) ? token : null;
        }

        private static void usage(Terminal t) {
            Ansi.println(t, Ansi.heading("/model actions:"));
            Ansi.println(t, Ansi.dim("  list                       list saved models (* = active, [default])"));
            Ansi.println(t, Ansi.dim("  add                        add a model (provider, key, url, name) + test"));
            Ansi.println(t, Ansi.dim("  switch <id|index>          use this model for the current session"));
            Ansi.println(t, Ansi.dim("  switch <id|index> --global set as the global default (all sessions)"));
            Ansi.println(t, Ansi.dim("  edit <id|index>            change key / url / model name"));
            Ansi.println(t, Ansi.dim("  delete <id|index>          delete a saved model (asks to confirm)"));
        }
    }

    @Command(name = "/compress", description = "Context compression (now|status|off|on)")
    static final class CompressCommand implements Runnable {
        private final ReplContext ctx;

        @Parameters(index = "0", arity = "0..1", paramLabel = "<now|status|off|on>")
        String action;

        CompressCommand(ReplContext ctx) {
            this.ctx = ctx;
        }

        @Override
        public void run() {
            Terminal t = ctx.terminal();
            String sid = ctx.sessionManager().getCurrentSessionId();
            String act = action == null ? "status" : action.toLowerCase();
            switch (act) {
                case "now" -> {
                    boolean did = ctx.compressionService().compressNow(sid);
                    Ansi.println(t, did ? Ansi.success("Context compressed.")
                            : Ansi.dim("Nothing to compress yet."));
                }
                case "status" -> {
                    CompressionStatus s = ctx.compressionService().status(sid);
                    Ansi.println(t, Ansi.heading("Compression"));
                    Ansi.println(t, line("Auto", s.enabled() ? "on" : "off"));
                    Ansi.println(t, line("Tokens", s.estimatedTokens() + " / " + s.budgetTokens()
                            + " (trigger " + s.thresholdTokens() + ")"));
                    Ansi.println(t, line("Budget", "pinned " + s.budget().pinnedTokens()
                            + " · recent " + s.budget().recentTokens()
                            + " · summarized " + s.budget().summarizedTokens()));
                    Ansi.println(t, line("Messages", String.valueOf(s.messageCount())));
                    Ansi.println(t, line("Last", s.lastCompressedEpochMs() == 0 ? "never"
                            : java.time.Instant.ofEpochMilli(s.lastCompressedEpochMs()).toString()));
                }
                case "off" -> {
                    ctx.compressionService().setEnabled(sid, false);
                    Ansi.println(t, Ansi.warn("Auto-compression off for this session."));
                }
                case "on" -> {
                    ctx.compressionService().setEnabled(sid, true);
                    Ansi.println(t, Ansi.success("Auto-compression on for this session."));
                }
                default -> Ansi.println(t, Ansi.error("Usage: /compress <now|status|off|on>"));
            }
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
            Terminal t = ctx.terminal();
            long runningChannels = ctx.bridges().stream().filter(b -> b.getChannel().isRunning()).count();
            SessionManager sm = ctx.sessionManager();
            String sessionLabel = sm.getCurrentSession()
                    .map(s -> s.name() + " [" + s.id() + "]")
                    .orElse("(none)");
            String modelLabel = ctx.modelManager().getCurrentModel()
                    .map(StoredModel::label)
                    .orElse("(none)");
            Ansi.println(t, Ansi.heading("Agent Status"));
            Ansi.println(t, line("Agent", ctx.agent().getAgentName()));
            Ansi.println(t, line("Model", modelLabel));
            Ansi.println(t, line("Session", sessionLabel));
            Ansi.println(t, line("Memory", sm.isMemoryEnabled() ? "on" : "off"));
            Ansi.println(t, line("Perms", ctx.configManager().getConfig().getPermissions()
                    .resolveMode().name().toLowerCase()));
            Ansi.println(t, line("Channels", runningChannels + " running"));
            long mcpConnected = ctx.mcpManager().list().stream().filter(s -> s.connected()).count();
            Ansi.println(t, line("MCP", ctx.mcpManager().list().size() + " configured, "
                    + mcpConnected + " connected"));
            renderHiddenTools(t, ctx.availabilityReport());
        }

        /**
         * Show tools hidden from the model because their availability preconditions are unmet, with the
         * missing-prerequisite reason (a variable name, never a credential value).
         */
        private static void renderHiddenTools(Terminal t, ToolAvailabilityReport report) {
            List<ToolAvailabilityReport.Hidden> hidden =
                    report == null ? List.of() : report.hidden();
            if (hidden.isEmpty()) {
                Ansi.println(t, line("Tools", "all available"));
                return;
            }
            Ansi.println(t, line("Tools", hidden.size() + " hidden (unavailable)"));
            for (ToolAvailabilityReport.Hidden h : hidden) {
                Ansi.println(t, "    " + Ansi.dim(h.toolName() + " — " + h.reason()));
            }
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

    @Command(name = "/memory", description = "Toggle or show native long-term memory (MEMORY.md)")
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
                    Ansi.println(t, Ansi.success("Memory enabled (native MEMORY.md flush + injection)."));
                }
                case "off" -> {
                    sm.setMemoryEnabled(false);
                    Ansi.println(t, Ansi.warn("Memory disabled (no MEMORY.md injection, no flush)."));
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
