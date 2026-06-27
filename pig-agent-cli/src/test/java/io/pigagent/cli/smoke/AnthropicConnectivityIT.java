package io.pigagent.cli.smoke;

import io.pigagent.model.JsonModelStore;
import io.pigagent.model.ModelManager;
import io.pigagent.model.StoredModel;
import io.pigagent.provider.anthropic.AnthropicProvider;
import io.pigagent.provider.dashscope.DashScopeProvider;
import io.pigagent.provider.gemini.GeminiProvider;
import io.pigagent.provider.mimo.MimoProvider;
import io.pigagent.provider.ollama.OllamaProvider;
import io.pigagent.provider.openai.OpenAiProvider;
import io.pigagent.provider.registry.ProviderRegistry;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 实网连通冒烟（仅在显式 -Dtest=AnthropicConnectivitySmokeTest 时运行）：
 * 加载真实 workspace 的 models.json，经 AnthropicProvider(baseUrl) 实际调用 deepthink，
 * 证明"程序能以 anthropic 模型跑起来"。会消耗 token，不进 CI 默认集。
 */
class AnthropicConnectivityIT {

    @Test
    void defaultModelConnects() {
        Path models = Path.of(System.getProperty("user.home"), ".pig-agent", "workspace", "models.json");
        assertThat(models).exists();

        ProviderRegistry registry = new ProviderRegistry();
        registry.register(new MimoProvider());
        registry.register(new AnthropicProvider());
        registry.register(new OpenAiProvider());
        registry.register(new OllamaProvider());
        registry.register(new GeminiProvider());
        registry.register(new DashScopeProvider());

        ModelManager mm = new ModelManager(registry, new JsonModelStore(models));
        assertThat(mm.isConfigured()).isTrue();
        StoredModel def = mm.getDefault().orElseThrow();
        System.out.println("[SMOKE] testing model: " + def.label() + " baseUrl=" + def.baseUrl());

        ModelManager.TestResult r = mm.test(def);
        System.out.println("[SMOKE] ok=" + r.ok() + " error=" + r.error());
        assertThat(r.ok()).as("anthropic via deepthink should connect; error=%s", r.error()).isTrue();
    }
}
