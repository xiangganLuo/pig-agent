package io.pigagent.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StoredModelTest {

    @Test
    void createGeneratesIdAndLabel() {
        StoredModel m = StoredModel.create("openai", "key", null, "gpt-4o");
        assertThat(m.id()).isNotBlank();
        assertThat(m.protocolId()).isEqualTo("openai");
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

    @Test
    void createDefaultsToChatKind() {
        StoredModel m = StoredModel.create("openai", "k", null, "gpt-4o");
        assertThat(m.kind()).isEqualTo(ModelKind.CHAT);
        assertThat(m.isEmbedding()).isFalse();
    }

    @Test
    void fiveArgConstructorDefaultsToChat() {
        // backward-compat: pre-embedding call sites construct a 5-arg StoredModel
        StoredModel m = new StoredModel("id1", "openai", "k", null, "gpt-4o");
        assertThat(m.kind()).isEqualTo(ModelKind.CHAT);
    }

    @Test
    void nullKindNormalizesToChat() {
        StoredModel m = new StoredModel("id1", "openai", "k", null, "gpt-4o", null);
        assertThat(m.kind()).isEqualTo(ModelKind.CHAT);
    }

    @Test
    void createEmbeddingKind() {
        StoredModel m = StoredModel.create("openai", "k", "https://api.x/v1", "text-embedding-3-small",
                ModelKind.EMBEDDING);
        assertThat(m.kind()).isEqualTo(ModelKind.EMBEDDING);
        assertThat(m.isEmbedding()).isTrue();
    }

    @Test
    void withKindIsImmutable() {
        StoredModel chat = StoredModel.create("openai", "k", null, "m");
        StoredModel emb = chat.withKind(ModelKind.EMBEDDING);

        assertThat(chat.kind()).isEqualTo(ModelKind.CHAT); // original unchanged
        assertThat(emb.kind()).isEqualTo(ModelKind.EMBEDDING);
        assertThat(emb.id()).isEqualTo(chat.id());
    }

    @Test
    void withMethodsPreserveKind() {
        StoredModel emb = StoredModel.create("openai", "k", "u", "m", ModelKind.EMBEDDING);
        assertThat(emb.withApiKey("k2").kind()).isEqualTo(ModelKind.EMBEDDING);
        assertThat(emb.withBaseUrl("u2").kind()).isEqualTo(ModelKind.EMBEDDING);
        assertThat(emb.withModelName("m2").kind()).isEqualTo(ModelKind.EMBEDDING);
    }
}
