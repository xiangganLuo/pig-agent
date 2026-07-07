package io.pigagent.tool.websearch;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.pigagent.tool.contract.ToolErrors;
import io.pigagent.tool.availability.Availability;
import io.pigagent.tool.availability.ToolAvailability;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import java.util.function.Function;

/**
 * Web search tool using Brave Search API.
 * Requires the {@code BRAVE_API_KEY} environment variable; when it is missing the {@code webSearch}
 * tool declares itself unavailable via {@link ToolAvailability} so it is hidden from the model schema
 * rather than failing at call time.
 */
public final class BraveWebSearchTool implements ToolAvailability {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();
    private static final String API_URL = "https://api.search.brave.com/res/v1/web/search";
    private static final String API_KEY_ENV = "BRAVE_API_KEY";
    private static final String TOOL_NAME = "webSearch";

    private final Function<String, String> env;

    public BraveWebSearchTool() {
        this(System::getenv);
    }

    /** Test seam: inject the environment-variable lookup. */
    BraveWebSearchTool(Function<String, String> env) {
        this.env = env;
    }

    @Override
    public Set<String> availabilityToolNames() {
        return Set.of(TOOL_NAME);
    }

    @Override
    public Availability checkAvailability() {
        String apiKey = env.apply(API_KEY_ENV);
        return (apiKey == null || apiKey.isBlank())
                ? Availability.unavailable(API_KEY_ENV + " not set")
                : Availability.AVAILABLE;
    }

    @Tool(description = "Search the web using Brave Search. Returns top results with titles, URLs, and snippets.")
    public String webSearch(
            @ToolParam(name = "query", description = "Search query") String query,
            @ToolParam(name = "count", description = "Number of results (1-10, default 5)") String count
    ) {
        String apiKey = env.apply(API_KEY_ENV);
        if (apiKey == null || apiKey.isBlank()) {
            return ToolErrors.message(API_KEY_ENV + " environment variable not set");
        }

        int resultCount = 5;
        if (count != null && !count.isBlank()) {
            try {
                int parsed = Integer.parseInt(count.trim());
                resultCount = Math.max(1, Math.min(10, parsed));
            } catch (NumberFormatException e) {
                // use default
            }
        }

        try {
            String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
            URI uri = URI.create(API_URL + "?q=" + encodedQuery + "&count=" + resultCount);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(uri)
                    .timeout(Duration.ofSeconds(15))
                    .header("Accept", "application/json")
                    .header("Accept-Encoding", "gzip")
                    .header("X-Subscription-Token", apiKey)
                    .GET()
                    .build();

            HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                return ToolErrors.message("Search error (HTTP " + response.statusCode() + "): " + response.body());
            }

            return parseResults(response.body(), resultCount);
        } catch (Exception e) {
            return ToolErrors.message("Search error: " + e.getMessage());
        }
    }

    private String parseResults(String json, int maxResults) {
        StringBuilder sb = new StringBuilder();
        int webResultsStart = json.indexOf("\"results\":");
        if (webResultsStart < 0) {
            return "No results found";
        }

        int count = 0;
        int pos = webResultsStart;
        while (count < maxResults) {
            int titleStart = json.indexOf("\"title\":", pos);
            if (titleStart < 0) break;
            int urlStart = json.indexOf("\"url\":", titleStart);
            int descStart = json.indexOf("\"description\":", urlStart);
            if (urlStart < 0 || descStart < 0) break;

            String title = extractJsonValue(json, titleStart + 8);
            String url = extractJsonValue(json, urlStart + 6);
            String description = extractJsonValue(json, descStart + 14);

            sb.append(count + 1).append(". ").append(title).append("\n");
            sb.append("   URL: ").append(url).append("\n");
            sb.append("   ").append(description).append("\n\n");

            pos = descStart + 14;
            count++;
        }

        return count == 0 ? "No results found" : sb.toString().trim();
    }

    private String extractJsonValue(String json, int start) {
        int quoteStart = json.indexOf('"', start);
        if (quoteStart < 0) return "";
        int quoteEnd = quoteStart + 1;
        while (quoteEnd < json.length()) {
            if (json.charAt(quoteEnd) == '"' && json.charAt(quoteEnd - 1) != '\\') break;
            quoteEnd++;
        }
        return json.substring(quoteStart + 1, quoteEnd)
                .replace("\\\"", "\"")
                .replace("\\n", "\n")
                .replace("\\/", "/");
    }
}
