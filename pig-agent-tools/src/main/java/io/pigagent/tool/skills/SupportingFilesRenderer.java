package io.pigagent.tool.skills;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;

/**
 * Renders a composite skill's supporting files into the bounded "Supporting files" section appended by
 * {@code loadSkill}. Pure presentation: lists each file's relative path + human-readable size, inlines
 * a small text file's content in a fenced block, and marks binary / over-limit files as not inlined.
 *
 * <p>Bounded by {@link SkillLimits}: a file is only inlined when it is text, within the per-file cap,
 * and the running inline total stays within the overall cap — so a skill directory with large or many
 * supporting files can never blow up the model context.
 */
final class SupportingFilesRenderer {

    private static final Logger log = LoggerFactory.getLogger(SupportingFilesRenderer.class);

    private SupportingFilesRenderer() {
    }

    /** @return the section text (leading with a blank line + separator), or {@code ""} when no files. */
    static String render(List<SkillResource> resources, SkillLimits limits) {
        if (resources == null || resources.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("\n\n---\nSupporting files (").append(resources.size()).append("):");
        long inlinedTotal = 0;
        for (SkillResource r : resources) {
            sb.append("\n- ").append(r.path()).append(" (").append(humanSize(r.size()));
            boolean canInline = r.isText()
                    && r.size() <= limits.maxSupportingFileBytes()
                    && inlinedTotal + r.size() <= limits.maxInlineBytes();
            if (!r.isText()) {
                sb.append(", binary");
            } else if (!canInline) {
                sb.append(", not inlined");
            }
            sb.append(')');
            if (canInline) {
                inlinedTotal += appendInline(sb, r);
            }
        }
        return sb.toString();
    }

    private static long appendInline(StringBuilder sb, SkillResource r) {
        try {
            String text = r.read();
            sb.append("\n```\n").append(text);
            if (!text.endsWith("\n")) {
                sb.append('\n');
            }
            sb.append("```");
            return r.size();
        } catch (IOException e) {
            log.warn("Failed to inline supporting file '{}': {}", r.path(), e.toString());
            sb.append("\n  (unreadable)");
            return 0;
        }
    }

    private static String humanSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double kib = bytes / 1024.0;
        if (kib < 1024) {
            return String.format("%.1f KB", kib);
        }
        return String.format("%.1f MB", kib / 1024.0);
    }
}
