package io.pigagent.core.memory.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;

/**
 * A real {@link Embedder} calling an <b>OpenAI-compatible</b> {@code /embeddings} endpoint (Doubao,
 * DeepSeek, any OpenAI-compatible vendor) with a stored model's {@code apiKey}/{@code baseUrl}/{@code
 * modelName} — capability {@code hybrid-memory-search}.
 *
 * <p><b>Spike outcome / deferral (OD7).</b> AgentScope 2.0 has no embedding Model API, and any embedding
 * call is a live network round-trip that cannot be verified offline. So this class keeps its network I/O
 * behind an injectable {@link HttpPost} seam: the <em>pure</em> request-building and response-parsing
 * ({@link #buildRequestBody}/{@link #parseEmbedding}) plus L2-normalization are unit-tested offline with
 * canned JSON, while the real HTTP round-trip and embedding <em>quality</em> are verified under
 * {@code /ls:itest}. A failure throws a {@link RuntimeException}; {@link MemorySearchIndex} catches it
 * and degrades to BM25-only, so a broken/absent embedder never breaks search.
 *
 * <p>Security: the API key is sent only as the {@code Authorization: Bearer} header and is never logged.
 */
public final class OpenAiCompatibleEmbedder implements Embedder {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleEmbedder.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Injectable HTTP-POST seam (offline tests inject a fake returning canned JSON). */
    @FunctionalInterface
    public interface HttpPost {
        /** POST {@code jsonBody} to {@code url} with {@code Authorization: Bearer <apiKey>}; return the body. */
        String post(String url, String apiKey, String jsonBody) throws Exception;
    }

    private final String endpoint;
    private final String apiKey;
    private final String modelName;
    private final HttpPost http;

    /** Production constructor — a real {@link HttpClient} POST (10s timeout). */
    public OpenAiCompatibleEmbedder(String baseUrl, String apiKey, String modelName) {
        this(baseUrl, apiKey, modelName, defaultHttpPost());
    }

    /** Seam constructor — inject a fake {@link HttpPost} for offline tests. */
    public OpenAiCompatibleEmbedder(String baseUrl, String apiKey, String modelName, HttpPost http) {
        this.endpoint = embeddingsUrl(baseUrl);
        this.apiKey = apiKey == null ? "" : apiKey;
        this.modelName = Objects.requireNonNull(modelName, "modelName");
        this.http = Objects.requireNonNull(http, "http");
    }

    @Override
    public float[] embed(String text) {
        try {
            String body = http.post(endpoint, apiKey, buildRequestBody(modelName, text == null ? "" : text));
            return Vectors.l2normalize(parseEmbedding(body));
        } catch (Exception e) {
            // Do not echo the key/body — only the exception type.
            throw new IllegalStateException("embeddings call failed: " + e.getClass().getSimpleName(), e);
        }
    }

    /** Build the {@code {"model":..,"input":..}} request body (pure — offline-testable). */
    static String buildRequestBody(String model, String input) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("model", model);
        root.put("input", input);
        return root.toString();
    }

    /** Parse {@code data[0].embedding} into a {@code float[]} (pure — offline-testable). */
    static float[] parseEmbedding(String json) throws Exception {
        JsonNode root = MAPPER.readTree(json);
        JsonNode data = root.path("data");
        if (!data.isArray() || data.isEmpty()) {
            throw new IllegalStateException("no embedding data in response");
        }
        JsonNode emb = data.get(0).path("embedding");
        if (!(emb instanceof ArrayNode arr) || arr.isEmpty()) {
            throw new IllegalStateException("empty embedding vector in response");
        }
        float[] vec = new float[arr.size()];
        for (int i = 0; i < arr.size(); i++) {
            vec[i] = (float) arr.get(i).asDouble();
        }
        return vec;
    }

    /** Join {@code baseUrl} with {@code /embeddings} (tolerating a trailing slash). */
    static String embeddingsUrl(String baseUrl) {
        String base = baseUrl == null ? "" : baseUrl.strip();
        if (base.isEmpty()) {
            return "/embeddings";
        }
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/embeddings";
    }

    private static HttpPost defaultHttpPost() {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        return (url, key, jsonBody) -> {
            HttpRequest.Builder rb = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody));
            if (key != null && !key.isBlank()) {
                rb.header("Authorization", "Bearer " + key);
            }
            HttpResponse<String> resp = client.send(rb.build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                throw new IllegalStateException("embeddings HTTP " + resp.statusCode());
            }
            return resp.body();
        };
    }
}
