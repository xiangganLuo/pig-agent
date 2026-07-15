package io.pigagent.mcp;

import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.modelcontextprotocol.spec.McpSchema;
import io.pigagent.config.PigAgentConfig.McpConfig;
import io.pigagent.config.PigAgentConfig.McpServerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * MCP 客户端的运行时协调者：增/删/改/启停/连通测试，实时把工具注册进 {@link Toolkit} 或注销。
 *
 * <p>并发（E1 细粒度锁）：网络/进程连接（{@code connect} / {@code listTools}）在锁外；
 * 仅"碰撞检查 + register + 写 map"的短临界区在 {@code synchronized(this)} 内，避免慢连接冻结
 * 其他 MCP 操作或对话回合。工具命名空间扁平（D-NS），新增时若工具名与已注册冲突即拒绝。
 *
 * <p>持久化委托 {@link McpStore}（{@code mcp.json}）；{@code application.yaml} 的旧
 * {@code mcp.servers} 在首启一次性导入。
 */
public final class McpManager {

    private static final Logger log = LoggerFactory.getLogger(McpManager.class);
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    private Toolkit toolkit;
    private McpStore store;
    /** name -> 已连接的 client，受 {@code this} 锁保护。 */
    private final Map<String, McpClientWrapper> clients = new LinkedHashMap<>();
    /** 正在添加/连接中的 name（占位防并发重名，L-1），受 {@code this} 锁保护。 */
    private final Set<String> pending = new HashSet<>();
    /**
     * 可选（deferred-tools）：服务器名 → tool-group 名。为 null（默认）时零行为变化——MCP 工具照旧
     * 未分组注册。非 null 时 attach 把该服务器的工具注册进 {@code active} 分组，供 {@code DeferredToolGate}
     * 按需停用以隐藏（延迟）；这样延迟对 MCP 安全（保留 {@code mcpClientName}，{@code removeMcpClient} 照常）。
     */
    private java.util.function.Function<String, String> toolGroupNamer;
    /** attach 时创建过的 tool-group 名（供上层枚举 MCP 工具→分组），受 {@code this} 锁保护。 */
    private final Set<String> managedGroups = new java.util.LinkedHashSet<>();

    /**
     * 启用 MCP 工具分组（deferred-tools）：注入「服务器名 → 分组名」函数。MUST 在 {@link #initialize} 前调用。
     * 传 null 恢复默认（不分组）。
     */
    public void setToolGroupNamer(java.util.function.Function<String, String> namer) {
        this.toolGroupNamer = namer;
    }

    /** attach 期创建的 MCP tool-group 名快照（未启用分组时为空）。 */
    public synchronized Set<String> managedToolGroups() {
        return new java.util.LinkedHashSet<>(managedGroups);
    }

    /** 连通测试结果。 */
    public record TestResult(boolean ok, String error, int toolCount) {
        public static TestResult success(int toolCount) {
            return new TestResult(true, null, toolCount);
        }

        public static TestResult failure(String error) {
            return new TestResult(false, error, 0);
        }
    }

    /** 列表项：配置 + 实时健康。 */
    public record ServerStatus(McpServerSpec spec, boolean connected, int toolCount) {
    }

    /** 取代旧 {@code connectAll}：首启导入 {@code application.yaml}，再尽力连接所有 enabled 服务器。 */
    public void initialize(McpStore store, Toolkit toolkit, McpConfig legacy) {
        this.store = store;
        this.toolkit = toolkit;

        if (store.findAll().isEmpty() && legacy != null
                && legacy.getServers() != null && !legacy.getServers().isEmpty()) {
            int imported = 0;
            for (Map.Entry<String, McpServerConfig> e : legacy.getServers().entrySet()) {
                try {
                    store.save(fromLegacy(e.getKey(), e.getValue()));
                    imported++;
                } catch (RuntimeException ex) {
                    log.warn("跳过导入 '{}': {}", e.getKey(), ex.getMessage());
                }
            }
            if (imported > 0) {
                log.info("已从 application.yaml 导入 {} 个服务器到 mcp.json（此后 mcp.json 为唯一真源）。", imported);
            }
        }

        for (McpServerSpec spec : store.findAll()) {
            if (!spec.enabled()) {
                continue;
            }
            try {
                attach(spec);
                log.info("已连接 MCP: {}", spec.name());
            } catch (RuntimeException ex) {
                log.warn("连接 MCP 失败 {}: {}", spec.name(), ex.getMessage());
            }
        }
    }

