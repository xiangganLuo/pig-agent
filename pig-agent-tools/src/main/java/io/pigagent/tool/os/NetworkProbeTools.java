package io.pigagent.tool.os;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.pigagent.tool.contract.ToolErrors;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

/**
 * Host-independent, pure-Java network diagnostics: resolve a hostname ({@code resolveHost}) and probe
 * TCP reachability of a port ({@code checkPort}). Pure {@code java.net} — no {@code ping}/{@code
 * nslookup}/{@code nc}/{@code Test-NetConnection}.
 *
 * <p>Deliberately <b>not</b> SSRF-blocked (unlike the content-fetching {@code fetchUrl}): probing
 * {@code localhost:3000} or an internal service is the whole point of a diagnostic, and these tools
 * only open a socket / resolve a name — they retrieve no content. They are classified {@code NETWORK}
 * so the permission engine still gates them. Backs the {@code networking-diagnostics} built-in skill.
 */
public final class NetworkProbeTools {

    /** Default connect timeout when none is given. */
    static final int DEFAULT_TIMEOUT_MS = 3000;
    static final int MIN_TIMEOUT_MS = 100;
    static final int MAX_TIMEOUT_MS = 15000;
    private static final int MAX_ADDRESSES = 20;

    @Tool(description = "Resolve a hostname to its IP address(es) via DNS (pure Java InetAddress). "
            + "Use to check whether a name resolves and to what.")
    public String resolveHost(
            @ToolParam(name = "host", description = "Hostname to resolve, e.g. example.com") String host) {
        if (host == null || host.isBlank()) {
            return ToolErrors.message("empty host");
        }
        String target = host.trim();
        try {
            InetAddress[] addresses = InetAddress.getAllByName(target);
            List<String> ips = new ArrayList<>();
            for (InetAddress addr : addresses) {
                String ip = addr.getHostAddress();
                if (!ips.contains(ip)) {
                    ips.add(ip);
                }
                if (ips.size() >= MAX_ADDRESSES) {
                    break;
                }
            }
            return target + " resolves to:\n" + String.join("\n", ips);
        } catch (UnknownHostException e) {
            return "cannot resolve: " + target;
        }
    }

    @Tool(description = "Check whether a TCP port is open by attempting a connection (pure Java). "
            + "Reports open / closed / timeout / unresolved. Works for localhost and internal hosts.")
    public String checkPort(
            @ToolParam(name = "host", description = "Host to connect to, e.g. localhost") String host,
            @ToolParam(name = "port", description = "TCP port number (1-65535)") String port,
            @ToolParam(name = "timeout_ms", description = "Connect timeout in milliseconds "
                    + "(default 3000, clamped 100-15000)") String timeoutMs) {
        if (host == null || host.isBlank()) {
            return ToolErrors.message("empty host");
        }
        int p;
        try {
            p = Integer.parseInt(port == null ? "" : port.trim());
        } catch (NumberFormatException e) {
            return ToolErrors.message("invalid port: " + port);
        }
        if (p < 1 || p > 65535) {
            return ToolErrors.message("port out of range (1-65535): " + p);
        }
        int timeout = clampTimeout(timeoutMs);
        String target = host.trim() + ":" + p;
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host.trim(), p), timeout);
            return target + " is OPEN";
        } catch (SocketTimeoutException e) {
            return target + " TIMED OUT after " + timeout + "ms (filtered or host down)";
        } catch (ConnectException e) {
            return target + " is CLOSED (connection refused)";
        } catch (UnknownHostException e) {
            return target + " UNRESOLVED (host does not resolve)";
        } catch (IOException e) {
            return ToolErrors.message(e.getMessage());
        }
    }

    private static int clampTimeout(String timeoutMs) {
        if (timeoutMs == null || timeoutMs.isBlank()) {
            return DEFAULT_TIMEOUT_MS;
        }
        try {
            int t = Integer.parseInt(timeoutMs.trim());
            return Math.max(MIN_TIMEOUT_MS, Math.min(t, MAX_TIMEOUT_MS));
        } catch (NumberFormatException e) {
            return DEFAULT_TIMEOUT_MS;
        }
    }
}
