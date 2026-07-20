package io.pigagent.cli;

import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.Toolkit;
import io.pigagent.tool.deferred.DeferralPlan;
import io.pigagent.tool.deferred.DeferredToolRegistry;
import io.pigagent.tool.deferred.ToolInfo;
import io.pigagent.tool.deferred.ToolSearchTool;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * backward-safe 命门（智能默认，D4）：{@code AgentBootstrap.applyDeferral} 在计划为空时移除
 * {@code tool_search}（初始 schema 与 enabled=false 逐字节等价），计划非空时照常隐藏被延迟工具。
 */
class AgentBootstrapDeferralTest {

    /** 内联 @Tool 夹具（cli 测试可访问 agentscope-core @Tool）。 */
    static final class Fixture {
        @Tool(name = "alpha", description = "alpha capability")
        public String alpha() {
            return "a";
        }

        @Tool(name = "beta", description = "beta capability")
        public String beta() {
            return "b";
        }
    }

    private static Set<String> schemaNames(Toolkit tk) {
        return tk.getToolSchemas().stream().map(ToolSchema::getName).collect(Collectors.toSet());
    }

    private static Toolkit toolkitWithToolSearch(DeferredToolRegistry reg) {
        Toolkit tk = new Toolkit();
        tk.registration().tool(new Fixture()).apply();
        tk.registration().tool(new ToolSearchTool(reg, name -> true)).apply();
        return tk;
    }

    @Test
    void emptyPlanRemovesToolSearchLeavingSchemaByteIdentical() {
        DeferredToolRegistry reg = new DeferredToolRegistry();
        Toolkit withFeature = toolkitWithToolSearch(reg);
        assertThat(schemaNames(withFeature)).contains("tool_search", "alpha", "beta");

        // enabled=false equivalent: the same tools without tool_search / grouping.
        Toolkit baseline = new Toolkit();
        baseline.registration().tool(new Fixture()).apply();

        AgentBootstrap.applyDeferral(withFeature, DeferralPlan.empty(), List.of(), reg);

        assertThat(schemaNames(withFeature)).doesNotContain("tool_search");
        assertThat(schemaNames(withFeature)).isEqualTo(schemaNames(baseline));
    }

    @Test
    void nonEmptyPlanHidesPlannedToolAndKeepsToolSearch() {
        DeferredToolRegistry reg = new DeferredToolRegistry();
        Toolkit tk = toolkitWithToolSearch(reg);
        List<ToolInfo> inv = List.of(
                ToolInfo.of("alpha", "alpha capability", null),
                ToolInfo.of("beta", "beta capability", null));

        AgentBootstrap.applyDeferral(tk, new DeferralPlan(Set.of("alpha")), inv, reg);

        assertThat(schemaNames(tk)).doesNotContain("alpha");
        assertThat(schemaNames(tk)).contains("beta", "tool_search");
        assertThat(reg.deferredNames()).containsExactly("alpha");
    }
}
