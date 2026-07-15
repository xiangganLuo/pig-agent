package io.pigagent.tool.skills;

import io.pigagent.tool.skills.spi.SkillProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.ServiceLoader;

/**
 * The built-in skill source: discovers {@link SkillProvider}s declared in
 * {@code META-INF/services/io.pigagent.tool.skills.spi.SkillProvider} on a classpath (via
 * {@link ServiceLoader}) and aggregates the skills they contribute. Mirrors
 * {@code io.pigagent.plugin.ServiceLoaderPluginSource}.
 *
 * <p>Fault-tolerant: a single bad service line yields a {@link java.util.ServiceConfigurationError},
 * and a provider whose {@code skills()} throws is logged and skipped — neither aborts discovery of the
 * rest. When no {@code SkillProvider} is on the classpath (e.g. the {@code pig-agent-skills-builtin}
 * module is absent), discovery returns empty and the composed registry degrades to workspace-only.
 */
public final class ClasspathSkillSource implements SkillSource {

    private static final Logger log = LoggerFactory.getLogger(ClasspathSkillSource.class);

    private final ClassLoader classLoader;

    /** Use the current thread's context classloader (falling back to this class's loader). */
    public ClasspathSkillSource() {
        this(Thread.currentThread().getContextClassLoader());
    }

    public ClasspathSkillSource(ClassLoader classLoader) {
        this.classLoader = classLoader != null ? classLoader : ClasspathSkillSource.class.getClassLoader();
    }

    @Override
    public String name() {
        return "classpath";
    }

    @Override
    public List<Skill> discover() {
        List<Skill> skills = new ArrayList<>();
        Iterator<SkillProvider> it = ServiceLoader.load(SkillProvider.class, classLoader).iterator();
        while (true) {
            SkillProvider provider;
            try {
                if (!it.hasNext()) {
                    break;
                }
                provider = it.next();
            } catch (Throwable t) {
                log.warn("SkillProvider failed to load from classpath, skipping: {}", t.toString());
                continue;
            }
            addProviderSkills(provider, skills);
        }
        return skills;
    }

    private static void addProviderSkills(SkillProvider provider, List<Skill> skills) {
        try {
            List<Skill> contributed = provider.skills();
            if (contributed != null) {
                for (Skill skill : contributed) {
                    if (skill != null) {
                        skills.add(skill);
                    }
                }
            }
        } catch (Throwable t) {
            log.warn("SkillProvider '{}' failed to enumerate skills, skipping: {}",
                    provider.getClass().getName(), t.toString());
        }
    }
}
