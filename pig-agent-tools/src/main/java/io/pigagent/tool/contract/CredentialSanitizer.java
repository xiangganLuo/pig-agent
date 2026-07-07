package io.pigagent.tool.contract;

import java.util.regex.Pattern;

/**
 * Redacts credential-like values from free-text tool error messages so a failure never echoes a
 * key/token back to the model. This aligns with the model/mcp convention of never surfacing raw
 * secret values (e.g. {@code McpTool} redacts {@code env}/{@code headers}); here the input is
 * free text (an exception message or HTTP body), so redaction is pattern-based.
 *
 * <p>Conservative by design: it only rewrites substrings that clearly look like a secret
 * (an {@code sk-} style key, a {@code Bearer <token>}, or a {@code key=<value>} / {@code token=<value>}
 * assignment), replacing just the value with {@code ***}. Innocent text is left untouched.
 */
public final class CredentialSanitizer {

    private static final String MASK = "***";

    /** {@code sk-...} style provider keys (OpenAI / Anthropic and lookalikes). */
    private static final Pattern SK_KEY = Pattern.compile("sk-[A-Za-z0-9._\\-]{6,}");

    /** {@code Bearer <token>} authorization values. */
    private static final Pattern BEARER = Pattern.compile("(?i)(bearer)\\s+[A-Za-z0-9._\\-+/=]+");

    /**
     * {@code key = value} / {@code token: value} style assignments for a set of secret-bearing
     * names. Group 1 = name, group 2 = separator (kept); the value is masked.
     */
    private static final Pattern ASSIGNMENT = Pattern.compile(
            "(?i)((?:api[_-]?key|apikey|access[_-]?token|token|secret|password|passwd|"
                    + "authorization|x-subscription-token|subscription[_-]?token))"
                    + "(\\s*[\"']?\\s*[:=]\\s*[\"']?)"
                    + "([^\\s\"'&,;}]{3,})");

    private CredentialSanitizer() {
    }

    /**
     * Redact credential-like values from {@code raw}. A null/blank input yields an empty string
     * so callers can safely build a message from it.
     */
    public static String sanitize(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        String out = raw;
        out = ASSIGNMENT.matcher(out).replaceAll(m -> java.util.regex.Matcher.quoteReplacement(
                m.group(1) + m.group(2) + MASK));
        out = BEARER.matcher(out).replaceAll("$1 " + MASK);
        out = SK_KEY.matcher(out).replaceAll(MASK);
        return out;
    }
}
