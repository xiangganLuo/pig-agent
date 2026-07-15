package io.pigagent.tool.skills;

/** Test helper: an in-memory {@link Skill} with fixed name + content (never throws). */
final class FixedSkill implements Skill {

    private final String name;
    private final String content;

    FixedSkill(String name, String content) {
        this.name = name;
        this.content = content;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String content() {
        return content;
    }
}
