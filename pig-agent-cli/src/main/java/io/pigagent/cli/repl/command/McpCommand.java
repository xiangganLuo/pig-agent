package io.pigagent.cli.repl.command;

import io.pigagent.cli.Ansi;
import io.pigagent.cli.repl.ReplContext;
import io.pigagent.mcp.McpManager;
import io.pigagent.mcp.McpServerSpec;
import org.jline.reader.LineReader;
import org.jline.terminal.Terminal;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code /mcp} — manage MCP servers at runtime (list/add/remove/edit/enable/disable/test).
 *
 * <p>Operator-facing (full trust): unlike the agent-facing {@code McpTool}, the CLI may add
 * stdio/command servers too. Changes take effect live via {@link McpManager} and persist to
 * {@code mcp.json}. {@code list} shows real-time health (connected / tool count / failed).
 */
@Command(name = "/mcp", description = "Manage MCP servers (list|add|remove|edit|enable|disable|test)")
public final class McpCommand implements Runnable {

    private final ReplContext ctx;

    @Parameters(index = "0", arity = "0..1", paramLabel = "<action>",
            description = "list | add | remove | edit | enable | disable | test")
    String action;

    @Parameters(index = "1..*", paramLabel = "<args>")
    String[] args;

    public McpCommand(ReplContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public void run() {
        Terminal t = ctx.terminal();
        McpManager mcp = ctx.mcpManager();
        String act = action == null ? "list" : action.toLowerCase();
        switch (act) {
            case "list" -> listServers(t, mcp);
            case "add" -> addServer(t, mcp);
            case "remove", "delete" -> removeServer(t, mcp);
            case "edit" -> editServer(t, mcp);
            case "enable" -> toggleServer(t, mcp, true);
            case "disable" -> toggleServer(t, mcp, false);
            case "test" -> testServer(t, mcp);
            case "help" -> usage(t);
            default -> {
                Ansi.println(t, Ansi.error("Unknown action: " + act));
                usage(t);
            }
        }
    }

    private void listServers(Terminal t, McpManager mcp) {
        List<McpManager.ServerStatus> list = mcp.list();
        Ansi.println(t, Ansi.heading("MCP servers:"));
        if (list.isEmpty()) {
            Ansi.println(t, Ansi.dim("  (none — use /mcp add)"));
            return;
        }
        int idx = 1;
        for (McpManager.ServerStatus s : list) {
            McpServerSpec spec = s.spec();
            String health = !spec.enabled() ? Ansi.dim("disabled")
                    : s.connected() ? Ansi.success("connected (" + s.toolCount() + " tools)")
                    : Ansi.error("not connected");
            String target = spec.isStdio() ? ("cmd=" + spec.command()) : ("url=" + spec.url());
            Ansi.println(t, String.format("  %2d) ", idx) + Ansi.info(spec.name())
                    + Ansi.dim(" [" + spec.transport() + "] " + target + "  ") + health);
            idx++;
        }
    }

    private void addServer(Terminal t, McpManager mcp) {
        LineReader reader = ctx.readerRef().get();
        if (reader == null) {
            Ansi.println(t, Ansi.error("Interactive input is unavailable."));
            return;
        }
        String name = reader.readLine("Server name: ").trim();
        if (name.isBlank()) {
            Ansi.println(t, Ansi.error("Name is required."));
            return;
        }
        if (mcp.findByName(name).isPresent()) {
            Ansi.println(t, Ansi.error("A server named '" + name + "' already exists."));
            return;
        }
        String type = reader.readLine("Transport [stdio|sse|http] (default sse): ").trim().toLowerCase();
        if (type.isBlank()) {
            type = "sse";
        }
        McpServerSpec spec;
        try {
            spec = buildSpec(reader, name, type);
        } catch (RuntimeException e) {
            Ansi.println(t, Ansi.error("Invalid input: " + e.getMessage()));
            return;
        }
        if (spec == null) {
            return;
        }
        Ansi.println(t, Ansi.dim("Testing " + spec.label() + " ..."));
        McpManager.TestResult test = mcp.test(spec);
        if (!test.ok()) {
            Ansi.println(t, Ansi.error("Test failed: " + test.error() + " (not added)"));
            return;
        }
        try {
            mcp.add(spec);
            Ansi.println(t, Ansi.success("Added " + spec.name() + " (" + test.toolCount() + " tools)."));
        } catch (RuntimeException e) {
            Ansi.println(t, Ansi.error("Add failed: " + e.getMessage()));
        }
    }

    private McpServerSpec buildSpec(LineReader reader, String name, String type) {
        if (type.equals("stdio")) {
            String command = reader.readLine("Command: ").trim();
            if (command.isBlank()) {
                throw new IllegalArgumentException("command is required for stdio");
            }
            String argLine = reader.readLine("Args (space-separated, optional): ").trim();
            List<String> argList = argLine.isBlank() ? List.of() : List.of(argLine.split("\\s+"));
            Map<String, String> env = readKeyVals(reader, "Env");
            return new McpServerSpec(name, command, argList, env, null, false, Map.of(), true);
        }
        String url = reader.readLine("URL: ").trim();
        if (url.isBlank()) {
            throw new IllegalArgumentException("url is required");
        }
        Map<String, String> headers = readKeyVals(reader, "Header");
        boolean streamable = type.equals("http");
        return new McpServerSpec(name, null, List.of(), Map.of(), url, streamable, headers, true);
    }

    private Map<String, String> readKeyVals(LineReader reader, String label) {
        Map<String, String> out = new LinkedHashMap<>();
        // Two-step entry so a sensitive value can be masked: the key is read visibly, then the value
        // is read masked when the key names a credential (token/key/authorization/secret), else visibly.
        // Resolves security review M-1 (was: whole KEY=VALUE line echoed, leaking tokens to scrollback).
        while (true) {
            String key = reader.readLine(label + " key (blank to finish): ").trim();
            if (key.isBlank()) {
                break;
            }
            String prompt = label + " value for '" + key + "': ";
            String value = isSensitiveKey(key)
                    ? reader.readLine(prompt, '*').trim()
                    : reader.readLine(prompt).trim();
            out.put(key, value);
        }
        return out;
    }

    /** Keys whose values are credentials and MUST be masked on input. */
    static boolean isSensitiveKey(String key) {
        String k = key.toLowerCase(java.util.Locale.ROOT);
        return k.contains("token") || k.contains("key") || k.contains("secret")
                || k.contains("authorization") || k.contains("password") || k.contains("passwd");
    }

    private void removeServer(Terminal t, McpManager mcp) {
        String name = resolveName(mcp, 0);
        if (name == null) {
            Ansi.println(t, Ansi.warn("Usage: /mcp remove <name|index>"));
            return;
        }
        LineReader reader = ctx.readerRef().get();
        String answer = reader != null ? reader.readLine(Ansi.warn("Remove MCP server " + name + "? (y/N) ")) : "n";
        if (answer == null || !answer.strip().equalsIgnoreCase("y")) {
            Ansi.println(t, Ansi.dim("Cancelled."));
            return;
        }
        try {
            mcp.remove(name);
            Ansi.println(t, Ansi.success("Removed " + name + "."));
        } catch (RuntimeException e) {
            Ansi.println(t, Ansi.error("Remove failed: " + e.getMessage()));
        }
    }

    private void editServer(Terminal t, McpManager mcp) {
        String name = resolveName(mcp, 0);
        if (name == null) {
            Ansi.println(t, Ansi.warn("Usage: /mcp edit <name|index>"));
            return;
        }
        McpServerSpec old = mcp.findByName(name).orElse(null);
        if (old == null) {
            Ansi.println(t, Ansi.error("No such server: " + name));
            return;
        }
        LineReader reader = ctx.readerRef().get();
        if (reader == null) {
            Ansi.println(t, Ansi.error("Interactive input is unavailable."));
            return;
        }
        String type = old.isStdio() ? "stdio" : (old.streamableHttp() ? "http" : "sse");
        Ansi.println(t, Ansi.dim("Editing " + old.label() + " [" + type + "] — re-enter its settings."));
        McpServerSpec updated;
        try {
            updated = buildSpec(reader, name, type);
        } catch (RuntimeException e) {
            Ansi.println(t, Ansi.error("Invalid input: " + e.getMessage()));
            return;
        }
        try {
            mcp.edit(updated);
            Ansi.println(t, Ansi.success("Updated " + name + " (tested before applying)."));
        } catch (RuntimeException e) {
            Ansi.println(t, Ansi.error("Edit failed (kept old config): " + e.getMessage()));
        }
    }

    private void toggleServer(Terminal t, McpManager mcp, boolean enable) {
        String name = resolveName(mcp, 0);
        if (name == null) {
            Ansi.println(t, Ansi.warn("Usage: /mcp " + (enable ? "enable" : "disable") + " <name|index>"));
            return;
        }
        try {
            if (enable) {
                mcp.enable(name);
                Ansi.println(t, Ansi.success("Enabled and connected " + name + "."));
            } else {
                mcp.disable(name);
                Ansi.println(t, Ansi.warn("Disabled " + name + " (tools unregistered, config kept)."));
            }
        } catch (RuntimeException e) {
            Ansi.println(t, Ansi.error("Failed: " + e.getMessage()));
        }
    }

    private void testServer(Terminal t, McpManager mcp) {
        String name = resolveName(mcp, 0);
        if (name == null) {
            Ansi.println(t, Ansi.warn("Usage: /mcp test <name|index>"));
            return;
        }
        McpServerSpec spec = mcp.findByName(name).orElse(null);
        if (spec == null) {
            Ansi.println(t, Ansi.error("No such server: " + name));
            return;
        }
        Ansi.println(t, Ansi.dim("Testing " + spec.label() + " ..."));
        McpManager.TestResult r = mcp.test(spec);
        Ansi.println(t, r.ok() ? Ansi.success("OK — " + r.toolCount() + " tools.")
                : Ansi.error("Failed: " + r.error()));
    }

    /** Resolve args[i] as a 1-based index into the live list or a server name. */
    private String resolveName(McpManager mcp, int i) {
        if (args == null || args.length <= i) {
            return null;
        }
        String token = args[i];
        List<McpServerSpec> specs = new ArrayList<>();
        mcp.list().forEach(s -> specs.add(s.spec()));
        if (token.matches("\\d+")) {
            int idx = Integer.parseInt(token) - 1;
            return (idx >= 0 && idx < specs.size()) ? specs.get(idx).name() : null;
        }
        return specs.stream().anyMatch(s -> s.name().equals(token)) ? token : null;
    }

    private static void usage(Terminal t) {
        Ansi.println(t, Ansi.heading("/mcp actions:"));
        Ansi.println(t, Ansi.dim("  list                    list servers with live health (* tools / failed)"));
        Ansi.println(t, Ansi.dim("  add                     add a server (stdio/sse/http) + connectivity test"));
        Ansi.println(t, Ansi.dim("  remove <name|index>     remove a server (asks to confirm)"));
        Ansi.println(t, Ansi.dim("  edit <name|index>       re-enter settings (tested before applying)"));
        Ansi.println(t, Ansi.dim("  enable <name|index>     enable + connect a server"));
        Ansi.println(t, Ansi.dim("  disable <name|index>    disable (unregister tools, keep config)"));
        Ansi.println(t, Ansi.dim("  test <name|index>       test connectivity without changing anything"));
    }
}
