package io.pigagent.core.memory.search;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Real embedder pure logic (offline): request-body build, response parse, L2-normalize, endpoint join,
 * and no-credential-echo on failure. The live network round-trip is deferred to {@code /ls:itest}.
 */
class OpenAiCompatibleEmbedderTest {

    @Test
    void buildsOpenAiRequestBody() {
        String body = OpenAiCompatibleEmbedder.buildRequestBody("doubao-embedding", "hello");
        assertThat(body).contains("\"model\":\"doubao-embedding\"").contains("\"input\":\"hello\"");
    }

    @Test
    void parsesEmbeddingArray() throws Exception {
        float[] v = OpenAiCompatibleEmbedder.parseEmbedding("{\"data\":[{\"embedding\":[0.1,0.2,0.3]}]}");
        assertThat(v).containsExactly(0.1f, 0.2f, 0.3f);
    }

    @Test
    void parseRejectsMissingData() {
        assertThatThrownBy(() -> OpenAiCompatibleEmbedder.parseEmbedding("{\"data\":[]}"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void parsePreservesVectorDimension() throws Exception {
        float[] v = OpenAiCompatibleEmbedder.parseEmbedding(
                "{\"data\":[{\"embedding\":[0.1,0.2,0.3,0.4,0.5]}]}");
        assertThat(v).hasSize(5); // parsed dimension matches the response vector length
    }

    @Test
    void parseRejectsEmptyEmbeddingVector() {
        assertThatThrownBy(() ->
                OpenAiCompatibleEmbedder.parseEmbedding("{\"data\":[{\"embedding\":[]}]}"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void embedClassifiesNon2xxByTypeWithoutEchoingKey() {
        // A non-2xx from the transport (defaultHttpPost throws "embeddings HTTP <status>"):
        OpenAiCompatibleEmbedder.HttpPost http500 = (u, k, b) -> {
            throw new IllegalStateException("embeddings HTTP 500 for key sk-secret");
        };
        Embedder e = new OpenAiCompatibleEmbedder("https://x", "sk-secret", "m", http500);
        assertThatThrownBy(() -> e.embed("x"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("IllegalStateException") // classified by exception type
                .hasMessageNotContaining("sk-secret");          // never echoes the key
    }

    @Test
    void joinsEndpointToleratingTrailingSlash() {
        assertThat(OpenAiCompatibleEmbedder.embeddingsUrl("https://x/v1/")).isEqualTo("https://x/v1/embeddings");
        assertThat(OpenAiCompatibleEmbedder.embeddingsUrl("https://x/v1")).isEqualTo("https://x/v1/embeddings");
    }

    @Test
    void embedNormalizesParsedVectorViaFakeHttp() {
        OpenAiCompatibleEmbedder.HttpPost fake = (url, key, body) -> "{\"data\":[{\"embedding\":[3,4]}]}";
        Embedder e = new OpenAiCompatibleEmbedder("https://api.example.com/v1", "sk-secret", "m", fake);
        float[] v = e.embed("x"); // [3,4] → [0.6, 0.8]
        assertThat(v[0]).isCloseTo(0.6f, within(1e-5f));
        assertThat(v[1]).isCloseTo(0.8f, within(1e-5f));
    }

    @Test
    void passesUrlKeyAndInputToTransport() {
        String[] captured = new String[3];
        OpenAiCompatibleEmbedder.HttpPost capture = (url, key, body) -> {
            captured[0] = url;
            captured[1] = key;
            captured[2] = body;
            return "{\"data\":[{\"embedding\":[1]}]}";
        };
        new OpenAiCompatibleEmbedder("https://api/v1", "sk-xyz", "m", capture).embed("q");
        assertThat(captured[0]).isEqualTo("https://api/v1/embeddings");
        assertThat(captured[1]).isEqualTo("sk-xyz");
        assertThat(captured[2]).contains("\"input\":\"q\"");
    }

    @Test
    void failureDoesNotEchoTheApiKey() {
        OpenAiCompatibleEmbedder.HttpPost boom = (u, k, b) -> {
            throw new RuntimeException("upstream error for key sk-secret");
        };
        Embedder e = new OpenAiCompatibleEmbedder("https://x", "sk-secret", "m", boom);
        assertThatThrownBy(() -> e.embed("x"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("sk-secret");
    }
}
