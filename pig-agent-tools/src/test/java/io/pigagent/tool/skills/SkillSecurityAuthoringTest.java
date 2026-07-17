package io.pigagent.tool.skills;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** SkillSecurity.isValidSkillName: bounds an agent-/user-supplied skill name to a safe segment. */
class SkillSecurityAuthoringTest {

    @Test
    void acceptsSafeSingleSegmentNames() {
        assertThat(SkillSecurity.isValidSkillName("code-review")).isTrue();
        assertThat(SkillSecurity.isValidSkillName("my_skill.v2")).isTrue();
        assertThat(SkillSecurity.isValidSkillName("A1")).isTrue();
    }

    @Test
    void rejectsTraversalPathSeparatorsAndReservedNames() {
        assertThat(SkillSecurity.isValidSkillName("../evil")).isFalse();
        assertThat(SkillSecurity.isValidSkillName("a/b")).isFalse();
        assertThat(SkillSecurity.isValidSkillName("a\\b")).isFalse();
        assertThat(SkillSecurity.isValidSkillName("..")).isFalse();
        assertThat(SkillSecurity.isValidSkillName(".")).isFalse();
        assertThat(SkillSecurity.isValidSkillName(".pending")).isFalse();  // dot-prefixed reserved
        assertThat(SkillSecurity.isValidSkillName(".archive")).isFalse();
    }

    @Test
    void rejectsBlankOrNullOrOversized() {
        assertThat(SkillSecurity.isValidSkillName(null)).isFalse();
        assertThat(SkillSecurity.isValidSkillName("")).isFalse();
        assertThat(SkillSecurity.isValidSkillName(" ")).isFalse();
        assertThat(SkillSecurity.isValidSkillName("x".repeat(65))).isFalse();  // > 64 chars
    }
}
