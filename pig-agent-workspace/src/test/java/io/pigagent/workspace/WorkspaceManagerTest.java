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
