package io.pigagent.core.profile;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Conservative credential redaction for the user profile ({@code user-profile}). Mirrors the
 * {@code pig-agent-tools} {@code CredentialSanitizer} but lives in {@code pig-agent-core} because the
 * profile store + injection middleware are core-level and cannot depend on the tools module. It exists
 * so a secret can never be written into (or injected from) {@code USER.md}: only substrings that
 * clearly look like a secret — an {@code sk-} style key, a {@code Bearer <token>}, or a
 * {@code key=<value>} / {@code token: <value>} assignment — have their value masked; innocent text is
 * left untouched.
 */
public final class SecretRedactor {

    private static final String MASK = "***";

    /** {@code sk-...} style provider keys (OpenAI / Anthropic and lookalikes). */
    private static final Pattern SK_KEY = Pattern.compile("sk-[A-Za-z0-9._\\-]{6,}");

    /** {@code Bearer <token>} authorization values. */
    private static final Pattern BEARER = Pattern.compile("(?i)(bearer)\\s+[A-Za-z0-9._\\-+/=]+");

    /** {@code key = value} / {@code token: value} style assignments for secret-bearing names. */
    private static final Pattern ASSIGNMENT = Pattern.compile(
            "(?i)((?:api[_-]?key|apikey|access[_-]?token|token|secret|password|passwd|"
                    + "authorization|x-subscription-token|subscription[_-]?token))"
                    + "(\\s*[\"']?\\s*[:=]\\s*[\"']?)"
                    + "([^\\s\"'&,;}]{3,})");

    private SecretRedactor() {
    }

    /** Redact credential-like values from {@code raw}. A null input yields an empty string. */
    public static String redact(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        String out = raw;
        out = ASSIGNMENT.matcher(out).replaceAll(m -> Matcher.quoteReplacement(
                m.group(1) + m.group(2) + MASK));
        out = BEARER.matcher(out).replaceAll("$1 " + MASK);
        out = SK_KEY.matcher(out).replaceAll(MASK);
        return out;
    }
}
