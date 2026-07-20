package io.pigagent.tool.skills;

import java.nio.charset.StandardCharsets;

/**
 * A {@link SkillResource} backed by an in-memory string, as carried by a native
 * {@code io.agentscope.core.skill.AgentSkill}'s {@code getResources()} map (path → content). Unlike
 * {@link FileSkillResource} there is no lazy file read — the native repository already materialised the
 * content in memory, so {@link #read()} simply returns it.
 */
final class NativeSkillResource implements SkillResource {

    private final String path;
    private final String content;

    NativeSkillResource(String path, String content) {
        this.path = path == null ? "" : path;
        this.content = content == null ? "" : content;
    }

    @Override
    public String path() {
        return path;
    }

    @Override
    public long size() {
        return content.getBytes(StandardCharsets.UTF_8).length;
    }

    /** Native resources arrive as strings; treat as text unless a NUL byte appears (mirrors FileSkill). */
    @Override
    public boolean isText() {
        return content.indexOf('\0') < 0;
    }

    @Override
    public String read() {
        return content;
    }
}