    /** 添加并实时生效：原子占名(临界区) → 连接(I/O) → 连通测试 → 碰撞检查+注册(临界区) → 持久化 → 释放占名。 */
    public McpServerSpec add(McpServerSpec spec) {
        reserveName(spec.name());
        try {
            attach(spec);
            store.save(spec);
            return spec;
        } finally {
            synchronized (this) {
                pending.remove(spec.name());
            }
        }
    }

    /**
     * 原子占名（L-1 TOCTOU 修复）：在同一临界区内查 store/clients/pending，全部空缺才登记 pending。
     * 与 attach 的锁外 I/O 配合，把"查重 → 保存"之间原本非原子的窗口收敛为占名 + 释放两段临界区。
     */
    private synchronized void reserveName(String name) {
        if (store.findByName(name).isPresent()) {
            throw new IllegalStateException("已存在同名 MCP 服务器: " + name);
        }
        if (clients.containsKey(name)) {
            throw new IllegalStateException("已连接同名 MCP 服务器: " + name);
        }
        if (!pending.add(name)) {
            throw new IllegalStateException("同名 MCP 服务器正在添加中: " + name);
        }
    }

    /** 实时移除：注销工具(临界区) → 关闭 client(锁外) → 删存储。 */
    public void remove(String name) {
        McpClientWrapper toClose;
        synchronized (this) {
            toClose = clients.remove(name);
            if (toClose != null) {
                toolkit.removeMcpClient(name).block(DEFAULT_TIMEOUT);
            }
        }
        if (toClose != null) {
            safeClose(toClose);
        }
        store.deleteByName(name);
    }

    /**
     * 编辑：先连通测试新配置（不动旧的）；通过后移除旧的再加新的（非破坏性）。
     *
     * <p>L-3 修复：{@code test()} 不查工具名冲突，{@code attach()} 才查；若 remove 旧的之后 add
     * 因冲突失败，旧配置本会被删除且无回滚。故先快照旧 spec 与其连接状态，add 失败时恢复旧 spec
     * 并（若原先已连接）重连，避免配置丢失。
     */
    public McpServerSpec edit(McpServerSpec newSpec) {
        TestResult t = test(newSpec);
        if (!t.ok()) {
            throw new IllegalStateException("新配置不可用，保持原配置: " + t.error());
        }
        McpServerSpec oldSpec = store.findByName(newSpec.name()).orElse(null);
        boolean wasConnected;
        synchronized (this) {
            wasConnected = clients.containsKey(newSpec.name());
        }
        remove(newSpec.name());
        try {
            return add(newSpec);
        } catch (RuntimeException addFailure) {
            rollbackEdit(oldSpec, wasConnected, addFailure);
            throw new IllegalStateException("编辑失败，已回滚旧配置: " + addFailure.getMessage(), addFailure);
        }
    }

    /** L-3 回滚：恢复旧 spec 到 store，并在原先已连接时尽力重连；回滚本身失败则合并报错。 */
    private void rollbackEdit(McpServerSpec oldSpec, boolean wasConnected, RuntimeException addFailure) {
        if (oldSpec == null) {
            return;
        }
        try {
            store.save(oldSpec);
            if (wasConnected && oldSpec.enabled()) {
                attach(oldSpec);
            }
        } catch (RuntimeException rollbackFailure) {
            throw new IllegalStateException("编辑失败且回滚旧配置也失败: "
                    + addFailure.getMessage() + "；回滚错误: " + rollbackFailure.getMessage(), addFailure);
        }
    }

    /** 启用并连接；持久化 enabled=true。 */
    public void enable(String name) {
        McpServerSpec spec = store.findByName(name)
                .orElseThrow(() -> new IllegalStateException("无此 MCP 服务器: " + name));
        boolean connected;
        synchronized (this) {
            connected = clients.containsKey(name);
        }
        if (!connected) {
            attach(spec);
        }
        store.save(spec.withEnabled(true));
    }

    /** 停用并注销工具；持久化 enabled=false（保留配置）。 */
    public void disable(String name) {
        Optional<McpServerSpec> spec = store.findByName(name);
        McpClientWrapper toClose;
        synchronized (this) {
            toClose = clients.remove(name);
            if (toClose != null) {
                toolkit.removeMcpClient(name).block(DEFAULT_TIMEOUT);
            }
        }
        if (toClose != null) {
            safeClose(toClose);
        }
        spec.ifPresent(s -> store.save(s.withEnabled(false)));
    }

    /** 连通测试：构造临时 client、连接、数工具、关闭。对坏服务器永不抛出。 */
    public TestResult test(McpServerSpec spec) {
        McpClientWrapper client = null;
        try {
            client = connect(spec);
            int n = toolNamesOf(client).size();
            return TestResult.success(n);
        } catch (Exception e) {
            return TestResult.failure(e.getMessage() == null ? e.toString() : e.getMessage());
        } finally {
            if (client != null) {
                safeClose(client);
            }
        }
    }

