package io.pigagent.skills.builtin;

import io.pigagent.tool.skills.Skill;
import io.pigagent.tool.skills.SkillManifestParser;
import io.pigagent.tool.skills.SkillMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * A {@link Skill} backed by a {@code SKILL.md} resource on the classpath (shipped inside this
 * module's jar under {@code skills/<name>/SKILL.md}). Reads are <b>progressive</b>: {@link #metadata()}
 * parses only the resource head (front-matter + first line) for cheap listing, while {@link #content()}
 * reads the full resource and returns the body with any front-matter stripped. Both are lazy via the
 * classloader, so enumerating the catalog never touches the resource stream.
 */
final class ClasspathSkill implements Skill {

    private static final Logger log = LoggerFactory.getLogger(ClasspathSkill.class);
    private static final int HEAD_MAX_LINES = 200;

    private final String name;
    private final String resourcePath;
    private final ClassLoader loader;
    private final SkillManifestParser parser = SkillManifestParser.defaults();

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
    public SkillMetadata metadata() {
        try {
            return parser.parse(readHead(), name).metadata();
        } catch (IOException e) {
            log.warn("Failed to read metadata for built-in skill '{}': {}", name, e.toString());
            return SkillMetadata.ofName(name);
        }
    }

    @Override
    public String content() throws IOException {
        try (InputStream in = loader.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IOException("built-in skill resource not found: " + resourcePath);
            }
            String raw = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return parser.parse(raw, name).body();
        }
    }

    /** Read a bounded head of the resource (front-matter + first body line live at the top). */
    private String readHead() throws IOException {
        try (InputStream in = loader.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IOException("built-in skill resource not found: " + resourcePath);
            }
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                int lines = 0;
                while (lines < HEAD_MAX_LINES && (line = r.readLine()) != null) {
                    sb.append(line).append('\n');
                    lines++;
                }
            }
            return sb.toString();
        }
    }
}
