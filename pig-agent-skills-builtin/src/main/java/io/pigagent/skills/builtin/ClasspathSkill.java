package io.pigagent.skills.builtin;

import io.pigagent.tool.skills.Skill;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * A {@link Skill} backed by a {@code SKILL.md} resource on the classpath (shipped inside this
 * module's jar under {@code skills/<name>/SKILL.md}). Content is read lazily via the classloader on
 * {@link #content()}, so enumerating the catalog never touches the resource stream.
 */
final class ClasspathSkill implements Skill {

    private final String name;
    private final String resourcePath;
    private final ClassLoader loader;

    ClasspathSkill(String name, String resourcePath, ClassLoader loader) {
        this.name = name;
        this.resourcePath = resourcePath;
        this.loader = loader;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String content() throws IOException {
        try (InputStream in = loader.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IOException("built-in skill resource not found: " + resourcePath);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
