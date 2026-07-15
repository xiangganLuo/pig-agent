package io.pigagent.tool.skills;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * A {@link Skill} backed by a workspace skill directory ({@code workspace/skills/<name>/}, holding
 * {@code SKILL.md} plus optional supporting files). Reads are <b>progressive</b>: {@link #metadata()}
 * parses only the {@code SKILL.md} head (front-matter + first line), {@link #content()} reads the full
 * body (front-matter stripped), and {@link #supportingFiles()} enumerates the sibling files bounded by
 * {@link SkillLimits} and confined to the skill directory (symlink escapes dropped).
 */
final class FileSkill implements Skill {

    private static final Logger log = LoggerFactory.getLogger(FileSkill.class);
    private static final String SKILL_FILE = "SKILL.md";
    private static final int HEAD_MAX_LINES = 200;
    private static final int HEAD_MAX_BYTES = 64 * 1024;
    private static final int WALK_MAX_DEPTH = 8;
    private static final int TEXT_SNIFF_BYTES = 4096;

    private final String name;
    private final Path skillDir;
    private final Path skillFile;
    private final SkillLimits limits;
    private final SkillManifestParser parser = SkillManifestParser.defaults();

    FileSkill(String name, Path skillDir, SkillLimits limits) {
        this.name = name;
        this.skillDir = skillDir;
        this.skillFile = skillDir.resolve(SKILL_FILE);
        this.limits = limits == null ? SkillLimits.defaults() : limits;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public SkillMetadata metadata() {
        try {
            return parser.parse(readHead(skillFile), name).metadata();
        } catch (IOException e) {
            log.warn("Failed to read metadata for skill '{}': {}", name, e.toString());
            return SkillMetadata.ofName(name);
        }
    }

    @Override
    public String content() throws IOException {
        return parser.parse(Files.readString(skillFile, StandardCharsets.UTF_8), name).body();
    }

    @Override
    public List<SkillResource> supportingFiles() {
        if (skillDir == null || !Files.isDirectory(skillDir)) {
            return List.of();
        }
        Path root = SkillSecurity.realOrNormalized(skillDir);
        List<SkillResource> out = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(skillDir, WALK_MAX_DEPTH)) {
            List<Path> files = walk.filter(Files::isRegularFile)
                    .filter(p -> !p.equals(skillFile))
                    .filter(p -> SkillSecurity.isWithin(root, p))
                    .sorted(Comparator.comparing(p -> skillDir.relativize(p).toString()))
                    .limit(limits.maxSupportingFiles())
                    .toList();
            for (Path p : files) {
                addResource(out, p);
            }
        } catch (IOException | RuntimeException e) {
            log.warn("Failed to enumerate supporting files for skill '{}': {}", name, e.toString());
        }
        return out;
    }

    private void addResource(List<SkillResource> out, Path p) {
        try {
            String rel = skillDir.relativize(p).toString().replace('\\', '/');
            out.add(new FileSkillResource(rel, p, Files.size(p), isProbablyText(p)));
        } catch (IOException e) {
            log.warn("Skipping unreadable supporting file '{}' in skill '{}': {}", p, name, e.toString());
        }
    }

    /** Read up to a bounded head of the file (front-matter + first body line live at the top). */
    private static String readHead(Path file) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            int lines = 0;
            while (lines < HEAD_MAX_LINES && sb.length() < HEAD_MAX_BYTES && (line = r.readLine()) != null) {
                sb.append(line).append('\n');
                lines++;
            }
        }
        return sb.toString();
    }

    /** Heuristic: a file is text unless a NUL byte appears in its leading sample. */
    private static boolean isProbablyText(Path p) throws IOException {
        byte[] sample;
        try (var in = Files.newInputStream(p)) {
            sample = in.readNBytes(TEXT_SNIFF_BYTES);
        }
        for (byte b : sample) {
            if (b == 0) {
                return false;
            }
        }
        return true;
    }
}
