package io.pigagent.core.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class AgentSpecAutonomousTest {

    @Test
    void interactiveByDefault_autonomousWhenScheduled() {
        AgentSpec interactive = AgentSpec.create("a", "A");
        assertThat(interactive.isAutonomous()).isFalse();
        assertThat(interactive.mandate()).isNull();
        assertThat(interactive.commandAllowlist()).isEmpty();
        assertThat(interactive.timeoutSeconds()).isZero();
        assertThat(interactive.lastRunAtEpochMs()).isZero();

        AgentSpec autonomous = interactive.withSchedule("0 2 * * *").withMandate("跑测试");
        assertThat(autonomous.isAutonomous()).isTrue();
        assertThat(autonomous.mandate()).isEqualTo("跑测试");
        assertThat(interactive.isAutonomous()).isFalse(); // original untouched
    }

    @Test
    void blankSchedule_isNotAutonomous() {
        assertThat(AgentSpec.create("a", "A").withSchedule("  ").isAutonomous()).isFalse();
        assertThat(AgentSpec.create("a", "A").withSchedule(null).isAutonomous()).isFalse();
    }

    @Test
    void withXxx_autonomousFields_dontMutateOriginal() {
        AgentSpec base = AgentSpec.create("a", "A");
        AgentSpec updated = base.withCommandAllowlist(List.of("mvn", "git"))
                .withTimeoutSeconds(900).withLastRunAtEpochMs(12345L);
        assertThat(updated.commandAllowlist()).containsExactly("mvn", "git");
        assertThat(updated.timeoutSeconds()).isEqualTo(900);
        assertThat(updated.lastRunAtEpochMs()).isEqualTo(12345L);
        assertThat(base.commandAllowlist()).isEmpty();
        assertThat(base.timeoutSeconds()).isZero();
    }

    @Test
    void backwardCompatibleConstructor_stillWorks() {
        AgentSpec spec = new AgentSpec("a", "A", "prompt", List.of("readFile"), "auto", "m1", 5);
        assertThat(spec.isAutonomous()).isFalse();
        assertThat(spec.commandAllowlist()).isEmpty();
        assertThat(spec.timeoutSeconds()).isZero();
    }

    @Test
    void repository_roundTrips_autonomousFields(@TempDir Path dir) {
        AgentSpecRepository repo = new AgentSpecRepository(dir);
        AgentSpec spec = AgentSpec.create("nightwatch", "代码守夜人")
                .withSysPrompt("盯项目")
                .withMandate("跑 mvn test 并总结\n第二行")
                .withSchedule("0 2 * * *")
                .withCommandAllowlist(List.of("mvn", "git"))
                .withTimeoutSeconds(900)
                .withLastRunAtEpochMs(1_700_000_000_000L);

        repo.save(spec);
        Optional<AgentSpec> loaded = repo.findById("nightwatch");

        assertThat(loaded).isPresent();
        assertThat(loaded.get()).isEqualTo(spec); // full round trip incl. autonomous fields
    }
}
