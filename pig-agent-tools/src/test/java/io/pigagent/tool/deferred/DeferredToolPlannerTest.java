package io.pigagent.tool.deferred;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 决策纯函数：禁用→空、显式清单、阈值自动延迟 MCP、tool_search 永不延迟。 */
class DeferredToolPlannerTest {

    private static ToolInfo builtin(String name) {
        return ToolInfo.of(name, name + " desc", null);
    }

    private static ToolInfo mcp(String name) {
        return ToolInfo.of(name, name + " desc", "mcp:srv");
    }

    private static List<ToolInfo> inventory(int builtins, int mcp) {
        List<ToolInfo> inv = new ArrayList<>();
        for (int i = 0; i < builtins; i++) {
            inv.add(builtin("b" + i));
        }
        for (int i = 0; i < mcp; i++) {
            inv.add(mcp("m" + i));
        }
        return inv;
    }

    @Test
    void disabledYieldsEmptyPlanRegardlessOfInput() {
        DeferralPlan plan = DeferredToolPlanner.plan(false, List.of("b0"), true, 1, inventory(3, 3));
        assertThat(plan.isEmpty()).isTrue();
    }

    @Test
    void explicitListDeferredWhenPresent() {
        DeferralPlan plan = DeferredToolPlanner.plan(true, List.of("b1", "ghost"), false, 999,
                inventory(3, 0));
        // 存在的 b1 被延迟；不存在的 ghost 被忽略
        assertThat(plan.deferredToolNames()).containsExactly("b1");
    }

    @Test
    void thresholdAutoDefersAllMcpTools() {
        // 总数 = 3 内置 + 3 MCP = 6 > 阈值 5 → 全部 MCP 被延迟；内置不动
        DeferralPlan plan = DeferredToolPlanner.plan(true, List.of(), true, 5, inventory(3, 3));
        assertThat(plan.deferredToolNames()).containsExactlyInAnyOrder("m0", "m1", "m2");
    }

    @Test
    void belowThresholdWithEmptyListDefersNothing() {
        // 总数 6 不超过阈值 25，显式清单空 → 空计划
        DeferralPlan plan = DeferredToolPlanner.plan(true, List.of(), true, 25, inventory(3, 3));
        assertThat(plan.isEmpty()).isTrue();
    }

    @Test
    void autoDeferMcpOffKeepsMcpVisibleEvenOverThreshold() {
        DeferralPlan plan = DeferredToolPlanner.plan(true, List.of(), false, 1, inventory(3, 3));
        assertThat(plan.isEmpty()).isTrue();
    }

    @Test
    void toolSearchNeverDeferred() {
        List<ToolInfo> inv = new ArrayList<>(inventory(1, 3));
        inv.add(ToolInfo.of("tool_search", "search", null));
        // 显式点名 + 超阈值都不该延迟 tool_search
        DeferralPlan plan = DeferredToolPlanner.plan(true, List.of("tool_search"), true, 2, inv);
        assertThat(plan.deferredToolNames()).doesNotContain("tool_search");
    }

    @Test
    void explicitAndThresholdAreUnioned() {
        DeferralPlan plan = DeferredToolPlanner.plan(true, List.of("b0"), true, 3, inventory(2, 2));
        // 总数 4 > 3 → m0,m1 自动延迟；并集加上显式 b0
        assertThat(plan.deferredToolNames()).containsExactlyInAnyOrder("b0", "m0", "m1");
    }
}
