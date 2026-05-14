package io.pigagent.workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Manages the workspace directory structure.
 * Default workspace: ~/.pig-agent/workspace/
 */
public final class WorkspaceManager {

    private static final String DEFAULT_WORKSPACE_DIR = ".pig-agent/workspace";
    private final Path rootPath;

    public WorkspaceManager(Path rootPath) {
        this.rootPath = rootPath;
    }

    public static WorkspaceManager defaultWorkspace() {
        Path home = Path.of(System.getProperty("user.home"));
        return new WorkspaceManager(home.resolve(DEFAULT_WORKSPACE_DIR));
    }

    public void initialize() throws IOException {
        Files.createDirectories(rootPath);
        Files.createDirectories(rootPath.resolve("context"));
        Files.createDirectories(rootPath.resolve("skills"));
        Files.createDirectories(rootPath.resolve("tasks"));
        Files.createDirectories(rootPath.resolve("tasks/recurring"));
        Files.createDirectories(rootPath.resolve("tasks").resolve(todayDir()));
        createIfAbsent(rootPath.resolve("AGENT.md"), defaultAgentMd());
        createIfAbsent(rootPath.resolve("INFO.md"), defaultInfoMd());
    }

    public Path getRootPath() { return rootPath; }
    public Path getAgentMd() { return rootPath.resolve("AGENT.md"); }
    public Path getInfoMd() { return rootPath.resolve("INFO.md"); }
    public Path getContextDir() { return rootPath.resolve("context"); }
    public Path getSkillsDir() { return rootPath.resolve("skills"); }
    public Path getTasksDir() { return rootPath.resolve("tasks"); }
    public Path getRecurringTasksDir() { return rootPath.resolve("tasks/recurring"); }
    public Path getTodayTaskDir() { return rootPath.resolve("tasks").resolve(todayDir()); }

    public String readAgentMd() throws IOException {
        return Files.readString(getAgentMd());
    }

    public String readInfoMd() throws IOException {
        return Files.readString(getInfoMd());
    }

    private void createIfAbsent(Path path, String defaultContent) throws IOException {
        if (!Files.exists(path)) {
            Files.writeString(path, defaultContent);
        }
    }

    private String todayDir() {
        return java.time.LocalDate.now().toString();
    }

    private String defaultAgentMd() {
        return "# AGENT.md - System Prompt\n\nYou are PigAgent, a helpful AI assistant running in the terminal.\n";
    }

    private String defaultInfoMd() {
        return "# Environment Info\n\n- OS: " + System.getProperty("os.name")
                + "\n- Java: " + System.getProperty("java.version")
                + "\n- Workspace: " + rootPath.toAbsolutePath() + "\n";
    }
}
