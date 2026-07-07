package io.pigagent.tool.contract;

/**
 * Builds the canonical tool error result. Per the tool JSON contract, a failing tool MUST return a
 * regular result of the form {@code {"error":"<reason>"}} rather than throwing: the model then reads
 * a readable failure reason and can continue. The reason is credential-redacted
 * ({@link CredentialSanitizer}) and JSON-escaped, so an error can never echo a secret or produce
 * malformed JSON.
 *
 * <p>Built-in tools call {@link #message(String)} in their failure paths; the dispatch-layer
 * {@code GuardedAgentTool} uses it as the last-resort fallback for any exception that escapes a tool.
 */
public final class ToolErrors {

    private static final String GENERIC = "unknown error";

    private ToolErrors() {
    }

    /** The canonical {@code {"error":"<sanitized, escaped reason>"}} string. */
    public static String message(String reason) {
        String sanitized = CredentialSanitizer.sanitize(reason);
        if (sanitized.isEmpty()) {
            sanitized = GENERIC;
        }
        return "{\"error\":\"" + escapeJson(sanitized) + "\"}";
    }

    /** Minimal JSON string escaping (no external JSON dependency in this module). */
    private static String escapeJson(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
