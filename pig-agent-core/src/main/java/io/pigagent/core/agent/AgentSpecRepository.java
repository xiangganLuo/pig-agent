package io.pigagent.core.agent;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * File-backed store for {@link AgentSpec}, one file per agent at {@code
 * workspace/agents/{id}.md}: a YAML front-matter block (all fields except the prompt) followed
 * by a Markdown body (= {@link AgentSpec#sysPrompt()}).
 *
 * <p>Fault-tolerant like the other repositories: a single corrupt file is backed up and skipped,
 * never crashing a listing. Unlike {@code FileSystemTaskRepository#parseMarkdown} (which drops
 * fields), this store round-trips every field: {@code save(x)} then {@code findById} yields an
 * equal {@link AgentSpec}.
 */
public final class AgentSpecRepository {

    private static final String DELIMITER = "---";

    private static final ObjectMapper YAML = new ObjectMapper(
            new YAMLFactory().disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER))
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    private final Path agentsDir;

    public AgentSpecRepository(Path agentsDir) {
        this.agentsDir = agentsDir;
    }

    public AgentSpec save(AgentSpec spec) {
        try {
            Files.createDirectories(agentsDir);
            FrontMatter fm = FrontMatter.from(spec);
            String content = DELIMITER + "\n" + YAML.writeValueAsString(fm)
                    + DELIMITER + "\n" + spec.sysPrompt();
            Files.writeString(fileOf(spec.id()), content);
            return spec;
        } catch (IOException e) {
            throw new RuntimeException("Failed to save agent: " + spec.id(), e);
        }
    }

    public Optional<AgentSpec> findById(String id) {
        Path file = fileOf(id);
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(parse(file));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** All agents, most sorted by nothing in particular; corrupt files are backed up and skipped. */
    public List<AgentSpec> findAll() {
        List<AgentSpec> specs = new ArrayList<>();
        if (!Files.isDirectory(agentsDir)) {
            return specs;
        }
        try (Stream<Path> files = Files.list(agentsDir)) {
            files.filter(p -> p.toString().endsWith(".md")).sorted().forEach(p -> {
                try {
                    specs.add(parse(p));
                } catch (Exception e) {
                    backup(p);
                }
            });
        } catch (IOException ignored) {
            // directory unreadable → return whatever we have, never crash
        }
        return specs;
    }

    public void deleteById(String id) {
        try {
            Files.deleteIfExists(fileOf(id));
        } catch (IOException ignored) {
        }
    }

    private Path fileOf(String id) {
        return agentsDir.resolve(id + ".md");
    }

    private AgentSpec parse(Path file) throws IOException {
        String content = Files.readString(file);
        // Split on lines that are exactly the "---" delimiter: parts = [before, frontMatter, body].
        String[] parts = content.split("(?m)^" + DELIMITER + "\\s*\\R", 3);
        if (parts.length < 3) {
            throw new IOException("Malformed agent file (missing front matter): " + file);
        }
        FrontMatter fm = YAML.readValue(parts[1], FrontMatter.class);
        if (fm.id == null || fm.name == null) {
            throw new IOException("Agent file missing id/name: " + file);
        }
        return new AgentSpec(fm.id, fm.name, parts[2], fm.toolNames,
                fm.permissionMode, fm.modelId, fm.maxIters,
                fm.mandate, fm.schedule, fm.commandAllowlist, fm.timeoutSeconds, fm.lastRunAtEpochMs);
    }

    private void backup(Path file) {
        try {
            Files.move(file, file.resolveSibling(file.getFileName() + ".bak"),
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
        }
    }

    /** Serializable view of an {@link AgentSpec} minus the prompt (which lives in the body). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    static final class FrontMatter {
        public String id;
        public String name;
        public List<String> toolNames;
        public String permissionMode;
        public String modelId;
        public int maxIters;
        // autonomous (phase 2)
        public String mandate;
        public String schedule;
        public List<String> commandAllowlist;
        public int timeoutSeconds;
        public long lastRunAtEpochMs;

        static FrontMatter from(AgentSpec spec) {
            FrontMatter fm = new FrontMatter();
            fm.id = spec.id();
            fm.name = spec.name();
            fm.toolNames = spec.toolNames();
            fm.permissionMode = spec.permissionMode();
            fm.modelId = spec.modelId();
            fm.maxIters = spec.maxIters();
            fm.mandate = spec.mandate();
            fm.schedule = spec.schedule();
            fm.commandAllowlist = spec.commandAllowlist();
            fm.timeoutSeconds = spec.timeoutSeconds();
            fm.lastRunAtEpochMs = spec.lastRunAtEpochMs();
            return fm;
        }
    }
}
