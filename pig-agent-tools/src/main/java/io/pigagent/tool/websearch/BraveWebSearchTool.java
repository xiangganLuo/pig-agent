package io.pigagent.tool.websearch;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Web search tool using Brave Search API.
 * Requires BRAVE_API_KEY environment variable.
 */
public final class BraveWebSearchTool {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();
    private static final String API_URL = "https://api.search.brave.com/res/v1/web/search";

    @Tool(description = "Search the web using Brave Search. Returns top results with titles, URLs, and snippets.")
    public String webSearch(
            @ToolParam(name = "query", description = "Search query") String query,
            @ToolParam(name = "count", description = "Number of results (1-10, default 5)") String count
    ) {
        String apiKey = System.getenv("BRAVE_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            return "Error: BRAVE_API_KEY environment variable not set";
        }

        int resultCount = 5;
        if (count != null && !count.isBlank()) {
            try {
                resultCount = Math.clamp(Integer.parseInt(count.trim()), 1, 10);
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
                return "Search error (HTTP " + response.statusCode() + "): " + response.body();
            }

            return parseResults(response.body(), resultCount);
        } catch (Exception e) {
            return "Search error: " + e.getMessage();
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
