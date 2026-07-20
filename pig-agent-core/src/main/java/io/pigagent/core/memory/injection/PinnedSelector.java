package io.pigagent.core.memory.injection;

/**
 * Pure, deterministic selection of the pinned memory core from {@code MEMORY.md} (capability
 * {@code memory-retrieval-injection}). No model call. Bounded by {@code pinnedMaxChars}. When the
 * configured source cannot produce content (e.g. the pinned heading is absent), it degrades to an
 * <b>empty</b> pinned block (retrieval then covers everything) — never throws.
 *
 * <p>Because the pinned core depends only on the {@code MEMORY.md} content and the (query-independent)
 * settings, it is byte-stable across different user queries — the invariant that keeps the pinned
 * injection inside the cached system-prompt prefix.
 */
public final class PinnedSelector {

    private PinnedSelector() {
    }

    /** Select the pinned block from {@code memoryContent} per {@code settings}; {@code ""} when none. */
    public static String select(String memoryContent, MemoryInjectionSettings settings) {
        if (memoryContent == null || memoryContent.isBlank() || settings == null) {
            return "";
        }
        String selected = switch (settings.pinnedSource()) {
            case HEAD -> memoryContent.strip();
            case HEADING -> headingSection(memoryContent, settings.pinnedHeading());
        };
        return truncate(selected, settings.pinnedMaxChars());
    }

    /** Extract the section under the first Markdown heading (any level) whose title matches {@code heading}. */
    private static String headingSection(String content, String heading) {
        String[] lines = content.split("\n", -1);
        int start = -1;
        int startLevel = 0;
        for (int i = 0; i < lines.length; i++) {
            int level = headingLevel(lines[i]);
            if (level > 0 && headingTitle(lines[i], level).equalsIgnoreCase(heading)) {
                start = i;
                startLevel = level;
                break;
            }
        }
        if (start < 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < lines.length; i++) {
            if (i > start) {
                int level = headingLevel(lines[i]);
                if (level > 0 && level <= startLevel) {
                    break; // the next same-or-higher-level heading ends the section
                }
            }
            sb.append(lines[i]).append('\n');
        }
        return sb.toString().strip();
    }

    /** The heading level (number of leading '#') when the line is an ATX heading, else 0. */
    private static int headingLevel(String line) {
        if (line == null) {
            return 0;
        }
        String t = line.stripLeading();
        int n = 0;
        while (n < t.length() && t.charAt(n) == '#') {
            n++;
        }
        return (n > 0 && n < t.length() && t.charAt(n) == ' ') ? n : 0;
    }

    private static String headingTitle(String line, int level) {
        return line.stripLeading().substring(level).strip();
    }

    private static String truncate(String s, int maxChars) {
        if (s == null || maxChars <= 0) {
            return "";
        }
        String stripped = s.strip();
        return stripped.length() <= maxChars ? stripped : stripped.substring(0, maxChars).strip();
    }
}
