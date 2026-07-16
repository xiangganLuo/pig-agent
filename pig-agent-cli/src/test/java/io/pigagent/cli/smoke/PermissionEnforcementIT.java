package io.pigagent.cli.smoke;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.Model;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import io.pigagent.config.PigAgentConfig;
import io.pigagent.core.agent.AgentHolder;
import io.pigagent.core.agent.PigAgent;
import io.pigagent.model.JsonModelStore;
import io.pigagent.model.ModelManager;
import io.pigagent.model.StoredModel;
import io.pigagent.provider.anthropic.AnthropicProtocol;
import io.pigagent.provider.dashscope.DashScopeProtocol;
import io.pigagent.provider.gemini.GeminiProtocol;
import io.pigagent.provider.ollama.OllamaProtocol;
import io.pigagent.provider.openai.OpenAiProtocol;
import io.pigagent.provider.registry.ProtocolRegistry;
import io.pigagent.tool.contract.GuardedAgentTool;
import io.pigagent.tool.contract.ToolContractGuard;
import io.pigagent.tool.permission.PermissionContextFactory;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 权限体系端到端集成测试（av2 Phase 4，原生路径）：用 AgentScope 2.0 原生
 * {@link PermissionContextState}（经 {@link PermissionContextFactory} 从 pig 模式 + toolkit 生成）+
 * 真实 anthropic 模型，验证 plan 模式否决可变工具、bypass 模式放行。取代旧的自研
 * {@code ToolPermissionHook}/{@code PermissionDeniedTool} 哨兵路径（H-2；旧 {@code PermissionVetoSpikeIT}
 * 已随哨兵机制归档删除，L-1）。
 *
 * <p>{@code *IT}，不进默认 {@code mvn test}。显式：
 * {@code mvn -pl pig-agent-cli -am test "-Dtest=PermissionEnforcementIT" "-Dsurefire.failIfNoSpecifiedTests=false"}
 */
class PermissionEnforcementIT {

    /** 记录是否被真正执行的 spy 可变工具（名字未知 → 分级为 EXEC，plan 下必被否决）。 */
    static final class SpyMutatingTool {
        final AtomicBoolean executed = new AtomicBoolean(false);

        @Tool(description = "执行一个可变的危险动作（集成测试用）")
        public String dangerousWrite(@ToolParam(name = "note", description = "备注") String note) {
            executed.set(true);
            return "MUTATED:" + note;
        }
    }

    private record Rig(SpyMutatingTool spy, AgentHolder holder, Toolkit toolkit) {
    }

    private Rig buildAgent(String mode) {
        return buildAgent(mode, false);
    }

    private Rig buildAgent(String mode, boolean guarded) {
        Path realModels = Path.of(System.getProperty("user.home"), ".pig-agent", "workspace", "models.json");
        assertThat(realModels).as("需要已配置的默认模型 models.json").exists();

        ProtocolRegistry registry = new ProtocolRegistry();
        registry.register(new OpenAiProtocol());
        registry.register(new AnthropicProtocol());
        registry.register(new GeminiProtocol());
        registry.register(new OllamaProtocol());
        registry.register(new DashScopeProtocol());
        ModelManager modelManager = new ModelManager(registry, new JsonModelStore(realModels));
        StoredModel def = modelManager.getDefault().orElseThrow();
        Model model = modelManager.buildModel(def);

        SpyMutatingTool spy = new SpyMutatingTool();
        Toolkit toolkit = new Toolkit();
        toolkit.registration().tool(spy).apply();
        if (guarded) {
            // Exercise the PRODUCTION dispatch path: AgentBootstrap wraps every built-in tool in a
            // GuardedAgentTool. This is the P0 regression guard — the native PermissionEngine only
            // gates a tool that resolves to a ToolBase (the acting phase auto-ALLOWs a non-ToolBase
            // tool), so if GuardedAgentTool ever stops being a ToolBase, permission enforcement is
            // silently bypassed. A raw-tool test would NOT catch that; this one must.
            ToolContractGuard.install(toolkit);
        }

        PigAgentConfig.PermissionConfig cfg = new PigAgentConfig.PermissionConfig();
        cfg.setMode(mode);
        // interactive=false：无 confirmer（plan 不会问；ask→DENY fail-closed；bypass 直接放行）。
        PermissionContextState permCtx = PermissionContextFactory.build(
                cfg, cfg.resolveMode(), toolkit.getToolNames(), false);

        String sysPrompt = "你是一个测试助理。当用户要求执行某动作时，你必须调用对应的工具，而不是凭空回答。";
        PigAgent agent = PigAgent.builder()
                .name("perm-it").sysPrompt(sysPrompt).model(model)
                .toolkit(toolkit).permissionContext(permCtx).build();
        return new Rig(spy, new AgentHolder(agent), toolkit);
    }

