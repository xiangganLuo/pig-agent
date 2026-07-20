package io.pigagent.tool.skills.curator;

import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.skill.curator.SkillUsageStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * S3 usage-recorder seam: the default no-op is inert; the native recorder self-feeds usage into the
 * AgentScope {@link SkillUsageStore} (agent-created skills increment, unknown skills are a safe
 * no-op), and every call is fault-tolerant.
 */
class SkillUsageRecorderTest {

    @Test
    void noop_doesNothing_andNeverThrows() {
        SkillUsageRecorder noop = SkillUsageRecorder.noop();
        assertThatCode(() -> {
            noop.record("anything");
            noop.markCreated("anything");
            noop.record(null);
        }).doesNotThrowAnyException();
    }

    @Test
    void native_marksCreated_thenBumpsUse(@TempDir Path ws) {
        // Arrange — a real usage store over a temp workspace.
        SkillUsageStore store = new SkillUsageStore(new LocalFilesystem(ws));
        NativeSkillUsageRecorder recorder = new NativeSkillUsageRecorder(store);

        // Act — a promoted skill is marked agent-created, then two loadSkill hits are recorded.
        recorder.markCreated("distilled-skill");
        recorder.record("distilled-skill");
        recorder.record("distilled-skill");

        // Assert — the store tracked the usage.
        assertThat(store.get("distilled-skill")).isPresent();
        assertThat(store.get("distilled-skill").get().useCount()).isGreaterThanOrEqualTo(2L);
    }

    @Test
    void native_recordUnknownSkill_isSafeNoOp(@TempDir Path ws) {
        // Arrange
        SkillUsageStore store = new SkillUsageStore(new LocalFilesystem(ws));
        NativeSkillUsageRecorder recorder = new NativeSkillUsageRecorder(store);

        // Act — record a skill that was never marked created (hand-authored / built-in).
        assertThatCode(() -> recorder.record("never-registered")).doesNotThrowAnyException();

        // Assert — native provenance gate: no phantom record is created.
        assertThat(store.get("never-registered")).isEmpty();
    }

    @Test
    void native_faultTolerant_whenStoreThrows() {
        // Arrange — a store that throws on every mutation.
        SkillUsageStore throwing = Mockito.mock(SkillUsageStore.class);
        Mockito.doThrow(new RuntimeException("boom")).when(throwing).bumpUse(Mockito.anyString());
        Mockito.doThrow(new RuntimeException("boom")).when(throwing).markAgentCreated(
                Mockito.anyString(), Mockito.anyString(), Mockito.anyList());
        NativeSkillUsageRecorder recorder = new NativeSkillUsageRecorder(throwing);

        // Act + Assert — failures are swallowed (must never affect loadSkill).
        assertThatCode(() -> {
            recorder.record("x");
            recorder.markCreated("x");
        }).doesNotThrowAnyException();
    }
}
