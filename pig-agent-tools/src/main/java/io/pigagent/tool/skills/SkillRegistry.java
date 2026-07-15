package io.pigagent.tool.skills;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Composes several {@link SkillSource}s into one view for {@code SkillsTool}. Sources are given in
 * <b>priority order (highest first)</b>; when two sources contribute a skill of the same name, the
 * higher-priority source wins (first-seen locks the name). Wiring the workspace source before the
 * built-in classpath source is what lets a user <b>override / shadow</b> a built-in skill by dropping
 * a same-named {@code SKILL.md} into {@code workspace/skills/}.
 *
 * <p>Fault-tolerant: each source's {@code discover()} is wrapped so a throwing source is skipped
 * (defence in depth — sources are already contract-bound not to throw).
 */
public final class SkillRegistry {

    private static final Logger log = LoggerFactory.getLogger(SkillRegistry.class);

    private final List<SkillSource> sources;

    /** @param sources discovery sources in priority order (highest first); {@code null} → none. */
    public SkillRegistry(List<SkillSource> sources) {
        this.sources = sources == null ? List.of() : List.copyOf(sources);
    }

    /**
     * All skills across the sources, de-duplicated by name with the highest-priority source winning.
     * Insertion order reflects discovery order (highest-priority source's skills first).
     */
    public List<Skill> all() {
        Map<String, Skill> byName = new LinkedHashMap<>();
        for (SkillSource source : sources) {
            for (Skill skill : safeDiscover(source)) {
                if (skill == null) {
                    continue;
                }
                String name = skill.name();
                if (name == null || name.isBlank()) {
                    continue;
                }
                byName.putIfAbsent(name, skill); // first (highest priority) wins
            }
        }
        return List.copyOf(byName.values());
    }

    /** Distinct skill names across all sources, sorted alphabetically (deterministic). */
    public List<String> listNames() {
        return all().stream().map(Skill::name).sorted().toList();
    }

    /** Resolve a skill by name; the highest-priority source's version wins (workspace over built-in). */
    public Optional<Skill> find(String name) {
        if (name == null) {
            return Optional.empty();
        }
        return all().stream().filter(s -> name.equals(s.name())).findFirst();
    }

    private List<Skill> safeDiscover(SkillSource source) {
        if (source == null) {
            return List.of();
        }
        try {
            List<Skill> found = source.discover();
            return found == null ? List.of() : found;
        } catch (Throwable t) {
            log.warn("Skill source '{}' failed, skipping: {}", sourceName(source), t.toString());
            return new ArrayList<>();
        }
    }

    private static String sourceName(SkillSource source) {
        try {
            return source.name();
        } catch (Throwable t) {
            return source.getClass().getSimpleName();
        }
    }
}
