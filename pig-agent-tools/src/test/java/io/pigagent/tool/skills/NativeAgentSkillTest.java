package io.pigagent.tool.skills;

import io.agentscope.core.skill.AgentSkill;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link NativeAgentSkill}: adapts a native {@code AgentSkill} to pig's {@link Skill} — name/metadata
 * from the native fields, body with front-matter stripped, supporting files from {@code getResources()}.
 */
class NativeAgentSkillTest {

    @Test
    void mapsName_description_keywords_version_fromNativeFields() {
        // Arrange
        AgentSkill native_ = AgentSkill.builder()
                .name("code-review").description("Review a diff.")
                .skillContent("# Code Review\nbody\n")
                .putMetadata("keywords", List.of("a", "b")).putMetadata("version", "2.0.0")
                .build();

        // Act
        SkillMetadata meta = new NativeAgentSkill(native_).metadata();

        // Assert
        assertThat(meta.name()).isEqualTo("code-review");
        assertThat(meta.description()).isEqualTo("Review a diff.");
        assertThat(meta.keywords()).containsExactly("a", "b");
        assertThat(meta.version()).isEqualTo("2.0.0");
    }

    @Test
    void name_fromNativeName() {
        AgentSkill native_ = AgentSkill.builder().name("tdd").description("d").skillContent("# X\n").build();
        assertThat(new NativeAgentSkill(native_).name()).isEqualTo("tdd");
    }

    @Test
    void content_stripsFrontMatter() {
        // Arrange — content carries a front-matter block.
        AgentSkill native_ = AgentSkill.builder().name("x").description("d")
                .skillContent("---\nname: x\ndescription: d\n---\n# X\nstep one\n").build();

        // Act
        String body = new NativeAgentSkill(native_).content();

        // Assert — body opens with its heading, front-matter removed (matches FileSkill).
        assertThat(body.stripLeading()).startsWith("# X");
        assertThat(body).doesNotContain("name: x");
    }

    @Test
    void content_withoutFrontMatter_returnedWhole() {
        AgentSkill native_ = AgentSkill.builder().name("x").description("d").skillContent("# X\nbody\n").build();
        assertThat(new NativeAgentSkill(native_).content().stripLeading()).startsWith("# X");
    }

    @Test
    void supportingFiles_adaptResources() throws java.io.IOException {
        // Arrange
        AgentSkill native_ = AgentSkill.builder().name("x").description("d").skillContent("# X\n")
                .addResource("refs/guide.md", "hello guide").build();

        // Act
        List<SkillResource> files = new NativeAgentSkill(native_).supportingFiles();

        // Assert
        assertThat(files).hasSize(1);
        SkillResource r = files.get(0);
        assertThat(r.path()).isEqualTo("refs/guide.md");
        assertThat(r.isText()).isTrue();
        assertThat(r.size()).isEqualTo("hello guide".getBytes(StandardCharsets.UTF_8).length);
        assertThat(r.read()).isEqualTo("hello guide");
    }

    @Test
    void supportingFiles_emptyWhenNoResources() {
        AgentSkill native_ = AgentSkill.builder().name("x").description("d").skillContent("# X\n").build();
        assertThat(new NativeAgentSkill(native_).supportingFiles()).isEmpty();
    }

    @Test
    void keywords_acceptCommaString() {
        AgentSkill native_ = AgentSkill.builder().name("x").description("d").skillContent("# X\n")
                .putMetadata("keywords", "a, b, c").build();
        assertThat(new NativeAgentSkill(native_).metadata().keywords()).containsExactly("a", "b", "c");
    }

    @Test
    void keywords_fallBackToWhenToUse() {
        AgentSkill native_ = AgentSkill.builder().name("x").description("d").skillContent("# X\n")
                .putMetadata("when-to-use", List.of("during review")).build();
        assertThat(new NativeAgentSkill(native_).metadata().keywords()).containsExactly("during review");
    }
}
