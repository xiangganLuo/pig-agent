package io.pigagent.cli.smoke;

import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PreActingEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import io.pigagent.core.agent.AgentFactory;
import io.pigagent.core.agent.AgentHolder;
import io.pigagent.model.JsonModelStore;
import io.pigagent.model.ModelManager;
import io.pigagent.model.StoredModel;
import io.pigagent.provider.anthropic.AnthropicProtocol;
import io.pigagent.provider.dashscope.DashScopeProtocol;
import io.pigagent.provider.gemini.GeminiProtocol;
import io.pigagent.provider.ollama.OllamaProtocol;
import io.pigagent.provider.openai.OpenAiProtocol;
import io.pigagent.provider.registry.ProtocolRegistry;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 权限系统 Task 1.1 SPIKE（承重卡点）：验证 {@code PreActingEvent} Hook 能否**否决**一次工具调用。
 *
 * <p>策略：高优先级 Hook 在 {@code PreActingEvent} 里把"可变工具"的待执行 {@link ToolUseBlock}
 * **改写**为一个只读 deny 占位工具（{@code permissionDenied}）。若生效，spy 工具不会执行，且模型
 * 收到拒绝结果后继续对话——这正是 plan 模式否决 + ask 拒绝所需的语义。
 *
 * <p>{@code *IT}，不进默认 {@code mvn test}（真调 LLM）。显式：
 * {@code mvn -pl pig-agent-cli -am test "-Dtest=PermissionVetoSpikeIT" "-Dsurefire.failIfNoSpecifiedTests=false"}
 */
class PermissionVetoSpikeIT {

    /** 记录是否被真正执行的 spy「危险」工具。 */
    static final class SpyDangerTool {
        final AtomicBoolean executed = new AtomicBoolean(false);

        @Tool(description = "执行一个危险的可变动作（spike 测试用，例如写文件/删数据）")
        public String dangerousAction(
                @ToolParam(name = "note", description = "任意备注") String note) {
            executed.set(true);
            return "DANGER_EXECUTED:" + note;
        }
    }

    /** 只读 deny 占位工具：被改写指向它的调用会得到拒绝说明。 */
    static final class DenyTool {
        @Tool(description = "权限拒绝占位（plan 模式下不执行可变工具）")
        public String permissionDenied() {
            return "权限拒绝：当前为只读/受限模式，不执行可变工具。请产出计划而非直接执行。";
        }
    }

    /** 把非 deny 的工具调用改写为 permissionDenied（模拟 plan 全否决）。 */
    static final class VetoHook implements Hook {
        volatile boolean sawPreActing = false;
        volatile String rewrittenFrom = null;

        @Override
        public <T extends HookEvent> Mono<T> onEvent(T event) {
            if (event instanceof PreActingEvent pre) {
                ToolUseBlock tu = pre.getToolUse();
                if (tu != null && !"permissionDenied".equals(tu.getName())) {
                    sawPreActing = true;
                    rewrittenFrom = tu.getName();
                    pre.setToolUse(ToolUseBlock.builder()
                            .id(tu.getId())
                            .name("permissionDenied")
                            .input(Map.of())
                            .build());
                }
            }
            return Mono.just(event);
        }

        @Override
        public int priority() {
            return 0; // 最高优先级，早于日志等 hook
        }
    }

    @Test
    void preActingHookCanVetoToolByRewritingToDenySentinel() {
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

        SpyDangerTool spy = new SpyDangerTool();
        VetoHook hook = new VetoHook();

        Toolkit toolkit = new Toolkit();
        toolkit.registration().tool(spy).apply();
        toolkit.registration().tool(new DenyTool()).apply();
        System.out.println("[SPIKE] 已注册工具: " + toolkit.getToolNames());

        String sysPrompt = "你是一个测试助理。当用户要求执行某个动作时，你必须调用对应的工具，而不是凭空回答。";
        AgentFactory factory = new AgentFactory("veto-spike", sysPrompt, toolkit, List.<Hook>of(hook), null);
        AgentHolder holder = new AgentHolder(factory.create(model));

        Msg msg = Msg.builder().name("user").role(MsgRole.USER)
                .content(TextBlock.builder().text(
                        "请调用 dangerousAction 工具执行一个危险动作，note 参数传 SPIKE_HELLO。").build())
                .build();
        Msg reply = holder.get().call(msg);
        String out = reply == null ? "" : (reply.getTextContent() == null ? "" : reply.getTextContent());
        System.out.println("[SPIKE] sawPreActing=" + hook.sawPreActing
                + " rewrittenFrom=" + hook.rewrittenFrom
                + " spyExecuted=" + spy.executed.get());
        System.out.println("[SPIKE] reply: " + out.replaceAll("\\s+", " "));

        // 核心断言：Hook 在 PreActing 拦到了调用，且 spy 危险工具没有真正执行（被改写否决）。
        assertThat(hook.sawPreActing).as("Hook 应在 PreActing 阶段看到工具调用").isTrue();
        assertThat(spy.executed.get()).as("被否决的危险工具不应执行").isFalse();
    }
}
