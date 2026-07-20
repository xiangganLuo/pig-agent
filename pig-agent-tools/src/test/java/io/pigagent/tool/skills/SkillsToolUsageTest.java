package io.pigagent.tool.skills;

import io.pigagent.tool.skills.curator.SkillUsageRecorder;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S3: {@code SkillsTool.loadSkill} self-feeds a usage signal on a hit only — a miss (unknown skill)
 * records nothing. The {@code @Tool} return semantics are unchanged (covered by {@code SkillsToolTest});
 * this test only asserts the recorder interaction and that recording never alters the result.
 */
class SkillsToolUsageTest {

    /** A minimal in-memory skill. */
    private record FakeSkill(String name, String body) implements Skill {
        @Override
        public String content() {
            return body;
        }
    }

    /** A source that returns a fixed set of skills. */
    private record FakeSource(List<Skill> skills) implements SkillSource {
        @Override
        public String name() {
            return "fake";
        }

        @Override
        public List<Skill> discover() {
            return skills;
        }
    }

    /** Captures the names recorded. */
    private static final class RecordingRecorder implements SkillUsageRecorder {
        final List<String> recorded = new ArrayList<>();

        @Override
        public void record(String skillName) {
            recorded.add(skillName);
        }
    }

    private SkillsTool tool(RecordingRecorder recorder) {
        SkillRegistry registry = new SkillRegistry(List.of(
                new FakeSource(List.of(new FakeSkill("demo", "# Demo\nbody")))));
        return new SkillsTool(registry, SkillLimits.defaults(), recorder);
    }

    @Test
    void loadSkill_hit_recordsUsageOnce_andReturnsBody() {
        // Arrange
        RecordingRecorder recorder = new RecordingRecorder();
        SkillsTool tool = tool(recorder);

        // Act
        String out = tool.loadSkill("demo");

        // Assert — body returned unchanged (usage recording is invisible) and recorded exactly once.
        assertThat(out).contains("# Demo").contains("body");
        assertThat(recorder.recorded).containsExactly("demo");
    }

    @Test
    void loadSkill_miss_recordsNothing() {
        // Arrange
        RecordingRecorder recorder = new RecordingRecorder();
        SkillsTool tool = tool(recorder);

        // Act
        String out = tool.loadSkill("does-not-exist");

        // Assert — unchanged not-found message, no usage recorded.
        assertThat(out).isEqualTo("Skill not found: does-not-exist");
        assertThat(recorder.recorded).isEmpty();
    }

    @Test
    void loadSkill_recorderThrows_doesNotAffectResult() {
        // Arrange — a recorder that blows up must not break loadSkill.
        SkillRegistry registry = new SkillRegistry(List.of(
                new FakeSource(List.of(new FakeSkill("demo", "# Demo\nbody")))));
        SkillsTool tool = new SkillsTool(registry, SkillLimits.defaults(), skillName -> {
            throw new RuntimeException("recorder boom");
        });

        // Act + Assert
        String out = tool.loadSkill("demo");
        assertThat(out).contains("# Demo");
    }
}
