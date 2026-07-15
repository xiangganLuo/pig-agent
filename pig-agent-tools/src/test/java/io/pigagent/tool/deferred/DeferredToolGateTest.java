package io.pigagent.tool.deferred;

import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gate 对真实 {@link Toolkit} 的行为：延迟内置工具后从 {@code getToolSchemas()} 消失但仍注册；
 * MCP（预置 active 分组）工具计划延迟后其分组停用、工具从 schema 消失；揭示后恢复。
 */
class DeferredToolGateTest {

    private static Set<String> schemaNames(Toolkit toolkit) {
        return toolkit.getToolSchemas().stream().map(ToolSchema::getName).collect(Collectors.toSet());
    }

    private static ToolInfo infoFrom(Toolkit toolkit, String name, String mcpGroup) {
        String desc = toolkit.getToolSchemas().stream()
                .filter(s -> s.getName().equals(name)).map(ToolSchema::getDescription)
                .findFirst().orElse("");
        return ToolInfo.of(name, desc, mcpGroup);
    }

    @Test
    void deferredBuiltinIsHiddenFromSchemaButStaysRegistered() {
        Toolkit toolkit = new Toolkit();
        toolkit.registration().tool(new DeferredSampleTools()).apply();
        assertThat(schemaNames(toolkit)).contains("getWeather", "sendEmail", "readFile");

        List<ToolInfo> inv = List.of(
                infoFrom(toolkit, "getWeather", null),
                infoFrom(toolkit, "sendEmail", null),
                infoFrom(toolkit, "readFile", null));
        DeferralPlan plan = new DeferralPlan(Set.of("getWeather"));

        DeferredToolRegistry reg = DeferredToolGate.applyTo(toolkit, plan, inv);

        // 从模型 schema 消失（省 token），但仍注册在 Toolkit（揭示后可调用）
        assertThat(schemaNames(toolkit)).doesNotContain("getWeather");
        assertThat(schemaNames(toolkit)).contains("sendEmail", "readFile");
        assertThat(toolkit.getToolNames()).contains("getWeather");
        assertThat(reg.deferredNames()).containsExactly("getWeather");
        assertThat(reg.find("getWeather")).isPresent();
    }

    @Test
    void revealBringsDeferredBuiltinBackIntoSchema() {
        Toolkit toolkit = new Toolkit();
        toolkit.registration().tool(new DeferredSampleTools()).apply();
        List<ToolInfo> inv = List.of(infoFrom(toolkit, "getWeather", null));
        DeferredToolRegistry reg =
                DeferredToolGate.applyTo(toolkit, new DeferralPlan(Set.of("getWeather")), inv);
        assertThat(schemaNames(toolkit)).doesNotContain("getWeather");

        boolean revealed = DeferredToolGate.reveal(toolkit, reg).reveal("getWeather");

        assertThat(revealed).isTrue();
        assertThat(schemaNames(toolkit)).contains("getWeather");
        assertThat(reg.find("getWeather")).isEmpty(); // 已揭示，不再待发现
    }

    @Test
    void revealUnknownToolReturnsFalse() {
        Toolkit toolkit = new Toolkit();
        DeferredToolRegistry reg = new DeferredToolRegistry();
        assertThat(DeferredToolGate.reveal(toolkit, reg).reveal("nope")).isFalse();
    }

    @Test
    void mcpGroupToolIsDeferredByDeactivatingItsGroup() {
        // 模拟 McpManager attach：把工具注册进 active 分组 "mcp:srv"
        Toolkit toolkit = new Toolkit();
        toolkit.createToolGroup("mcp:srv", "MCP server: srv", true);
        toolkit.registration().tool(new DeferredSampleTools()).group("mcp:srv").apply();
        assertThat(schemaNames(toolkit)).contains("getWeather", "sendEmail", "readFile");

        List<ToolInfo> inv = List.of(
                infoFrom(toolkit, "getWeather", "mcp:srv"),
                infoFrom(toolkit, "sendEmail", "mcp:srv"),
                infoFrom(toolkit, "readFile", "mcp:srv"));
        // 计划延迟其中两个 → 停用 mcp:srv 分组（该服务器全部工具随之隐藏）
        DeferralPlan plan = new DeferralPlan(Set.of("getWeather", "sendEmail"));

        DeferredToolRegistry reg = DeferredToolGate.applyTo(toolkit, plan, inv);

        assertThat(schemaNames(toolkit)).doesNotContain("getWeather", "sendEmail", "readFile");
        assertThat(reg.deferredNames()).containsExactlyInAnyOrder("getWeather", "sendEmail");

        // 揭示其一 → 激活 mcp:srv → 该服务器工具重新进 schema
        boolean revealed = DeferredToolGate.reveal(toolkit, reg).reveal("getWeather");
        assertThat(revealed).isTrue();
        assertThat(schemaNames(toolkit)).contains("getWeather", "sendEmail", "readFile");
    }

    @Test
    void emptyPlanIsNoOp() {
        Toolkit toolkit = new Toolkit();
        toolkit.registration().tool(new DeferredSampleTools()).apply();
        Set<String> before = schemaNames(toolkit);

        DeferredToolRegistry reg = DeferredToolGate.applyTo(toolkit, DeferralPlan.empty(), List.of());

        assertThat(schemaNames(toolkit)).isEqualTo(before);
        assertThat(reg.isEmpty()).isTrue();
    }

    @Test
    void absentToolInPlanIsSkippedFailSafe() {
        Toolkit toolkit = new Toolkit();
        toolkit.registration().tool(new DeferredSampleTools()).apply();
        List<ToolInfo> inv = List.of(infoFrom(toolkit, "getWeather", null));
        // 计划里含一个 inventory/toolkit 都没有的名字 → 跳过，不抛
        DeferralPlan plan = new DeferralPlan(Set.of("getWeather", "ghostTool"));

        DeferredToolRegistry reg = DeferredToolGate.applyTo(toolkit, plan, inv);

        assertThat(reg.deferredNames()).containsExactly("getWeather");
    }
}
