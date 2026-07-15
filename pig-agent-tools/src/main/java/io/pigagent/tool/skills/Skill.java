package io.pigagent.tool.skills;

import java.io.IOException;
import java.util.List;

/**
 * A named capability pack the agent can load — a composite {@code SKILL.md} bundle. Skills come from
 * several {@link SkillSource}s (the built-in classpath set, the workspace directory, …) and are
 * merged by {@link SkillRegistry}.
 *
 * <p><b>Progressive loading.</b> {@link #metadata()} is a cheap snapshot (name + optional description)
 * read at <em>listing</em> time — a file-backed skill parses only the {@code SKILL.md} head, never the
 * whole body — so {@code listSkills} stays token-cheap even with many entries. {@link #content()} reads
 * the full body only when a skill is actually loaded (via {@code loadSkill}). Both declare / handle
 * failure so a read error renders as a friendly message and is never surfaced to the model as an
 * exception.
 *
 * <p>{@link #metadata()} and {@link #supportingFiles()} are default methods, so existing
 * implementations keep compiling: a plain {@code SKILL.md}-only skill derives its metadata from the
 * name and has no supporting files.
 */
public interface Skill {

    /** Stable skill name (the directory / resource name, e.g. {@code "code-review"}). Never blank. */
    String name();

    /** Read and return the skill's {@code SKILL.md} body (front-matter stripped). Lazy; may throw. */
    String content() throws IOException;

    /**
     * Cheap metadata for listing (progressive loading): parsed from the {@code SKILL.md} head /
     * front-matter, or derived from the name. Default derives from {@link #name()} only.
     */
    default SkillMetadata metadata() {
        return SkillMetadata.ofName(name());
    }

    /**
     * Supporting files carried alongside {@code SKILL.md} in a composite skill directory. Default is
     * empty (a single-file skill). Never {@code null}; implementations must not throw — a listing
     * failure degrades to fewer/no supporting files.
     */
    default List<SkillResource> supportingFiles() {
        return List.of();
    }
}
