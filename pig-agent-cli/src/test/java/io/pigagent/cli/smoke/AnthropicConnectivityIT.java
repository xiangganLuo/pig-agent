package io.pigagent.cli.smoke;

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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 实网连通冒烟（仅在显式 -Dtest=AnthropicConnectivitySmokeTest 时运行）：
 * 加载真实 workspace 的 models.json，经 anthropic 协议(自定义 baseUrl) 实际调用远端模型服务，
 * 证明"程序能以 anthropic 模型跑起来"。会消耗 token，不进 CI 默认集。
 */
class AnthropicConnectivityIT {

    @Test
    void defaultModelConnects() {
        Path models = Path.of(System.getProperty("user.home"), ".pig-agent", "workspace", "models.json");
        assertThat(models).exists();

        ProtocolRegistry registry = new ProtocolRegistry();
        registry.register(new OpenAiProtocol());
        registry.register(new AnthropicProtocol());
        registry.register(new GeminiProtocol());
        registry.register(new OllamaProtocol());
        registry.register(new DashScopeProtocol());

        ModelManager mm = new ModelManager(registry, new JsonModelStore(models));
        assertThat(mm.isConfigured()).isTrue();
        StoredModel def = mm.getDefault().orElseThrow();
        System.out.println("[SMOKE] testing model: " + def.label() + " baseUrl=" + def.baseUrl());

        ModelManager.TestResult r = mm.test(def);
        System.out.println("[SMOKE] ok=" + r.ok() + " error=" + r.error());
        assertThat(r.ok()).as("anthropic via custom baseUrl should connect; error=%s", r.error()).isTrue();
    }
}
