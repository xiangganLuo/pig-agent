package io.pigagent.tool.skills;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Tolerant, dependency-free parser for an optional {@code ---}-fenced YAML front-matter at the top of
 * a {@code SKILL.md}. Recognises the keys {@code name}, {@code description}, {@code version},
 * {@code keywords} and {@code when-to-use}; {@code keywords}/{@code when-to-use} accept either an
 * inline comma list ({@code a, b, c}) or a {@code - item} block list.
 *
 * <p>Never throws: missing front-matter derives the description from the first body line; a start
 * fence with no closing fence (malformed) degrades to "whole text is the body". A hand-rolled
 * mini-parser (not a full YAML library) — front-matter is a handful of simple lines, and this keeps
 * the module dependency-free and inherently fault-tolerant.
 */
final class FrontMatterManifestParser implements SkillManifestParser {

    static final FrontMatterManifestParser INSTANCE = new FrontMatterManifestParser();

    private static final String FENCE = "---";
    private static final int MAX_DESCRIPTION = 200;

    @Override
    public SkillManifest parse(String text, String fallbackName) {
        String src = text == null ? "" : text;
        String name = fallbackName == null ? "" : fallbackName;
        String[] lines = src.split("\n", -1);
        int start = frontMatterStart(lines);
        int end = start < 0 ? -1 : closingFence(lines, start);
        if (start < 0 || end < 0) {
            // No front-matter, or malformed (no closing fence) → whole text is the body (tolerant).
            return new SkillManifest(derive(name, src), src);
        }
        SkillMetadata meta = parseFrontMatter(lines, start + 1, end, name);
        String body = stripLeadingNewlines(join(lines, end + 1));
        if (meta.description().isBlank()) {
            meta = new SkillMetadata(meta.name(), deriveDescription(body), meta.keywords(), meta.version());
        }
        return new SkillManifest(meta, body);
    }

    /** Index of the opening fence (first non-blank line == {@code ---}), else -1. */
    private static int frontMatterStart(String[] lines) {
        for (int i = 0; i < lines.length; i++) {
            String t = lines[i].trim();
            if (t.isEmpty()) {
                continue;
            }
            return t.equals(FENCE) ? i : -1;
        }
        return -1;
    }

    /** Index of the first closing fence after {@code start}, else -1 (malformed). */
    private static int closingFence(String[] lines, int start) {
        for (int i = start + 1; i < lines.length; i++) {
            if (lines[i].trim().equals(FENCE)) {
                return i;
            }
        }
        return -1;
    }

    private static SkillMetadata parseFrontMatter(String[] lines, int from, int toExclusive, String fallbackName) {
        String name = fallbackName;
        String description = "";
        String version = "";
        List<String> keywords = new ArrayList<>();
        String listKey = null;
        for (int i = from; i < toExclusive; i++) {
            String trimmed = lines[i].trim();
            if (trimmed.isEmpty()) {
                listKey = null;
                continue;
            }
            if (trimmed.startsWith("-")) {
                if (listKey != null) {
                    addItem(keywords, trimmed.substring(1).trim());
                }
                continue;
            }
            int colon = trimmed.indexOf(':');
            if (colon < 0) {
                listKey = null;
                continue;
            }
            String key = trimmed.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = stripQuotes(trimmed.substring(colon + 1).trim());
            switch (key) {
                case "name" -> { if (!value.isEmpty()) name = value; listKey = null; }
                case "description" -> { description = value; listKey = null; }
                case "version" -> { version = value; listKey = null; }
                case "keywords", "when-to-use" -> listKey = applyKeywords(keywords, value);
                default -> listKey = null;
            }
        }
        return new SkillMetadata(name, description, keywords, version);
    }

    /** Inline value → split now (no open list); empty value → open a block list for following items. */
    private static String applyKeywords(List<String> keywords, String value) {
        if (value.isEmpty()) {
            return "keywords";
        }
        for (String part : value.split(",")) {
            addItem(keywords, part.trim());
        }
        return null;
    }

    private static void addItem(List<String> keywords, String item) {
        String cleaned = stripQuotes(item.trim());
        if (!cleaned.isEmpty()) {
            keywords.add(cleaned);
        }
    }

    private static SkillMetadata derive(String name, String body) {
        return new SkillMetadata(name, deriveDescription(body), List.of(), "");
    }

    /** First non-blank body line, with leading {@code #}/whitespace removed and length-capped. */
    private static String deriveDescription(String body) {
        for (String line : body.split("\n", -1)) {
            String t = line.trim();
            if (t.isEmpty()) {
                continue;
            }
            String cleaned = t.replaceFirst("^#+\\s*", "").trim();
            if (cleaned.isEmpty()) {
                continue;
            }
            return cleaned.length() > MAX_DESCRIPTION ? cleaned.substring(0, MAX_DESCRIPTION) + "…" : cleaned;
        }
        return "";
    }

    private static String stripQuotes(String v) {
        if (v.length() >= 2
                && ((v.startsWith("\"") && v.endsWith("\"")) || (v.startsWith("'") && v.endsWith("'")))) {
            return v.substring(1, v.length() - 1);
        }
        return v;
    }

    private static String join(String[] lines, int from) {
        if (from >= lines.length) {
            return "";
        }
        return String.join("\n", List.of(lines).subList(from, lines.length));
    }

    private static String stripLeadingNewlines(String s) {
        int i = 0;
        while (i < s.length() && (s.charAt(i) == '\n' || s.charAt(i) == '\r')) {
            i++;
        }
        return s.substring(i);
    }
}
