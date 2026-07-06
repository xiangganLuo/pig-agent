package io.pigagent.core.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class AgentSpecRepositoryTest {

    @TempDir
    Path agentsDir;

    @Test
    void roundTrip_preservesAllFields() {
        // Arrange
        AgentSpecRepository repo = new AgentSpecRepository(agentsDir);
        AgentSpec spec = AgentSpec.create("nightwatch", "代码守夜人")
                .withSysPrompt("盯着 repo\n跑测试并总结")
                .withToolNames(List.of("readFile", "executeCommand"))
                .withPermissionMode("auto")
                .withModelId("m-strong")
                .withMaxIters(15);

        // Act
        repo.save(spec);
        Optional<AgentSpec> loaded = repo.findById("nightwatch");

        // Assert — every field survives the write→read round trip
        assertThat(loaded).isPresent();
        assertThat(loaded.get()).isEqualTo(spec);
    }

    @Test
    void findAll_returnsAllSavedSpecs() {
        // Arrange
        AgentSpecRepository repo = new AgentSpecRepository(agentsDir);
        repo.save(AgentSpec.create("a", "Alpha"));
        repo.save(AgentSpec.create("b", "Beta"));

        // Act
        List<AgentSpec> all = repo.findAll();

        // Assert
        assertThat(all).extracting(AgentSpec::id).containsExactlyInAnyOrder("a", "b");
    }

    @Test
    void corruptFile_isSkipped_andDoesNotBreakListing() throws IOException {
        // Arrange
        AgentSpecRepository repo = new AgentSpecRepository(agentsDir);
        repo.save(AgentSpec.create("good", "Good"));
        Files.writeString(agentsDir.resolve("bad.md"), "this is not valid front matter");

        // Act
        List<AgentSpec> all = repo.findAll();

        // Assert — the good one is listed, the corrupt file neither crashes nor appears
        assertThat(all).extracting(AgentSpec::id).containsExactly("good");
        assertThat(repo.findById("bad")).isEmpty();
    }

    @Test
    void deleteById_removesSpec() {
        // Arrange
        AgentSpecRepository repo = new AgentSpecRepository(agentsDir);
        repo.save(AgentSpec.create("gone", "Gone"));

        // Act
        repo.deleteById("gone");

        // Assert
        assertThat(repo.findById("gone")).isEmpty();
    }
}
