package io.pigagent.workspace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;

class WorkspaceManagerTest {

    @TempDir Path tempDir;

    @Test
    void initializeCreatesDirectoryStructure() throws Exception {
        WorkspaceManager ws = new WorkspaceManager(tempDir.resolve("test-ws"));
        ws.initialize();
        assertThat(Files.isDirectory(ws.getContextDir())).isTrue();
        assertThat(Files.isDirectory(ws.getSkillsDir())).isTrue();
        assertThat(Files.isDirectory(ws.getTasksDir())).isTrue();
        assertThat(Files.isDirectory(ws.getPluginsDir())).isTrue();
        assertThat(Files.exists(ws.getAgentMd())).isTrue();
        assertThat(Files.exists(ws.getInfoMd())).isTrue();
    }

    @Test
    void readAgentMdReturnsContent() throws Exception {
        WorkspaceManager ws = new WorkspaceManager(tempDir.resolve("test-ws"));
        ws.initialize();
        assertThat(ws.readAgentMd()).contains("PigAgent");
    }

    @Test
    void defaultAgentMdIsNonEmptyEnglishPrompt() throws Exception {
        // Arrange
        WorkspaceManager ws = new WorkspaceManager(tempDir.resolve("test-ws"));

        // Act
        ws.initialize();
        String prompt = ws.readAgentMd();

        // Assert — non-empty and identifies the agent
        assertThat(prompt).isNotBlank();
        assertThat(prompt).contains("PigAgent");
        // English heuristic: the prompt is dominated by ASCII (a mostly-English document),
        // asserted via key English anchors below rather than a brittle full-text match.
        long ascii = prompt.chars().filter(c -> c < 128).count();
        assertThat((double) ascii / prompt.length()).isGreaterThan(0.95);
    }

    @Test
    void defaultAgentMdMentionsFileToolsNotShellForFileOps() throws Exception {
        WorkspaceManager ws = new WorkspaceManager(tempDir.resolve("test-ws"));
        ws.initialize();
        String prompt = ws.readAgentMd();

        assertThat(prompt).contains("readFile", "writeFile", "listDirectory");
        assertThat(prompt).contains("executeCommand");
    }

    @Test
    void defaultAgentMdHasSkillsSection() throws Exception {
        WorkspaceManager ws = new WorkspaceManager(tempDir.resolve("test-ws"));
        ws.initialize();
        String prompt = ws.readAgentMd();

        assertThat(prompt).contains("loadSkill", "listSkills");
        assertThat(prompt).containsIgnoringCase("Skills");
        // At least one built-in skill named, so the guidance is accurate to the shipped set.
        assertThat(prompt).contains("systematic-debugging");
    }

    @Test
    void defaultAgentMdHasSafetyAndPermissionNote() throws Exception {
        WorkspaceManager ws = new WorkspaceManager(tempDir.resolve("test-ws"));
        ws.initialize();
        String prompt = ws.readAgentMd();

        assertThat(prompt).containsIgnoringCase("permission");
        assertThat(prompt).containsIgnoringCase("secret");
    }

    @Test
    void defaultAgentMdNotesWebSearchMayBeUnavailable() throws Exception {
        WorkspaceManager ws = new WorkspaceManager(tempDir.resolve("test-ws"));
        ws.initialize();
        String prompt = ws.readAgentMd();

        assertThat(prompt).contains("webSearch", "BRAVE_API_KEY");
        assertThat(prompt).containsIgnoringCase("unavailable");
    }

    @Test
    void defaultAgentMdRequestsResponseInUserLanguage() throws Exception {
        WorkspaceManager ws = new WorkspaceManager(tempDir.resolve("test-ws"));
        ws.initialize();
        String prompt = ws.readAgentMd();

        assertThat(prompt).containsIgnoringCase("user's language");
    }

    @Test
    void initializeSeedsAgentsMdAlongsideAgentMd() throws Exception {
        // Arrange
        WorkspaceManager ws = new WorkspaceManager(tempDir.resolve("test-ws"));

        // Act
        ws.initialize();

        // Assert — both the singular editable prompt AND the plural harness-scan seed exist, and are
        // distinct files (the plural one silences the native AGENTS.md scan, not pig's prompt).
        Path agentMd = ws.getRootPath().resolve("AGENT.md");
        Path agentsMd = ws.getRootPath().resolve("AGENTS.md");
        assertThat(Files.exists(agentMd)).isTrue();
        assertThat(Files.exists(agentsMd)).isTrue();
        // The plural seed points the user back at AGENT.md and is not pig's system prompt.
        String agents = Files.readString(agentsMd);
        assertThat(agents).contains("AGENT.md");
    }

    @Test
    void initializeDoesNotOverwriteExistingAgentsMd() throws Exception {
        // Arrange — a workspace whose AGENTS.md a user already customized
        WorkspaceManager ws = new WorkspaceManager(tempDir.resolve("test-ws"));
        ws.initialize();
        String custom = "# my agents notes\n";
        Files.writeString(ws.getRootPath().resolve("AGENTS.md"), custom);

        // Act — re-initialize (createIfAbsent must be idempotent)
        ws.initialize();

        // Assert — the user's content is preserved verbatim
        assertThat(Files.readString(ws.getRootPath().resolve("AGENTS.md"))).isEqualTo(custom);
    }

    @Test
    void defaultConfigYamlDropsStaleModelNameAndDocumentsCurrentSurface() throws Exception {
        // Arrange
        WorkspaceManager ws = new WorkspaceManager(tempDir.resolve("test-ws"));

        // Act
        ws.initialize();
        String yaml = Files.readString(ws.getRootPath().resolve("application.yaml"));

        // Assert — the stale model stub is gone (models.json is the source of truth)
        assertThat(yaml).doesNotContain("mimo-v2.5-pro");
        assertThat(yaml).doesNotContain("max-iters: 10"); // stale value; real default is 40
        // The template documents the key config knobs (as commented examples).
        assertThat(yaml).contains("permissions", "compression", "memory", "sandbox", "loop-detection");
        assertThat(yaml).contains("fallback-model-id");
        // At least one active (non-comment) top-level key so it parses to a non-null config.
        assertThat(yaml).contains("\nagent:");
    }

    @Test
    void initializeDoesNotOverwriteExistingAgentMd() throws Exception {
        // Arrange — a workspace with a user-customized AGENT.md already present
        WorkspaceManager ws = new WorkspaceManager(tempDir.resolve("test-ws"));
        ws.initialize();
        String custom = "# My custom prompt\n";
        Files.writeString(ws.getAgentMd(), custom);

        // Act — re-initialize (createIfAbsent must be idempotent)
        ws.initialize();

        // Assert — the user's content is preserved verbatim
        assertThat(ws.readAgentMd()).isEqualTo(custom);
    }
}
