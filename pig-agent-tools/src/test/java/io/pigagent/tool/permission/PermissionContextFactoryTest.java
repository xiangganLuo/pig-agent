package io.pigagent.tool.permission;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.permission.PermissionEngine;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.pigagent.config.PermissionMode;
import io.pigagent.config.PigAgentConfig.PermissionConfig;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 原生权限门等价回归：{@link PermissionContextFactory} 产出的 {@link PermissionContextState} 交给真实
 * {@link PermissionEngine} 评估，逐 (模式 × 工具) 断言最终 {@link PermissionBehavior} 与 pig 决策表一致
 * （取代已删除的 {@code ToolPermissionHookTest}/{@code PermissionResolverTest} 的哨兵机制断言）。
 *
 * <p>覆盖安全不变量：plan 只读（可变全 DENY，allowlist 不越权）、bypass 全 ALLOW、ask/auto 决策表、
 * MCP_ADMIN 委托 D-SEC（ALLOW）、渠道/无人值守 fail-closed（无 confirmer → ASK 降级 DENY）、
 * 以及无显式规则工具的基线模式兜底。
 */
class PermissionContextFactoryTest {

    /**
     * 名字可控的最小 ToolBase：checkPermissions 直接 passthrough，把决策权完全交给引擎的 rules+mode。
     * {@code readOnly} 反映工具真实只读性——EXPLORE(plan) 以内置检查按 {@code isReadOnly()} 放行只读工具，
     * 故只读工具须如实声明（对应真实 {@code @Tool(readOnly=true)}）。
     */
    private static final class FakeTool extends ToolBase {
        FakeTool(String name, boolean readOnly) {
            super(ToolBase.builder().name(name).description(name)
                    .inputSchema(Map.of("type", "object")).readOnly(readOnly));
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            return Mono.empty();
        }

        @Override
        public Mono<PermissionDecision> checkPermissions(
                Map<String, Object> input, PermissionContextState context) {
            return Mono.just(PermissionDecision.passthrough("test"));
        }
    }

    private static final List<String> TOOLS = List.of(
            "readFile", "writeFile", "fetchUrl", "executeCommand", "addMcpServer");

    private static PermissionConfig cfg() {
        return new PermissionConfig();
    }

    /** 建 context → 引擎评估 → 取最终 behavior。工具的只读性按风险分级如实建模。 */
    private static PermissionBehavior behavior(PermissionConfig cfg, PermissionMode mode,
                                              boolean interactive, String tool, Map<String, Object> input) {
        PermissionContextState ctx = PermissionContextFactory.build(cfg, mode, TOOLS, interactive);
        PermissionEngine engine = new PermissionEngine(ctx);
        boolean readOnly = ToolRiskClassifier.classify(tool, cfg.getToolOverrides()) == ToolRisk.READ_ONLY;
        PermissionDecision d = engine.checkPermission(new FakeTool(tool, readOnly), input).block();
        assertThat(d).as("decision for %s@%s", tool, mode).isNotNull();
        return d.getBehavior();
    }

    private static PermissionBehavior behavior(PermissionMode mode, String tool) {
        return behavior(cfg(), mode, true, tool, Map.of());
    }

    // ---- mode -> native base mapping ----

    @Test
    void modeMapping() {
        assertThat(PermissionContextFactory.toNativeMode(PermissionMode.PLAN))
                .isEqualTo(io.agentscope.core.permission.PermissionMode.EXPLORE);
        assertThat(PermissionContextFactory.toNativeMode(PermissionMode.ASK))
                .isEqualTo(io.agentscope.core.permission.PermissionMode.DEFAULT);
        assertThat(PermissionContextFactory.toNativeMode(PermissionMode.AUTO))
                .isEqualTo(io.agentscope.core.permission.PermissionMode.ACCEPT_EDITS);
        assertThat(PermissionContextFactory.toNativeMode(PermissionMode.BYPASS))
                .isEqualTo(io.agentscope.core.permission.PermissionMode.BYPASS);
    }

    // ---- interactive matrix (mirrors PermissionPolicy table) ----

    @Test
    void readOnlyAlwaysAllowed() {
        for (PermissionMode m : PermissionMode.values()) {
            assertThat(behavior(m, "readFile")).as("readFile@" + m).isEqualTo(PermissionBehavior.ALLOW);
        }
    }

    @Test
    void planDeniesEveryMutatingTool() {
        assertThat(behavior(PermissionMode.PLAN, "writeFile")).isEqualTo(PermissionBehavior.DENY);
        assertThat(behavior(PermissionMode.PLAN, "fetchUrl")).isEqualTo(PermissionBehavior.DENY);
        assertThat(behavior(PermissionMode.PLAN, "executeCommand")).isEqualTo(PermissionBehavior.DENY);
        assertThat(behavior(PermissionMode.PLAN, "addMcpServer")).isEqualTo(PermissionBehavior.DENY);
    }

    @Test
    void askConfirmsMutatingButDelegatesMcpAdmin() {
        assertThat(behavior(PermissionMode.ASK, "writeFile")).isEqualTo(PermissionBehavior.ASK);
        assertThat(behavior(PermissionMode.ASK, "fetchUrl")).isEqualTo(PermissionBehavior.ASK);
        assertThat(behavior(PermissionMode.ASK, "executeCommand")).isEqualTo(PermissionBehavior.ASK);
        assertThat(behavior(PermissionMode.ASK, "addMcpServer")).isEqualTo(PermissionBehavior.ALLOW);
    }

