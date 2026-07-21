package io.pigagent.core.memory.quality;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a {@code MEMORY.md} into ordered {@link Segment}s for the semantic-dedup curator — capability
 * {@code memory-consolidation-quality}. Each source line becomes a segment tagged as either a
 * <b>fact</b> unit (a content bullet / paragraph line that participates in dedup) or a <b>structural</b>
 * line (a Markdown heading {@code #…} or a blank line) that is kept verbatim. Keeping headings as
 * structural segments is what lets the curator rewrite {@code MEMORY.md} in place while <b>preserving the
 * layer format contract</b> (D6): only near-duplicate fact bullets are dropped; section headers and the
 * layering they express are never touched.
 *
 * <p>Pure/deterministic, line-granular (one segment per source line). Rebuilding by concatenating the
 * surviving segments' text (each followed by a newline) reproduces the file minus the dropped facts.
 */
public final class FactUnitSplitter {

    /** One source line: its verbatim text plus whether it is a dedup-eligible fact unit. */
    public record Segment(String text, boolean fact) {
    }

    private FactUnitSplitter() {
    }

    /** Split {@code md} into ordered per-line segments (empty list for {@code null}/empty input). */
    public static List<Segment> split(String md) {
        List<Segment> segments = new ArrayList<>();
        if (md == null || md.isEmpty()) {
            return segments;
        }
        for (String line : md.split("\n", -1)) {
            segments.add(new Segment(line, isFactLine(line)));
        }
        return segments;
    }

    /** Convenience: the fact-unit texts in order (what the deduplicator scores). */
    public static List<String> factTexts(List<Segment> segments) {
        List<String> facts = new ArrayList<>();
        for (Segment s : segments) {
            if (s.fact()) {
                facts.add(s.text());
            }
        }
        return facts;
    }

    /**
     * A line is a dedup-eligible fact unit when it has content and is not a Markdown heading. Blank lines
     * and headings ({@code #}…{@code ######}) are structural (kept verbatim, never deduped).
     */
    private static boolean isFactLine(String line) {
        String stripped = line.strip();
        if (stripped.isEmpty()) {
            return false; // blank → structural
        }
        return !isHeading(stripped);
    }

    private static boolean isHeading(String stripped) {
        int hashes = 0;
        while (hashes < stripped.length() && stripped.charAt(hashes) == '#') {
            hashes++;
        }
        return hashes > 0 && hashes <= 6
                && (hashes == stripped.length() || Character.isWhitespace(stripped.charAt(hashes)));
    }
}
