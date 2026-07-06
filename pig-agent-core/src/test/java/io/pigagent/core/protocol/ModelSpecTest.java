package io.pigagent.core.protocol;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ModelSpecTest {

    @Test
    void hasApiKeyTrueWhenPresent() {
        // Arrange
        ModelSpec spec = new ModelSpec("openai", "sk-123", null, "gpt-4o");

        // Act & Assert
        assertThat(spec.hasApiKey()).isTrue();
    }

    @Test
    void hasApiKeyFalseWhenNullOrBlank() {
        // Arrange
        ModelSpec nullKey = new ModelSpec("ollama", null, "http://localhost:11434", "llama3.2");
        ModelSpec blankKey = new ModelSpec("ollama", "   ", null, "llama3.2");

        // Act & Assert
        assertThat(nullKey.hasApiKey()).isFalse();
        assertThat(blankKey.hasApiKey()).isFalse();
    }

    @Test
    void hasBaseUrlTrueWhenPresent() {
        // Arrange
        ModelSpec spec = new ModelSpec("openai", "sk-123", "https://api.xiaomimimo.com/v1", "mimo-v2.5-pro");

        // Act & Assert
        assertThat(spec.hasBaseUrl()).isTrue();
    }

    @Test
    void hasBaseUrlFalseWhenNullOrBlank() {
        // Arrange
        ModelSpec nullUrl = new ModelSpec("anthropic", "sk-123", null, "claude-sonnet-4-6");
        ModelSpec blankUrl = new ModelSpec("anthropic", "sk-123", "  ", "claude-sonnet-4-6");

        // Act & Assert
        assertThat(nullUrl.hasBaseUrl()).isFalse();
        assertThat(blankUrl.hasBaseUrl()).isFalse();
    }

    @Test
    void protocolIdIsCarried() {
        // Arrange
        ModelSpec spec = new ModelSpec("dashscope", "sk-123", null, "qwen-max");

        // Act & Assert
        assertThat(spec.protocolId()).isEqualTo("dashscope");
        assertThat(spec.modelName()).isEqualTo("qwen-max");
    }
}
