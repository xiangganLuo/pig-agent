package io.pigagent.tool.deferred;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Tokenizes a tool name + description into lowercase search keywords (DRY single point, reused when
 * populating {@link DeferredToolRegistry} and when scoring {@code tool_search} queries).
 *
 * <p>Splits on non-alphanumeric separators <em>and</em> camelCase boundaries (so {@code webSearch}
 * yields {@code web, search}), lowercases, and drops tokens shorter than {@value #MIN_LEN} chars.
 */
public final class Keywords {

    private static final int MIN_LEN = 2;

    private Keywords() {
    }

    /** Keywords derived from a tool's name and description (never {@code null}; may be empty). */
    public static Set<String> from(String name, String description) {
        Set<String> out = new LinkedHashSet<>();
        addTokens(out, name);
        addTokens(out, description);
        return out;
    }

    /** Tokenize an arbitrary string (e.g. a search query) the same way, for symmetric matching. */
    public static Set<String> tokenize(String text) {
        Set<String> out = new LinkedHashSet<>();
        addTokens(out, text);
        return out;
    }

    private static void addTokens(Set<String> out, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        String spaced = splitCamelCase(text);
        for (String raw : spaced.split("[^A-Za-z0-9]+")) {
            if (raw.length() >= MIN_LEN) {
                out.add(raw.toLowerCase(Locale.ROOT));
            }
        }
    }

    /** Insert a space at every lowercase/digit → uppercase boundary so camelCase words split. */
    private static String splitCamelCase(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (i > 0 && Character.isUpperCase(c) && !Character.isUpperCase(s.charAt(i - 1))) {
                sb.append(' ');
            }
            sb.append(c);
        }
        return sb.toString();
    }
}
