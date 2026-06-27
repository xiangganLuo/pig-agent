package io.pigagent.mcp;

import java.util.List;
import java.util.Optional;

/** MCP 服务器配置的持久化，按 {@code name} 作键。实现须容损：单个坏文件不得让整体崩溃。 */
public interface McpStore {

    List<McpServerSpec> findAll();

    Optional<McpServerSpec> findByName(String name);

    /** 按 name upsert，返回保存的配置。 */
    McpServerSpec save(McpServerSpec spec);

    void deleteByName(String name);
}
