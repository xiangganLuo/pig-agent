package io.pigagent.tool.permission;

import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionRule;
import io.pigagent.config.PermissionMode;
import io.pigagent.config.PigAgentConfig.PermissionConfig;

import java.util.Collection;
import java.util.Locale;

/**
 * 把 pig 的运维权限模型（{@link PermissionMode} plan/ask/auto/bypass + {@link ToolRiskClassifier}
 * 分级 + allowlist）映射为 AgentScope 2.0 原生 {@link PermissionContextState}（mode + 逐工具规则）。
 *
 * <p>取代自研的 {@code ToolPermissionHook} + {@code PermissionDeniedTool} 哨兵：原生
 * {@code PermissionEngine} 在工具执行前评估 context，DENY 的工具永不执行、ReAct 循环回喂
 * {@code ToolResultState.DENIED} 让模型继续（Risk-2 PoC 已证等价，无需哨兵）。
 *
 * <p><b>模式映射</b>：{@code plan→EXPLORE}、{@code ask→DEFAULT}、{@code auto→ACCEPT_EDITS}、
 * {@code bypass→BYPASS}。非交互回合（渠道/无人值守，无 confirmer）以 {@code DONT_ASK} 作兜底基线，
 * 并把逐工具 ASK 规则降级为 DENY —— 即 pig「无 confirmer 时 ASK fail-closed」的原生等价。
 *
 * <p>规则按 {@link PermissionPolicy} 决策表逐工具生成（ruleContent=null 匹配该工具所有调用）：
 * READ_ONLY→ALLOW；plan 下可变工具→DENY（只读不可越权）；bypass→全 ALLOW；MCP_ADMIN→ALLOW
 * （委托内层 D-SEC 门，不重复确认）。tool 级 allowlist 命中→ALLOW。
 *
 * <p><b>已知边界（Phase-4 补全）</b>：命令粒度 allowlist（{@code allowlist.commands} 首 token）
 * 不做静态规则映射——原生规则优先级 deny&gt;ask&gt;allow，工具级 ASK 规则会遮蔽命令级 ALLOW 规则，
 * 且反射式 {@code executeCommand} 的 {@code matchRule} 语义不可控。命令粒度放行留待 Phase-4 的 HITL
 * 处理器（{@code RequireUserConfirmEvent}/{@code ConfirmResult.suggestedRules} + {@link AllowlistWriter}
 * /{@link CommandKeys}）；当前保守 fail-closed（allowlist 命令在 ask 下仍会确认），方向安全。
 */
public final class PermissionContextFactory {

    private PermissionContextFactory() {
    }

    /** pig 运维模式 → 原生基线模式（不考虑 confirmer 可用性）。 */
    public static io.agentscope.core.permission.PermissionMode toNativeMode(PermissionMode mode) {
        return switch (mode) {
            case PLAN -> io.agentscope.core.permission.PermissionMode.EXPLORE;
            case ASK -> io.agentscope.core.permission.PermissionMode.DEFAULT;
            case AUTO -> io.agentscope.core.permission.PermissionMode.ACCEPT_EDITS;
            case BYPASS -> io.agentscope.core.permission.PermissionMode.BYPASS;
        };
    }

    /**
     * 构建原生权限上下文。
     *
     * @param cfg         权限配置（tool-overrides + allowlist）
     * @param pigMode     本回合生效的 pig 模式（交互用 {@code resolveMode}，渠道用 {@code resolveChannelMode}，
     *                    或 per-agent 覆盖）
     * @param toolNames   本 toolkit 已知的工具名集合（逐个生成规则）
     * @param interactive 是否有人工确认通道；{@code false} 时 ASK 一律 fail-closed 降级为 DENY
     */
    public static PermissionContextState build(PermissionConfig cfg, PermissionMode pigMode,
                                               Collection<String> toolNames, boolean interactive) {
        PermissionContextState.Builder builder = PermissionContextState.builder()
                .mode(effectiveNativeMode(pigMode, interactive));
        String source = "pig:" + pigMode.name().toLowerCase(Locale.ROOT)
                + (interactive ? "" : ":noconfirm");
        if (toolNames != null) {
            for (String name : toolNames) {
                if (name == null || name.isBlank()) {
                    continue;
                }
                addRule(builder, cfg, pigMode, name, interactive, source);
            }
        }
        return builder.build();
    }

    private static void addRule(PermissionContextState.Builder builder, PermissionConfig cfg,
                                PermissionMode pigMode, String name, boolean interactive, String source) {
        ToolRisk risk = ToolRiskClassifier.classify(name, cfg.getToolOverrides());
        boolean allowlisted = cfg.getAllowlist().getTools().contains(name);
        PermissionDecision decision = PermissionPolicy.decide(pigMode, risk, allowlisted);
        PermissionBehavior behavior = toBehavior(decision, interactive);
        PermissionRule rule = new PermissionRule(name, null, behavior, source);
        switch (behavior) {
            case ALLOW -> builder.addAllowRule(name, rule);
            case DENY -> builder.addDenyRule(name, rule);
            case ASK -> builder.addAskRule(name, rule);
            default -> {
                // PASSTHROUGH is never produced by the decision table; ignore defensively.
            }
        }
    }

    /**
     * 非交互（无 confirmer）时以 {@code DONT_ASK} 作基线，让任何未显式建规则的工具（原生内置 / 后挂 MCP
     * 工具）的 ASK 也降级为 DENY —— 但 BYPASS 是显式完全信任，保持 allow-all。
     */
    private static io.agentscope.core.permission.PermissionMode effectiveNativeMode(
            PermissionMode pigMode, boolean interactive) {
        if (!interactive && pigMode != PermissionMode.BYPASS) {
            return io.agentscope.core.permission.PermissionMode.DONT_ASK;
        }
        return toNativeMode(pigMode);
    }

    private static PermissionBehavior toBehavior(PermissionDecision decision, boolean interactive) {
        return switch (decision) {
            case ALLOW -> PermissionBehavior.ALLOW;
            case DENY -> PermissionBehavior.DENY;
            case ASK -> interactive ? PermissionBehavior.ASK : PermissionBehavior.DENY;
        };
    }
}
