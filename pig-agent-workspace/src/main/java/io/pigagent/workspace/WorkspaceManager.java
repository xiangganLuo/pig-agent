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
        Files.createDirectories(rootPath.resolve("sessions"));
        Files.createDirectories(rootPath.resolve("agents"));
        Files.createDirectories(rootPath.resolve("reports"));
        createIfAbsent(rootPath.resolve("AGENT.md"), defaultAgentMd());
        createIfAbsent(rootPath.resolve("INFO.md"), defaultInfoMd());
        createIfAbsent(rootPath.resolve("application.yaml"), defaultConfigYaml());
    }

    public Path getRootPath() { return rootPath; }
    public Path getAgentMd() { return rootPath.resolve("AGENT.md"); }
    public Path getInfoMd() { return rootPath.resolve("INFO.md"); }
    public Path getContextDir() { return rootPath.resolve("context"); }
    public Path getSkillsDir() { return rootPath.resolve("skills"); }
    public Path getTasksDir() { return rootPath.resolve("tasks"); }
    public Path getRecurringTasksDir() { return rootPath.resolve("tasks/recurring"); }
    public Path getTodayTaskDir() { return rootPath.resolve("tasks").resolve(todayDir()); }
    public Path getSessionsDir() { return rootPath.resolve("sessions"); }
    public Path getAgentsDir() { return rootPath.resolve("agents"); }
    public Path getReportsDir() { return rootPath.resolve("reports"); }
    public Path getModelsFile() { return rootPath.resolve("models.json"); }
    public Path getMcpFile() { return rootPath.resolve("mcp.json"); }

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
        return """
                # AGENT.md - System Prompt

                You are PigAgent, a helpful AI assistant running in the terminal.

                ## Capabilities
                - Read, write and list files (use readFile, writeFile, listDirectory) — with native absolute paths
                - Execute shell commands (use executeCommand) — for running programs, NOT for creating files
                - Fetch web content (use fetchUrl)
                - Search the web (use webSearch, requires BRAVE_API_KEY)
                - Manage tasks (use createTask, listTasks, updateTaskStatus)
                - Create checklists (use createChecklist, completeItem, showChecklist)
                - Load skills (use loadSkill, listSkills)

                ## Guidelines
                - Be concise and direct in responses
                - Use tools when needed to accomplish tasks
                - For file create/read/list, prefer writeFile/readFile/listDirectory over the shell
                - Ask for clarification when requirements are ambiguous
                - Report errors clearly with context
                - Prefer existing tools over manual implementations
                """;
    }

    private String defaultInfoMd() {
        return "# Environment Info\n\n- OS: " + System.getProperty("os.name")
                + "\n- Java: " + System.getProperty("java.version")
                + "\n- Workspace: " + rootPath.toAbsolutePath() + "\n";
    }

    private String defaultConfigYaml() {
        return """
                # Pig Agent Configuration

                model:
                  provider: anthropic
                  model-name: mimo-v2.5-pro

                agent:
                  name: PigAgent
                  max-iters: 10

                channels:
                  telegram:
                    enabled: false
                    token: ""
                  discord:
                    enabled: false
                    token: ""

                mcp:
                  servers: {}
                  # Example stdio server:
                  #   filesystem:
                  #     command: npx
                  #     args: ["-y", "@modelcontextprotocol/server-filesystem", "/path/to/dir"]
                  # Example SSE server:
                  #   remote:
                  #     url: http://localhost:3000/sse
                  #     headers:
                  #       Authorization: Bearer token
                """;
    }
}
