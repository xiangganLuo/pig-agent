package io.pigagent.plugin.builtin.tool;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.pigagent.tool.contract.ToolErrors;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * Fetches a URL's text content. Requests pass an {@link SsrfGuard} egress check first, so the agent
 * cannot reach loopback / private / cloud-metadata endpoints (SSRF), and an optional host allowlist
 * can narrow egress further. The client keeps {@code Redirect.NEVER} (JLine default) so a 3xx to an
 * internal host is not auto-followed.
 */
public final class SmartWebFetchTool {

    private static final HttpClient CLIENT =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private final List<String> allowedHosts;

    /** No host allowlist — only the SSRF IP guard applies. */
    public SmartWebFetchTool() {
        this(List.of());
    }

    /** @param allowedHosts optional host allowlist; empty means "IP guard only" */
    public SmartWebFetchTool(List<String> allowedHosts) {
        this.allowedHosts = allowedHosts == null ? List.of() : List.copyOf(allowedHosts);
    }

    @Tool(description = "Fetch content from a URL. Returns the page text content.")
    public String fetchUrl(@ToolParam(name = "url", description = "URL to fetch") String url) {
        String blocked = SsrfGuard.checkBlocked(url, allowedHosts);
        if (blocked != null) {
            return ToolErrors.message("blocked: destination not allowed");
        }
        try {
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url))
                    .timeout(Duration.ofSeconds(30)).header("User-Agent", "PigAgent/0.1").GET().build();
            HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            String body = response.body();
            if (body.length() > 10000) body = body.substring(0, 10000) + "\n... [truncated]";
            return "Status: %d\n\n%s".formatted(response.statusCode(), body);
        } catch (Exception e) {
            return ToolErrors.message(e.getMessage());
        }
    }
}
