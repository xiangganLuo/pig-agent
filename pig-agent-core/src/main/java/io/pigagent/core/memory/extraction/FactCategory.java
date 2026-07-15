package io.pigagent.core.memory.extraction;

/**
 * Classification of an extracted memory fact. Small enum registry: each constant carries a stable
 * wire {@link #label()} (used in the extractor prompt, the model's JSON, and the persisted store),
 * and {@link #fromLabel(String, FactCategory)} parses fault-tolerantly — an unknown/blank label
 * falls back to the supplied default rather than throwing, so a slightly-off model output never
 * breaks extraction.
 */
public enum FactCategory {

    /** A durable preference of the user (language, tone, tooling choice, …). */
    USER_PREFERENCE("user-preference"),
    /** A stable fact about the project/codebase (build tool, module layout, conventions, …). */
    PROJECT_FACT("project-fact"),
    /** A reference/pointer the user will want later (a URL, a file path, an identifier, …). */
    REFERENCE("reference");

    private final String label;

    FactCategory(String label) {
        this.label = label;
    }

    /** The stable wire label used across prompt / JSON / persistence. */
    public String label() {
        return label;
    }

    /**
     * Parse a label to its category, case-insensitively and tolerating minor separators; returns
     * {@code fallback} (never null unless fallback is null) for null/blank/unknown input.
     */
    public static FactCategory fromLabel(String raw, FactCategory fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String norm = raw.strip().toLowerCase().replace('_', '-').replace(' ', '-');
        for (FactCategory c : values()) {
            if (c.label.equals(norm) || c.name().equalsIgnoreCase(raw.strip())) {
                return c;
            }
        }
        return fallback;
    }
}
