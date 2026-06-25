package io.pigagent.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StoredModelTest {

    @Test
    void createGeneratesIdAndLabel() {
        StoredModel m = StoredModel.create("openai", "key", null, "gpt-4o");
        assertThat(m.id()).isNotBlank();
        assertThat(m.providerId()).isEqualTo("openai");
        assertThat(m.label()).isEqualTo("openai / gpt-4o");
    }

    @Test
    void withMethodsAreImmutable() {
        StoredModel m = StoredModel.create("openai", "k1", "u1", "gpt-4o");
        StoredModel changed = m.withApiKey("k2").withBaseUrl("u2").withModelName("gpt-4.1");

        assertThat(m.apiKey()).isEqualTo("k1");
        assertThat(m.baseUrl()).isEqualTo("u1");
        assertThat(m.modelName()).isEqualTo("gpt-4o");

        assertThat(changed.id()).isEqualTo(m.id());
        assertThat(changed.apiKey()).isEqualTo("k2");
        assertThat(changed.baseUrl()).isEqualTo("u2");
        assertThat(changed.modelName()).isEqualTo("gpt-4.1");
    }
}
