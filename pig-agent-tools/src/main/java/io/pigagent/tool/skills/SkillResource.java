package io.pigagent.tool.skills;

import java.io.IOException;

/**
 * A supporting file that a composite {@link Skill} directory carries alongside its {@code SKILL.md}
 * (templates, examples, data, …). Surfaced by {@code loadSkill} as a bounded listing; small text
 * resources may be inlined.
 *
 * <p>{@link #read()} is lazy — a descriptor can be listed by path/size without reading its bytes, so
 * a large or binary resource costs nothing until (and unless) it is actually inlined.
 */
public interface SkillResource {

    /** Path relative to the skill directory (e.g. {@code "templates/pr.md"}); uses {@code /} separators. */
    String path();

    /** Size in bytes. */
    long size();

    /** Best-effort "is this human-readable text?" (drives whether {@code loadSkill} inlines it). */
    boolean isText();

    /** Read the resource as UTF-8 text. Lazy; only called when the resource is small enough to inline. */
    String read() throws IOException;
}
