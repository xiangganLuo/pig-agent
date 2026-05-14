package io.pigagent.tool.webfetch;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public final class SmartWebFetchTool {
    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @Tool(description = "Fetch content from a URL. Returns the page text content.")
    public String fetchUrl(@ToolParam(name = "url", description = "URL to fetch") String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url))
                    .timeout(Duration.ofSeconds(30)).header("User-Agent", "PigAgent/0.1").GET().build();
            HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            String body = response.body();
            if (body.length() > 10000) body = body.substring(0, 10000) + "\n... [truncated]";
            return "Status: %d\n\n%s".formatted(response.statusCode(), body);
        } catch (Exception e) { return "Error: " + e.getMessage(); }
    }
}
