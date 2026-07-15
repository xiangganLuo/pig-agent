package io.pigagent.tool.skills.spi;

import io.pigagent.tool.skills.Skill;

import java.util.List;

/**
 * SPI for a module that ships a set of built-in {@link Skill}s on the classpath. A provider is
 * discovered by {@code ClasspathSkillSource} via {@link java.util.ServiceLoader}, declared in
 * {@code META-INF/services/io.pigagent.tool.skills.spi.SkillProvider}.
 *
 * <p>Mirrors {@code io.pigagent.plugin.Plugin}: "ship a set of built-in capabilities = implement one
 * SPI + add a service line", with no central assembly edit. A provider that throws is isolated by the
 * source (fault-safe), so one bad provider never hides the others.
 */
public interface SkillProvider {

    /** The skills this provider contributes; returns empty (never {@code null}) when none. */
    List<Skill> skills();
}
