package io.pigagent.tool.mcp;

import io.pigagent.config.PigAgentConfig.AgentManagementConfig;
import io.pigagent.mcp.McpManager;
import io.pigagent.mcp.McpServerSpec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** D-SEC 安全门回归测试（CRITICAL）。 */
class McpToolTest {

    private final AgentManagementConfig policy = new AgentManagementConfig();
    private final McpManager mcp = mock(McpManager.class);
    private boolean confirm = true;
    private final McpTool tool = new McpTool(mcp, () -> policy, p -> confirm);

    private static McpServerSpec url(String name, String u) {
        return new McpServerSpec(name, null, List.of(), Map.of(), u, false, Map.of(), true);
    }

    private static McpServerSpec stdio(String name) {
        return new McpServerSpec(name, "npx", List.of(), Map.of(), null, false, Map.of(), true);
    }

    @Test
    void allowAddFalseIsRejected() {
        policy.setAllowAdd(false);
        assertThat(tool.applyAddPolicy(url("a", "https://h.com/sse"))).contains("拒绝");
        verify(mcp, never()).add(any());
    }

    @Test
    void stdioRejectedEvenWhenAllowAddTrue() {
        policy.setAllowAdd(true);
        policy.setAllowedHosts(List.of("h.com"));
        assertThat(tool.applyAddPolicy(stdio("a"))).contains("URL");
        verify(mcp, never()).add(any());
    }

    @Test
    void hostNotAllowlistedIsRejected() {
        policy.setAllowAdd(true);
        policy.setAllowedHosts(List.of("ok.com"));
        assertThat(tool.applyAddPolicy(url("a", "https://evil.com/sse"))).contains("白名单");
        verify(mcp, never()).add(any());
    }

    @Test
    void allowlistedUrlConfirmedIsAdded() {
        policy.setAllowAdd(true);
        policy.setAllowedHosts(List.of("ok.com"));
        confirm = true;
        when(mcp.add(any())).thenAnswer(i -> i.getArgument(0));
        assertThat(tool.applyAddPolicy(url("a", "https://ok.com/sse"))).contains("已添加");
        verify(mcp).add(any());
    }

    @Test
    void notConfirmedAborts() {
        policy.setAllowAdd(true);
        policy.setAllowedHosts(List.of("ok.com"));
        confirm = false;
        assertThat(tool.applyAddPolicy(url("a", "https://ok.com/sse"))).contains("确认");
        verify(mcp, never()).add(any());
    }

    @Test
    void removeDisabledByDefault() {
        assertThat(tool.removeMcpServer("a")).contains("拒绝");
        verify(mcp, never()).remove(any());
    }

    @Test
    void nonHttpsSchemeRejected() {
        policy.setAllowAdd(true);
        policy.setAllowedHosts(List.of("ok.com"));
        assertThat(tool.applyAddPolicy(url("a", "http://ok.com/sse"))).contains("https");
        verify(mcp, never()).add(any());
    }

    @Test
    void userinfoBypassRejected() {
        // host=evil.com 但 userinfo=ok.com —— 必须按 userinfo 拒绝，防白名单旁路
        policy.setAllowAdd(true);
        policy.setAllowedHosts(List.of("ok.com"));
        assertThat(tool.applyAddPolicy(url("a", "https://ok.com@evil.com/sse"))).contains("userinfo");
        verify(mcp, never()).add(any());
    }

    @Test
    void hostMatchIsCaseInsensitive() {
        policy.setAllowAdd(true);
        policy.setAllowedHosts(List.of("ok.com"));
        confirm = true;
        when(mcp.add(any())).thenAnswer(i -> i.getArgument(0));
        assertThat(tool.applyAddPolicy(url("a", "https://OK.Com/sse"))).contains("已添加");
        verify(mcp).add(any());
    }

    @Test
    void confirmerThrowingIsTreatedAsDenied() {
        AgentManagementConfig pol = new AgentManagementConfig();
        pol.setAllowAdd(true);
        pol.setAllowedHosts(List.of("ok.com"));
        McpManager m = mock(McpManager.class);
        McpTool t = new McpTool(m, () -> pol, p -> {
            throw new RuntimeException("reader closed");
        });
        assertThat(t.applyAddPolicy(url("a", "https://ok.com/sse"))).contains("拒绝");
        verify(m, never()).add(any());
    }
}
