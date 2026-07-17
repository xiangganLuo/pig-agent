package io.pigagent.core.profile;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The curated user profile ({@code USER.md}) store — capability {@code user-profile} (Hermes
 * {@code USER.md} blueprint, OD4-A). A single workspace-level Markdown file holding identity / durable
 * preferences / working style, kept <b>distinct</b> from {@code MEMORY.md} (the general fact ledger of
 * {@code pa-memory-native}). This is the one place that reads, bounds, sanitizes and deterministically
 * merges the profile — used by both the injection middleware ({@link UserProfileContextMiddleware},
 * core) and the {@code updateProfile} tool (tools module).
 *
 * <p><b>Format.</b> A {@code # User Profile} heading followed by field lines of the form
 * {@code - **<Field>**: <value>}. This is human-readable / hand-editable / version-controllable
 * (local-first) and gives {@link #updateField} a deterministic set/merge target.
 *
 * <p><b>Credential-safe.</b> Every value written and every read handed out for injection is passed
 * through {@link SecretRedactor}, so a secret can never land in — or be injected from — {@code USER.md}
 * (defends a hand-edited file too).
 *
 * <p><b>Fault-tolerant.</b> A missing / unreadable file reads as empty (never throws); a write failure
 * is logged at warn and reported as {@code false} rather than thrown — the profile subsystem must never
 * break a turn or crash startup.
 */
public final class UserProfileStore {

    private static final Logger log = LoggerFactory.getLogger(UserProfileStore.class);

    private static final String HEADING = "# User Profile";
    private static final String TRUNCATION_MARKER = "\n… (profile truncated)";

    /** Matches a field line {@code - **<name>**: <value>}; group 1 = name, group 2 = value. */
    private static final Pattern FIELD_LINE =
            Pattern.compile("^\\s*-\\s*\\*\\*(.+?)\\*\\*\\s*:\\s*(.*)$");

    private final Path userMd;

    public UserProfileStore(Path userMd) {
        this.userMd = Objects.requireNonNull(userMd, "userMd");
    }

    /** The backing {@code USER.md} path. */
    public Path path() {
        return userMd;
    }

    /**
     * Read the profile for injection — credential-redacted and bounded to {@code maxChars}
     * (truncated with a marker when longer). A missing / blank / unreadable file yields an empty
     * string (never throws).
     */
    public String read(int maxChars) {
        String content = SecretRedactor.redact(readFull());
        if (content.isBlank()) {
            return "";
        }
        if (maxChars > 0 && content.length() > maxChars) {
            int cut = Math.max(0, maxChars - TRUNCATION_MARKER.length());
            return content.substring(0, cut) + TRUNCATION_MARKER;
        }
        return content;
    }

    /** Read the full raw profile content (empty when absent/unreadable — never throws). */
    public String readFull() {
        try {
            if (!Files.isRegularFile(userMd)) {
                return "";
            }
            return Files.readString(userMd, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            log.debug("Could not read USER.md at {}: {}", userMd, e.getMessage());
            return "";
        }
    }

    /**
     * Deterministically set/merge one profile field: if a line for {@code field} exists (case-insensitive
     * name match) its value is replaced; otherwise a new field line is appended. The field name and value
     * are credential-redacted and internal newlines are collapsed to spaces (so a field stays a single,
     * deterministic list item). Creates the file (with a heading) when absent.
     *
     * @return {@code true} on a successful write; {@code false} on invalid input or an IO failure (safe
     *     degrade, logged) — never throws.
     */
    public boolean updateField(String field, String value) {
        String cleanField = collapse(SecretRedactor.redact(field));
        String cleanValue = collapse(SecretRedactor.redact(value));
        if (cleanField.isBlank()) {
            return false;
        }
        try {
            return writeRaw(merge(readFull(), cleanField, cleanValue));
        } catch (IOException | RuntimeException e) {
            log.warn("Could not update USER.md field '{}' at {}: {}", cleanField, userMd, e.getMessage());
            return false;
        }
    }

    /**
     * Overwrite the whole profile body (used by background consolidation) — credential-redacted before
     * writing. A blank body clears the file to just the heading (keeps a well-formed profile). Fault-
     * tolerant: any failure is logged and reported as {@code false}, never thrown.
     */
    public boolean write(String body) {
        String clean = SecretRedactor.redact(body);
        try {
            return writeRaw(clean.isBlank() ? HEADING + "\n" : clean);
        } catch (IOException | RuntimeException e) {
            log.warn("Could not write USER.md at {}: {}", userMd, e.getMessage());
            return false;
        }
    }

    /** Atomic-ish write via a temp sibling + move (falls back to a direct move if ATOMIC unsupported). */
    private boolean writeRaw(String content) throws IOException {
        if (userMd.getParent() != null) {
            Files.createDirectories(userMd.getParent());
        }
        Path tmp = userMd.resolveSibling(userMd.getFileName() + ".tmp");
        Files.writeString(tmp, content, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, userMd, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicUnsupported) {
            Files.move(tmp, userMd, StandardCopyOption.REPLACE_EXISTING);
        }
        return true;
    }

    /** Merge a field into existing content: replace a same-named line, else append. */
    private static String merge(String existing, String field, String value) {
        String fieldLine = "- **" + field + "**: " + value;
        if (existing == null || existing.isBlank()) {
            return HEADING + "\n\n" + fieldLine + "\n";
        }
        List<String> lines = new ArrayList<>(List.of(existing.split("\n", -1)));
        boolean replaced = false;
        for (int i = 0; i < lines.size(); i++) {
            Matcher m = FIELD_LINE.matcher(lines.get(i));
            if (m.matches() && m.group(1).trim().equalsIgnoreCase(field)) {
                lines.set(i, fieldLine);
                replaced = true;
                break;
            }
        }
        if (replaced) {
            return String.join("\n", lines);
        }
        // Append a new field line, ensuring a trailing newline separation.
        String base = existing.endsWith("\n") ? existing : existing + "\n";
        return base + fieldLine + "\n";
    }

    /** Collapse internal whitespace/newlines to single spaces and trim (keeps a field a single line). */
    private static String collapse(String s) {
        if (s == null) {
            return "";
        }
        return s.replaceAll("\\s+", " ").trim();
    }

    /** The heading a fresh profile file starts with (for tests). */
    static String heading() {
        return HEADING;
    }
}
