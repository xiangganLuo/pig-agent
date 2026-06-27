package io.pigagent.mcp;

import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * McpManager 守卫/委托逻辑的单测（不触网/不起进程）。
 *
 * <p>连接相关路径（attach/connect/碰撞检查/回滚）依赖真实 MCP 服务器，
 * 在 tasks.md 6.2 手动冒烟中验证；此处只覆盖确定性的去重/删除/停用/列举/未知名分支。
 */
class McpManagerTest {

    private McpStore store;
    private McpManager mgr;

    private static McpServerSpec disabledUrl(String name) {
        return new McpServerSpec(name, null, List.of(), Map.of(),
                "https://h.invalid/sse", false, Map.of(), false);
    }

    @BeforeEach
    void setUp() {
        store = mock(McpStore.class);
        // findAll 为空 → initialize 不触发任何 attach（不连接）
        when(store.findAll()).thenReturn(List.of());
        mgr = new McpManager();
        mgr.initialize(store, new Toolkit(), null);
    }

    @Test
    void addRejectsDuplicateNameBeforeConnecting() {
        when(store.findByName("dup")).thenReturn(Optional.of(disabledUrl("dup")));
        assertThatThrownBy(() -> mgr.add(disabledUrl("dup")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("已存在");
        verify(store, never()).save(any());
    }

    @Test
    void removeDeletesFromStoreWhenNotConnected() {
        mgr.remove("ghost");
        verify(store).deleteByName("ghost");
    }

    @Test
    void disablePersistsDisabledFlagWhenNotConnected() {
        when(store.findByName("s")).thenReturn(Optional.of(disabledUrl("s")));
        mgr.disable("s");
        verify(store).save(argThat(spec -> !spec.enabled()));
    }

    @Test
    void enableRejectsUnknownServer() {
        when(store.findByName("none")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> mgr.enable("none"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("无此");
    }

    @Test
    void listReportsDisconnectedFromStore() {
        when(store.findAll()).thenReturn(List.of(disabledUrl("a"), disabledUrl("b")));
        List<McpManager.ServerStatus> list = mgr.list();
        assertThat(list).hasSize(2);
        assertThat(list).allMatch(s -> !s.connected() && s.toolCount() == -1);
    }

    @Test
    void findByNameDelegatesToStore() {
        when(store.findByName("x")).thenReturn(Optional.of(disabledUrl("x")));
        assertThat(mgr.findByName("x")).isPresent();
        verify(store).findByName("x");
    }
}
