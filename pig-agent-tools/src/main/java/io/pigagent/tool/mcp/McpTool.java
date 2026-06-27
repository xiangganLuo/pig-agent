package io.pigagent.tool.mcp;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.pigagent.config.PigAgentConfig.AgentManagementConfig;
import io.pigagent.mcp.McpManager;
import io.pigagent.mcp.McpServerSpec;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 让 agent 自助管理 MCP 服务器的工具，受 D-SEC 安全门控制。
 *
 * <p>{@code list}/{@code test} 只读、始终允许；{@code add}/{@code remove} 受
 * {@code mcp.agent-management} 门控：默认全关；开启 add 时仅限 URL + host 白名单 + 人工确认，
 * 绝不允许 stdio/command。返回值对 {@code env}/{@code headers} 脱敏，避免凭据回显。
 */
public final class McpTool {

    private final McpManager mcp;
    private final Supplier<AgentManagementConfig> policy;
    private final McpConfirmer confirmer;

    public McpTool(McpManager mcp, Supplier<AgentManagementConfig> policy, McpConfirmer confirmer) {
        this.mcp = mcp;
        this.policy = policy;
        this.confirmer = confirmer;
    }

    @Tool(description = "列出已配置的 MCP 服务器及其实时健康（密钥已脱敏）")
    public String listMcpServers() {
        List<McpManager.ServerStatus> list = mcp.list();
        if (list.isEmpty()) {
            return "（无 MCP 服务器）";
        }
        StringBuilder sb = new StringBuilder();
        for (McpManager.ServerStatus s : list) {
            McpServerSpec spec = s.spec();
            sb.append("- ").append(spec.name()).append(" [").append(spec.transport()).append("] ")
                    .append(s.connected() ? "已连接" : "未连接")
                    .append(s.toolCount() >= 0 ? (" 工具数=" + s.toolCount()) : "")
                    .append(spec.isStdio() ? (" command=" + spec.command()) : (" url=" + spec.url()))
                    .append(redactKeys("env", spec.env()))
                    .append(redactKeys("headers", spec.headers()))
                    .append("\n");
        }
        return sb.toString().trim();
    }

    @Tool(description = "测试一个已配置 MCP 服务器的连通性")
    public String testMcpServer(@ToolParam(name = "name", description = "服务器名") String name) {
        McpServerSpec spec = mcp.findByName(name).orElse(null);
        if (spec == null) {
            return "无此 MCP 服务器: " + name;
        }
        McpManager.TestResult r = mcp.test(spec);
        return r.ok() ? ("OK，工具数=" + r.toolCount()) : ("失败: " + r.error());
    }

    @Tool(description = "请求添加一个 URL 类型的 MCP 服务器（受安全策略与人工确认控制）")
    public String addMcpServer(
            @ToolParam(name = "name", description = "服务器名（唯一）") String name,
            @ToolParam(name = "url", description = "MCP 服务器 URL（SSE）") String url) {
        McpServerSpec spec;
        try {
            spec = new McpServerSpec(name, null, List.of(), Map.of(), url, false, Map.of(), true);
        } catch (RuntimeException e) {
            return "参数无效: " + e.getMessage();
        }
        return applyAddPolicy(spec);
    }

    @Tool(description = "移除一个 MCP 服务器（默认禁止 agent 执行）")
    public String removeMcpServer(@ToolParam(name = "name", description = "服务器名") String name) {
        if (!policy.get().isAllowRemove()) {
            return "已拒绝：agent 移除 MCP 服务器被禁用（mcp.agent-management.allow-remove=false）";
        }
        try {
            mcp.remove(name);
            return "已移除 " + name;
        } catch (RuntimeException e) {
            return "移除失败: " + e.getMessage();
        }
    }

    /**
     * D-SEC 门（包私有，便于测试）：allow-add → 非 stdio → 仅 https → 无 userinfo →
     * host 白名单（大小写/末尾点归一化）→ 人工确认（fail-closed）→ 添加。
     *
     * <p>白名单是这套安全门的核心防线，故对 URL 做严格校验以防旁路：拒绝非 https
     * （避免明文 http 把 Authorization header 暴露上网）、拒绝带 userinfo 的
     * {@code https://allowed@evil/} 形态、host 两侧统一小写并去末尾点后再比对。
     */
    String applyAddPolicy(McpServerSpec spec) {
        AgentManagementConfig p = policy.get();
        if (!p.isAllowAdd()) {
            return "已拒绝：agent 添加 MCP 服务器被禁用（mcp.agent-management.allow-add=false）";
        }
        if (spec.isStdio()) {
            return "已拒绝：agent 只能添加 URL 类型的 MCP 服务器，不允许 stdio/command";
        }
        URI uri;
        try {
            uri = new URI(spec.url());
        } catch (Exception e) {
            return "已拒绝：URL 非法: " + spec.url();
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https")) {
            return "已拒绝：agent 只能添加 https 的 MCP 服务器（拒绝 "
                    + (scheme.isBlank() ? "缺少 scheme" : scheme) + "）";
        }
        if (uri.getUserInfo() != null) {
            return "已拒绝：URL 不得包含用户名/口令（userinfo），疑似白名单旁路";
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return "已拒绝：URL 缺少有效 host";
        }
        String normHost = normalizeHost(host);
        boolean allowed = p.getAllowedHosts().stream()
                .filter(h -> h != null)
                .anyMatch(h -> normalizeHost(h).equals(normHost));
        if (!allowed) {
            return "已拒绝：host '" + normHost + "' 不在白名单 mcp.agent-management.allowed-hosts";
        }
        boolean confirmed;
        try {
            confirmed = confirmer.confirm("agent 请求添加 MCP 服务器 '"
                    + spec.name() + "' (" + spec.url() + ")，是否允许？");
        } catch (RuntimeException e) {
            return "已取消：人工确认不可用，按拒绝处理";
        }
        if (!confirmed) {
            return "已取消：未获人工确认";
        }
        try {
            McpServerSpec added = mcp.add(spec);
            return "已添加 " + added.name() + "（" + added.url() + "）";
        } catch (RuntimeException e) {
            return "添加失败: " + e.getMessage();
        }
    }

    /** DNS 语义下 host 大小写不敏感、末尾点等价：统一小写并去掉末尾点后比对白名单。 */
    private static String normalizeHost(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        return h.endsWith(".") ? h.substring(0, h.length() - 1) : h;
    }

    private static String redactKeys(String label, Map<String, String> m) {
        if (m == null || m.isEmpty()) {
            return "";
        }
        String keys = m.keySet().stream().collect(Collectors.joining(","));
        return " " + label + "={" + keys + "=***}";
    }
}
