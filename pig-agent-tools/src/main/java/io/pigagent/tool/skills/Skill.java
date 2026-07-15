package io.pigagent.tool.skills;

import java.io.IOException;

/**
 * A named capability pack the agent can load — conceptually one {@code SKILL.md}. Skills come from
 * several {@link SkillSource}s (the built-in classpath set, the workspace directory, …) and are
 * merged by {@link SkillRegistry}.
 *
 * <p>Content is <b>lazy</b>: {@link #content()} reads the underlying resource/file only when a skill
 * is actually loaded (via {@code loadSkill}), so listing skills stays cheap even with many entries.
 * {@link #content()} declares {@link IOException} so callers can render a read failure as a friendly
 * message instead of aborting — it is never surfaced to the model as an exception.
 */
public interface Skill {

    /** Stable skill name (the directory / resource name, e.g. {@code "code-review"}). Never blank. */
    String name();

    /** Read and return the skill's {@code SKILL.md} content. Lazy; may throw on a read failure. */
    String content() throws IOException;
}
