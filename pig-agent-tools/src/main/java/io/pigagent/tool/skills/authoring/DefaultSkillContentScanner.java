package io.pigagent.tool.skills.authoring;

import io.pigagent.tool.contract.CredentialSanitizer;
import io.pigagent.tool.skills.SkillLimits;
import io.pigagent.tool.skills.SkillManifest;
import io.pigagent.tool.skills.SkillManifestParser;
import io.pigagent.tool.skills.SkillSecurity;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Default {@link SkillContentScanner}: four tolerant checks over a staged skill, reusing the existing
 * guards rather than reinventing them —
 * <ol>
 *   <li><b>name</b> — {@link SkillSecurity#isValidSkillName} (rejects {@code ../}, path separators,
 *       {@code .}/{@code ..}, reserved dot-prefixed names);</li>
 *   <li><b>size</b> — the {@code SKILL.md} bytes MUST be within {@link SkillLimits#maxSkillBytes};</li>
 *   <li><b>credentials</b> — {@link CredentialSanitizer} MUST NOT find a secret-looking value (the
 *       reason never echoes the matched value);</li>
 *   <li><b>structure</b> — the front-matter MUST parse and {@code name}/{@code description}/{@code body}
 *       be non-blank.</li>
 * </ol>
 * Never throws: any internal failure becomes a rejection reason.
 */
public final class DefaultSkillContentScanner implements SkillContentScanner {

    private final SkillLimits limits;
    private final SkillManifestParser parser;

    public DefaultSkillContentScanner() {
        this(SkillLimits.defaults(), SkillManifestParser.defaults());
    }

    public DefaultSkillContentScanner(SkillLimits limits, SkillManifestParser parser) {
        this.limits = limits == null ? SkillLimits.defaults() : limits;
        this.parser = parser == null ? SkillManifestParser.defaults() : parser;
    }

    @Override
    public SkillScanResult scan(String name, String skillMd) {
        List<String> reasons = new ArrayList<>();
        String text = skillMd == null ? "" : skillMd;
        try {
            if (!SkillSecurity.isValidSkillName(name)) {
                reasons.add("invalid skill name (must be a single safe path segment, not a reserved name)");
            }
            long bytes = text.getBytes(StandardCharsets.UTF_8).length;
            if (bytes > limits.maxSkillBytes()) {
                reasons.add("SKILL.md too large (" + bytes + " bytes > " + limits.maxSkillBytes() + ")");
            }
            if (containsCredential(text)) {
                reasons.add("contains credential-like content (API key / token / secret)");
            }
            SkillManifest manifest = parser.parse(text, name == null ? "" : name);
            if (manifest.metadata().name().isBlank()) {
                reasons.add("missing skill name in content");
            }
            if (manifest.metadata().description().isBlank()) {
                reasons.add("missing description (add a front-matter description or a leading heading)");
            }
            if (manifest.body().isBlank()) {
                reasons.add("empty skill body");
            }
        } catch (RuntimeException e) {
            // Fail-safe: any scanner error is a rejection, never an exception to the caller.
            reasons.add("scan error");
        }
        return reasons.isEmpty() ? SkillScanResult.ok() : SkillScanResult.rejected(reasons);
    }

    /** True iff sanitizing the text changes it — i.e. it contained a secret-looking value. */
    private static boolean containsCredential(String text) {
        return !CredentialSanitizer.sanitize(text).equals(text);
    }
}
