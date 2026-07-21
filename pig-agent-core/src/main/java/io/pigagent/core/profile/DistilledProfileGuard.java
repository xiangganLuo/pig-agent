package io.pigagent.core.profile;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A deterministic, <b>model-free</b> conservative filter for auto-distilled profile content — capability
 * {@code user-profile} (M-E). It is the privacy/hallucination pre-gate applied by
 * {@link ProfileConsolidationService} <em>before</em> a distilled body is written to {@code USER.md}
 * (which is injected into the system prompt and stays resident every turn).
 *
 * <p><b>What it enforces (structural, offline-testable):</b> keep only well-formed
 * {@code - **<Field>**: <value>} lines whose normalized field name is in a conservative
 * identity/preference whitelist; drop free-form prose, out-of-whitelist fields, and empty/overlong
 * values; canonicalize the heading. When nothing conforms it returns {@code ""}, so the caller declines
 * to write (the existing profile is left intact).
 *
 * <p><b>What it does NOT do (documented residual risk):</b> this is a <em>structural</em> gate (is this
 * an identity/preference-shaped field?), not a <em>semantic</em> one. A hallucinated value in a
 * whitelisted field (e.g. a wrong {@code name}) still passes; credential-shaped values are handled by
 * the {@link SecretRedactor} that {@link UserProfileStore#write} stacks on top. These semantic limits
 * are exactly why background distillation stays default-off (see the M-E spike).
 *
 * <p>The whitelist deliberately excludes a bare {@code address} field (only "how to address you" /
 * "call me" / "form of address" phrasings are allowed) so a physical/home address is not mistaken for
 * an addressing preference.
 */
public final class DistilledProfileGuard {

    /** Matches a field line {@code - **<name>**: <value>}; group 1 = name, group 2 = value. */
    private static final Pattern FIELD_LINE =
            Pattern.compile("^\\s*-\\s*\\*\\*(.+?)\\*\\*\\s*:\\s*(.*)$");

    /** Max characters for a single field value; longer values look like free-form content → dropped. */
    private static final int MAX_VALUE_CHARS = 200;

    /**
     * Conservative identity/preference field names (normalized: lowercase, {@code -_} → space, collapsed).
     * Intentionally narrow — unknown fields are dropped rather than trusted.
     */
    private static final Set<String> ALLOWED_FIELDS = Set.of(
            // identity
            "name", "full name", "preferred name", "nickname", "pronouns",
            "call me", "how to address", "how to address you", "form of address",
            "role", "occupation", "profession",
            // durable preferences
            "language", "preferred language", "languages", "locale",
            "output style", "response style", "communication style", "writing style", "tone", "style",
            "technology preferences", "technology preference", "tech preferences", "tech preference",
            "technologies", "tech stack", "preferred stack", "preferred tools",
            "working style", "work style",
            "timezone", "time zone");

    private DistilledProfileGuard() {
    }

    /**
     * Filter a distilled profile body down to conforming identity/preference field lines.
     *
     * @param distilled the raw distiller output (may be {@code null}/blank).
     * @return a canonical {@code # User Profile} body with only the kept field lines, or {@code ""} when
     *     nothing conforms (caller should then decline to write).
     */
    public static String filter(String distilled) {
        if (distilled == null || distilled.isBlank()) {
            return "";
        }
        List<String> kept = new ArrayList<>();
        for (String line : distilled.split("\n", -1)) {
            Matcher m = FIELD_LINE.matcher(line);
            if (!m.matches()) {
                continue; // headings, prose, blank lines → dropped
            }
            String value = m.group(2).trim();
            if (value.isEmpty() || value.length() > MAX_VALUE_CHARS) {
                continue; // empty or overlong value → dropped
            }
            if (!ALLOWED_FIELDS.contains(normalize(m.group(1)))) {
                continue; // out-of-whitelist field → dropped
            }
            kept.add("- **" + m.group(1).trim() + "**: " + value);
        }
        if (kept.isEmpty()) {
            return "";
        }
        return UserProfileStore.heading() + "\n\n" + String.join("\n", kept) + "\n";
    }

    /** Normalize a field name for whitelist matching: lowercase, {@code -_} → space, collapse, trim. */
    private static String normalize(String field) {
        return field.replaceAll("[-_]+", " ").replaceAll("\\s+", " ").trim().toLowerCase();
    }
}
