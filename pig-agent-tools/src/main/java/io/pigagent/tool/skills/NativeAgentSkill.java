package io.pigagent.tool.skills;

import io.agentscope.core.skill.AgentSkill;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Adapts a native {@code io.agentscope.core.skill.AgentSkill} to pig's {@link Skill} (the "engine, not
 * mouth" bridge — see {@link NativeRepositorySkillSource}). The mapping is deliberately shallow: the
 * native repository has already parsed the skill, so metadata comes straight from the native
 * {@code getName()}/{@code getDescription()}/{@code getMetadata()} rather than re-parsing, and the body
 * comes from {@code getSkillContent()} with any front-matter stripped so it opens with its heading —
 * matching {@link FileSkill#content()} so the two read paths look identical to {@code SkillsTool}.
 *
 * <p>Progressive-loading semantics are preserved: {@link #metadata()} touches only the in-memory native
 * fields (no body scan), {@link #content()} returns the (stripped) body, {@link #supportingFiles()}
 * adapts {@code getResources()} (path → content) into in-memory {@link NativeSkillResource}s.
 */
final class NativeAgentSkill implements Skill {

    private final AgentSkill delegate;
    private final SkillManifestParser parser = SkillManifestParser.defaults();

    NativeAgentSkill(AgentSkill delegate) {
        this.delegate = delegate;
    }

    @Override
    public String name() {
        return delegate.getName();
    }

    /** Body with any front-matter stripped (tolerant — no front-matter ⇒ the whole content is body). */
    @Override
    public String content() {
        String raw = delegate.getSkillContent();
        return parser.parse(raw == null ? "" : raw, name()).body();
    }

    @Override
    public SkillMetadata metadata() {
        Map<String, Object> md = delegate.getMetadata();
        return new SkillMetadata(delegate.getName(), delegate.getDescription(),
                keywordsFrom(md), versionFrom(md));
    }

    @Override
    public List<SkillResource> supportingFiles() {
        Map<String, String> resources = delegate.getResources();
        if (resources == null || resources.isEmpty()) {
            return List.of();
        }
        List<SkillResource> out = new ArrayList<>();
        for (Map.Entry<String, String> e : resources.entrySet()) {
            if (e.getKey() == null) {
                continue;
            }
            out.add(new NativeSkillResource(e.getKey().replace('\\', '/'), e.getValue()));
        }
        return out;
    }

    /** {@code keywords} (falling back to {@code when-to-use}) from the native metadata map. */
    private static List<String> keywordsFrom(Map<String, Object> md) {
        if (md == null) {
            return List.of();
        }
        Object kw = md.get("keywords");
        if (kw == null) {
            kw = md.get("when-to-use");
        }
        return toStringList(kw);
    }

    private static String versionFrom(Map<String, Object> md) {
        if (md == null || md.get("version") == null) {
            return "";
        }
        return String.valueOf(md.get("version"));
    }

    /** Accept either a YAML list ({@code List<?>}) or an inline comma string; blank items dropped. */
    private static List<String> toStringList(Object value) {
        if (value == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object o : list) {
                addIfNotBlank(out, o);
            }
            return out;
        }
        for (String part : String.valueOf(value).split(",")) {
            addIfNotBlank(out, part);
        }
        return out;
    }

    private static void addIfNotBlank(List<String> out, Object item) {
        if (item == null) {
            return;
        }
        String s = String.valueOf(item).trim();
        if (!s.isEmpty()) {
            out.add(s);
        }
    }
}