    private String ask(AgentHolder holder, String text) {
        Msg msg = Msg.builder().name("user").role(MsgRole.USER)
                .content(TextBlock.builder().text(text).build()).build();
        Msg reply = holder.get().call(msg);
        String out = reply == null || reply.getTextContent() == null ? "" : reply.getTextContent();
        System.out.println("[PERM-IT] reply: " + out.replaceAll("\\s+", " "));
        return out;
    }

    @Test
    void planModeVetoesMutatingTool() {
        Rig rig = buildAgent("plan");
        ask(rig.holder(), "请调用 dangerousWrite 工具，note 传 PLAN_TEST。");
        System.out.println("[PERM-IT] plan spyExecuted=" + rig.spy().executed.get());
        assertThat(rig.spy().executed.get()).as("plan 模式必须否决可变工具").isFalse();
    }

    @Test
    void bypassModeAllowsMutatingTool() {
        Rig rig = buildAgent("bypass");
        ask(rig.holder(), "请调用 dangerousWrite 工具，note 传 BYPASS_TEST。");
        System.out.println("[PERM-IT] bypass spyExecuted=" + rig.spy().executed.get());
        assertThat(rig.spy().executed.get()).as("bypass 模式应放行可变工具").isTrue();
    }

    /**
     * P0 回归护栏（最关键）：可变工具经 {@link GuardedAgentTool} 包裹（=生产 dispatch 路径），plan/EXPLORE
     * 下仍必须被原生 {@code PermissionEngine} 否决。P0 曾因 {@code GuardedAgentTool} 只实现 {@code AgentTool}
     * （非 {@code ToolBase}）→ ReAct acting 阶段对非 ToolBase 工具自动 ALLOW，权限在生产环境静默失效。
     * 若 {@code GuardedAgentTool extends ToolBase} 被回退，本用例会翻红（原始工具用例不会）。
     */
    @Test
    void planModeVetoesGuardedMutatingTool() {
        Rig rig = buildAgent("plan", true);
        // Sanity: the tool the model will call is actually the guard, not the raw reflective tool —
        // so a regression to `implements AgentTool` genuinely flips this test's outcome.
        assertThat(rig.toolkit().getTool("dangerousWrite"))
                .as("spy 工具必须已被 GuardedAgentTool 包裹（生产 dispatch 路径）")
                .isInstanceOf(GuardedAgentTool.class);
        ask(rig.holder(), "请调用 dangerousWrite 工具，note 传 PLAN_GUARDED_TEST。");
        System.out.println("[PERM-IT] plan(guarded) spyExecuted=" + rig.spy().executed.get());
        assertThat(rig.spy().executed.get())
                .as("plan 模式必须否决被 GuardedAgentTool 包裹的可变工具（P0 回归护栏）").isFalse();
    }

    /** 对称检查：guard 未过度拦截——bypass 下经 guard 的可变工具仍被执行（证明 guard 正确委派 callAsync）。 */
    @Test
    void bypassModeAllowsGuardedMutatingTool() {
        Rig rig = buildAgent("bypass", true);
        assertThat(rig.toolkit().getTool("dangerousWrite")).isInstanceOf(GuardedAgentTool.class);
        ask(rig.holder(), "请调用 dangerousWrite 工具，note 传 BYPASS_GUARDED_TEST。");
        System.out.println("[PERM-IT] bypass(guarded) spyExecuted=" + rig.spy().executed.get());
        assertThat(rig.spy().executed.get()).as("bypass 模式应放行经 guard 的可变工具").isTrue();
    }
}