    @Test
    void autoAllowsEditsAndNetworkButStillAsksExec() {
        assertThat(behavior(PermissionMode.AUTO, "writeFile")).isEqualTo(PermissionBehavior.ALLOW);
        assertThat(behavior(PermissionMode.AUTO, "fetchUrl")).isEqualTo(PermissionBehavior.ALLOW);
        assertThat(behavior(PermissionMode.AUTO, "executeCommand")).isEqualTo(PermissionBehavior.ASK);
        assertThat(behavior(PermissionMode.AUTO, "addMcpServer")).isEqualTo(PermissionBehavior.ALLOW);
    }

    @Test
    void bypassAllowsEverything() {
        for (String t : TOOLS) {
            assertThat(behavior(PermissionMode.BYPASS, t)).as(t + "@BYPASS").isEqualTo(PermissionBehavior.ALLOW);
        }
    }

    // ---- allowlist (tool-level) ----

    @Test
    void toolAllowlistShortCircuitsInAskButNotPlan() {
        PermissionConfig c = cfg();
        c.getAllowlist().getTools().add("writeFile");
        assertThat(behavior(c, PermissionMode.ASK, true, "writeFile", Map.of()))
                .isEqualTo(PermissionBehavior.ALLOW);
        // plan 只读语义高于 allowlist
        assertThat(behavior(c, PermissionMode.PLAN, true, "writeFile", Map.of()))
                .isEqualTo(PermissionBehavior.DENY);
    }

    // ---- M-1: executeCommand gets NO ask rule so the command-granular allowlist can govern ----

    @Test
    void executeCommandHasNoAskRule_soCommandAllowlistCanGovern() {
        // ask/auto interactive: an ASK rule would short-circuit before CommandPermissionTool.checkPermissions
        // (native deny>ask>allow>tool-check), shadowing a command-level ALLOW. So the factory emits none.
        assertThat(PermissionContextFactory.build(cfg(), PermissionMode.ASK, TOOLS, true)
                .getAskRules()).doesNotContainKey("executeCommand");
        assertThat(PermissionContextFactory.build(cfg(), PermissionMode.AUTO, TOOLS, true)
                .getAskRules()).doesNotContainKey("executeCommand");
        // non-interactive would-be-ASK also emits no rule (mode default DONT_ASK fail-closes it).
        assertThat(PermissionContextFactory.build(cfg(), PermissionMode.ASK, TOOLS, false)
                .getDenyRules()).doesNotContainKey("executeCommand");
        // But plan still DENies exec explicitly (only-read semantics beat any allowlist).
        assertThat(PermissionContextFactory.build(cfg(), PermissionMode.PLAN, TOOLS, true)
                .getDenyRules()).containsKey("executeCommand");
    }

    // ---- non-interactive (channel / autonomous): ASK fails closed ----

    @Test
    void channelAskFailsClosed() {
        // ask + no confirmer: mutating tools that would ASK become DENY; read-only still allowed
        assertThat(behavior(cfg(), PermissionMode.ASK, false, "writeFile", Map.of()))
                .isEqualTo(PermissionBehavior.DENY);
        assertThat(behavior(cfg(), PermissionMode.ASK, false, "executeCommand", Map.of()))
                .isEqualTo(PermissionBehavior.DENY);
        assertThat(behavior(cfg(), PermissionMode.ASK, false, "readFile", Map.of()))
                .isEqualTo(PermissionBehavior.ALLOW);
    }

    @Test
    void channelAutoAllowsEditsButDeniesExec() {
        // auto + no confirmer (pig channel default): write/network auto-allow, exec (ASK) fails closed
        assertThat(behavior(cfg(), PermissionMode.AUTO, false, "writeFile", Map.of()))
                .isEqualTo(PermissionBehavior.ALLOW);
        assertThat(behavior(cfg(), PermissionMode.AUTO, false, "fetchUrl", Map.of()))
                .isEqualTo(PermissionBehavior.ALLOW);
        assertThat(behavior(cfg(), PermissionMode.AUTO, false, "executeCommand", Map.of()))
                .isEqualTo(PermissionBehavior.DENY);
    }

    // ---- base-mode fallback (defense-in-depth for tools without an explicit rule) ----

    @Test
    void bypassBaseModeAllowsUnruledTool() {
        PermissionContextState ctx = PermissionContextFactory.build(cfg(), PermissionMode.BYPASS, TOOLS, true);
        PermissionEngine engine = new PermissionEngine(ctx);
        PermissionDecision d = engine.checkPermission(new FakeTool("someUnknownMcpTool", false), Map.of()).block();
        assertThat(d).isNotNull();
        assertThat(d.getBehavior()).isEqualTo(PermissionBehavior.ALLOW);
    }

    @Test
    void nonInteractiveBaseModeIsDontAskExceptBypass() {
        assertThat(PermissionContextFactory.build(cfg(), PermissionMode.ASK, TOOLS, false).getMode())
                .isEqualTo(io.agentscope.core.permission.PermissionMode.DONT_ASK);
        assertThat(PermissionContextFactory.build(cfg(), PermissionMode.AUTO, TOOLS, false).getMode())
                .isEqualTo(io.agentscope.core.permission.PermissionMode.DONT_ASK);
        // bypass keeps full-trust allow-all even without a confirmer
        assertThat(PermissionContextFactory.build(cfg(), PermissionMode.BYPASS, TOOLS, false).getMode())
                .isEqualTo(io.agentscope.core.permission.PermissionMode.BYPASS);
    }
}
