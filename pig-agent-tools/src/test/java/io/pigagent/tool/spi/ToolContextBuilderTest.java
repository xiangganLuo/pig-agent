package io.pigagent.tool.spi;

import io.pigagent.task.TaskManager;
import io.pigagent.tool.sandbox.SandboxPolicy;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * T6: the {@link ToolContext.Builder} is behaviorally equivalent to the telescoping constructors
 * (same field values, same null/empty defaults) — locking the "pure refactor, zero behavior change"
 * guarantee.
 */
class ToolContextBuilderTest {

    @Test
    void builderWithNoFields_matchesEmptyConstructorDefaults() {
        ToolContext viaCtor = new ToolContext(null, null);
        ToolContext viaBuilder = ToolContext.builder().build();

        assertThat(viaBuilder.taskManager()).isEqualTo(viaCtor.taskManager());
        assertThat(viaBuilder.skillsDir()).isEqualTo(viaCtor.skillsDir());
        assertThat(viaBuilder.workspaceRoot()).isEqualTo(viaCtor.workspaceRoot());
        assertThat(viaBuilder.webAllowedHosts()).isEqualTo(viaCtor.webAllowedHosts()).isEmpty();
        assertThat(viaBuilder.sandboxPolicy()).isNull();
        assertThat(viaBuilder.notificationService()).isNull();
        assertThat(viaBuilder.skillStaging()).isNull();
        assertThat(viaBuilder.userProfileFile()).isNull();
        assertThat(viaBuilder.outreachEnabled().getAsBoolean()).isFalse();
        assertThat(viaBuilder.autonomousSkillsEnabled().getAsBoolean()).isFalse();
        assertThat(viaBuilder.userProfileEnabled().getAsBoolean()).isFalse();
    }

    @Test
    void builderWithAllFields_matchesFullConstructor() {
        TaskManager tm = mock(TaskManager.class);
        Path skills = Path.of("skills");
        Path ws = Path.of("ws");
        List<String> hosts = List.of("example.com");
        SandboxPolicy policy = SandboxPolicy.defaults();
        BooleanSupplier yes = () -> true;
        Path userFile = Path.of("USER.md");

        ToolContext viaCtor = new ToolContext(
                tm, skills, ws, hosts, policy, null, yes, null, yes, userFile, yes);
        ToolContext viaBuilder = ToolContext.builder()
                .taskManager(tm).skillsDir(skills).workspaceRoot(ws).webAllowedHosts(hosts)
                .sandboxPolicy(policy).outreachEnabled(yes).autonomousSkillsEnabled(yes)
                .userProfileFile(userFile).userProfileEnabled(yes)
                .build();

        assertThat(viaBuilder.taskManager()).isSameAs(viaCtor.taskManager());
        assertThat(viaBuilder.skillsDir()).isEqualTo(viaCtor.skillsDir());
        assertThat(viaBuilder.workspaceRoot()).isEqualTo(viaCtor.workspaceRoot());
        assertThat(viaBuilder.webAllowedHosts()).isEqualTo(viaCtor.webAllowedHosts())
                .containsExactly("example.com");
        assertThat(viaBuilder.sandboxPolicy()).isSameAs(viaCtor.sandboxPolicy());
        assertThat(viaBuilder.userProfileFile()).isEqualTo(viaCtor.userProfileFile());
        assertThat(viaBuilder.outreachEnabled().getAsBoolean())
                .isEqualTo(viaCtor.outreachEnabled().getAsBoolean()).isTrue();
        assertThat(viaBuilder.autonomousSkillsEnabled().getAsBoolean()).isTrue();
        assertThat(viaBuilder.userProfileEnabled().getAsBoolean()).isTrue();
    }
}
