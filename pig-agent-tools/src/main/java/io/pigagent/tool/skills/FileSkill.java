package io.pigagent.tool.skills;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A {@link Skill} backed by a workspace file (typically {@code workspace/skills/<name>/SKILL.md}).
 * Content is read lazily on {@link #content()} so listing never touches disk.
 */
final class FileSkill implements Skill {

    private final String name;
    private final Path skillFile;

    FileSkill(String name, Path skillFile) {
        this.name = name;
        this.skillFile = skillFile;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String content() throws IOException {
        return Files.readString(skillFile);
    }
}