    /** 存储中的所有服务器 + 实时健康（已连接/工具数；未连接时 toolCount = -1）。 */
    public List<ServerStatus> list() {
        List<ServerStatus> out = new ArrayList<>();
        for (McpServerSpec spec : store.findAll()) {
            McpClientWrapper c;
            synchronized (this) {
                c = clients.get(spec.name());
            }
            boolean connected = c != null;
            int n = -1;
            if (connected) {
                try {
                    n = toolNamesOf(c).size();
                } catch (Exception ignore) {
                    n = -1;
                }
            }
            out.add(new ServerStatus(spec, connected, n));
        }
        return out;
    }

    public Optional<McpServerSpec> findByName(String name) {
        return store.findByName(name);
    }

    /** 关闭所有连接（关停时调用）。 */
    public void closeAll() {
        List<McpClientWrapper> snapshot;
        synchronized (this) {
            snapshot = new ArrayList<>(clients.values());
            clients.clear();
        }
        for (McpClientWrapper c : snapshot) {
            safeClose(c);
        }
    }

    // --- 内部 ---

    /** 连接(I/O,锁外) + 列工具(I/O,锁外) + 短临界区(碰撞检查+register+map.put)。 */
    private void attach(McpServerSpec spec) {
        McpClientWrapper client = connect(spec);
        List<String> incoming;
        try {
            incoming = toolNamesOf(client);
        } catch (RuntimeException e) {
            safeClose(client);
            throw new IllegalStateException("连通测试失败: " + e.getMessage(), e);
        }
        try {
            synchronized (this) {
                Set<String> existing = toolkit.getToolNames();
                for (String tn : incoming) {
                    if (existing.contains(tn)) {
                        throw new IllegalStateException(
                                "工具名冲突: '" + tn + "' 已被其他 MCP 服务器注册（扁平命名空间，拒绝添加）");
                    }
                }
                registerClient(client, spec.name());
                clients.put(spec.name(), client);
            }
        } catch (RuntimeException e) {
            safeClose(client);
            throw e;
        }
    }

    /**
     * 注册 client：默认（无分组函数）走 {@code registerMcpClient}（带超时，逐字节旧行为）；启用分组时
     * 先确保 {@code active} 分组存在，再经 {@code registration().mcpClient(...).group(g)} 注册——保留
     * {@code mcpClientName}（{@code removeMcpClient} 热移除照常）。须在 {@code synchronized(this)} 内调用。
     */
    private void registerClient(McpClientWrapper client, String serverName) {
        String group = toolGroupNamer == null ? null : toolGroupNamer.apply(serverName);
        if (group == null || group.isBlank()) {
            toolkit.registerMcpClient(client).block(DEFAULT_TIMEOUT);
            return;
        }
        if (toolkit.getToolGroup(group) == null) {
            toolkit.createToolGroup(group, "MCP server: " + serverName, true);
        }
        toolkit.registration().mcpClient(client).group(group).apply();
        managedGroups.add(group);
    }

    private McpClientWrapper connect(McpServerSpec spec) {
        McpClientBuilder builder = McpClientBuilder.create(spec.name())
                .timeout(DEFAULT_TIMEOUT)
                .initializationTimeout(DEFAULT_TIMEOUT);
        if (spec.isStdio()) {
            builder.stdioTransport(spec.command(), spec.args(), spec.env());
        } else if (spec.streamableHttp()) {
            builder.streamableHttpTransport(spec.url());
            if (!spec.headers().isEmpty()) {
                builder.headers(spec.headers());
            }
        } else {
            builder.sseTransport(spec.url());
            if (!spec.headers().isEmpty()) {
                builder.headers(spec.headers());
            }
        }
        return builder.buildSync();
    }

    private List<String> toolNamesOf(McpClientWrapper client) {
        List<McpSchema.Tool> tools = client.listTools().block(DEFAULT_TIMEOUT);
        List<String> names = new ArrayList<>();
        if (tools != null) {
            for (McpSchema.Tool t : tools) {
                names.add(t.name());
            }
        }
        return names;
    }

    private void safeClose(McpClientWrapper client) {
        try {
            client.close();
        } catch (Exception e) {
            log.warn("关闭 MCP 出错 {}: {}", client.getName(), e.getMessage());
        }
    }

    private static McpServerSpec fromLegacy(String name, McpServerConfig c) {
        return new McpServerSpec(name, c.getCommand(), c.getArgs(), c.getEnv(),
                c.getUrl(), c.isStreamableHttp(), c.getHeaders(), true);
    }
}
