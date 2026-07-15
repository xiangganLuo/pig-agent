package io.pigagent.mcp;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 把 MCP 服务器配置存到单个 {@code mcp.json}：
 * <pre>{ "servers": [ { name, command, args, env, url, streamableHttp, headers, enabled }, ... ] }</pre>
 *
 * <p>按 {@code name} 作键。容损：文件存在但不可解析时，备份为 {@code mcp.json.bak} 并从空开始，
 * 使应用回退到正常启动而非崩溃（镜像 {@code JsonModelStore} 的姿态）。
 */
public final class JsonMcpStore implements McpStore {

    private static final Logger log = LoggerFactory.getLogger(JsonMcpStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path file;
    private Data data;

    public JsonMcpStore(Path file) {
        this.file = file;
        this.data = load();
    }

    private Data load() {
        if (!Files.exists(file)) {
            return new Data();
        }
        try {
            Data loaded = MAPPER.readValue(file.toFile(), Data.class);
            return loaded != null ? loaded : new Data();
        } catch (IOException e) {
            backupCorrupt();
            return new Data();
        }
    }

    private void backupCorrupt() {
        try {
            Files.move(file, file.resolveSibling("mcp.json.bak"), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
        }
        log.warn("mcp.json 不可解析；已备份为 mcp.json.bak，从空开始。");
    }

    private void persist() {
        try {
            Files.createDirectories(file.getParent());
            // env/headers 可能含 Bearer token：写入后在 POSIX 上收敛为 0600（解决安全审查 M-2）。
            // 凭据仍明文存储，依赖 0600 + 目录权限保护，勿在共享主机使用。
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), data);
            restrictToOwner(file);
        } catch (IOException e) {
            log.error("写入 mcp.json 失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 在 POSIX 平台把凭据文件权限收敛为仅属主可读写（{@code 0600}）；非 POSIX 平台
     * （如 Windows）静默忽略——写入已成功，那里依赖目录权限。
     */
    private static void restrictToOwner(Path file) {
        try {
            Files.setPosixFilePermissions(file,
                    java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException ignored) {
            // 非 POSIX 文件系统或瞬时错误——不影响已成功的写入
        }
    }

    @Override
    public synchronized List<McpServerSpec> findAll() {
        List<McpServerSpec> result = new ArrayList<>();
        for (Entry e : data.servers) {
            try {
                result.add(e.toSpec());
            } catch (RuntimeException invalid) {
                // 跳过坏条目（例如 command/url 都缺），不影响其余。
                log.warn("跳过无效 MCP 条目 '{}': {}", e.name, invalid.getMessage());
            }
        }
        return result;
    }

    @Override
    public synchronized Optional<McpServerSpec> findByName(String name) {
        return data.servers.stream()
                .filter(e -> name.equals(e.name))
                .findFirst()
                .map(Entry::toSpec);
    }

    @Override
    public synchronized McpServerSpec save(McpServerSpec spec) {
        data.servers.removeIf(e -> spec.name().equals(e.name));
        data.servers.add(Entry.from(spec));
        persist();
        return spec;
    }

    @Override
    public synchronized void deleteByName(String name) {
        data.servers.removeIf(e -> name.equals(e.name));
        persist();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static final class Data {
        public List<Entry> servers = new ArrayList<>();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static final class Entry {
        public String name;
        public String command;
        public List<String> args = new ArrayList<>();
        public Map<String, String> env = Map.of();
        public String url;
        public boolean streamableHttp;
        public Map<String, String> headers = Map.of();
        public boolean enabled = true;

        static Entry from(McpServerSpec s) {
            Entry e = new Entry();
            e.name = s.name();
            e.command = s.command();
            e.args = new ArrayList<>(s.args());
            e.env = s.env();
            e.url = s.url();
            e.streamableHttp = s.streamableHttp();
            e.headers = s.headers();
            e.enabled = s.enabled();
            return e;
        }

        McpServerSpec toSpec() {
            return new McpServerSpec(name, command, args, env, url, streamableHttp, headers, enabled);
        }
    }
}
