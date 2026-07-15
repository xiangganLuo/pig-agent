package io.pigagent.tool.skills;

import java.util.List;

/**
 * A source that discovers {@link Skill}s (the built-in classpath set, the workspace {@code skills/}
 * directory, …). Discovery MUST be fault-tolerant: an implementation should never throw — it returns
 * whatever it could find and swallows/logs the rest — so a broken source degrades to "no skills"
 * instead of breaking {@code listSkills}/{@code loadSkill}.
 *
 * <p>Single abstract method, so tests can supply a source as a lambda. Mirrors
 * {@code io.pigagent.plugin.PluginSource} by design — skill discovery and plugin discovery are the
 * same shape (multi-source, fault-tolerant, composable).
 */
@FunctionalInterface
public interface SkillSource {

    /** Discover skills from this source; returns empty (never {@code null}) when none/failure. */
    List<Skill> discover();

    /** Human-readable name for logs; defaults to the implementation's simple class name. */
    default String name() {
        return getClass().getSimpleName();
    }
}
