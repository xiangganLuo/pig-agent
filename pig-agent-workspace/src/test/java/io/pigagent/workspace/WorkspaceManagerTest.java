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
        assertThat(Files.exists(ws.getAgentMd())).isTrue();
        assertThat(Files.exists(ws.getInfoMd())).isTrue();
    }

    @Test
    void readAgentMdReturnsContent() throws Exception {
        WorkspaceManager ws = new WorkspaceManager(tempDir.resolve("test-ws"));
        ws.initialize();
        assertThat(ws.readAgentMd()).contains("PigAgent");
    }
}
