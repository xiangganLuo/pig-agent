package io.pigagent.tool.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Unit tests for the immutable, fault-tolerant sandbox policy value object. */
class SandboxPolicyTest {

    @Test
    void defaultsAreConservativeAndSafe() {
        SandboxPolicy p = SandboxPolicy.defaults();
        assertThat(p.maxOutputBytes()).isEqualTo(SandboxPolicy.DEFAULT_MAX_OUTPUT_BYTES);
        assertThat(p.timeoutSeconds()).isEqualTo(SandboxPolicy.DEFAULT_TIMEOUT_SECONDS);
        assertThat(p.scrubEnv()).isTrue();
        assertThat(p.extraDenyPatterns()).isEmpty();
        assertThat(p.workingDir()).isNull();
    }

    @Test
    void invalidValuesClampToDefaults() {
        SandboxPolicy p = new SandboxPolicy(-5, 0, null, true, "   ");
        assertThat(p.maxOutputBytes()).isEqualTo(SandboxPolicy.DEFAULT_MAX_OUTPUT_BYTES);
        assertThat(p.timeoutSeconds()).isEqualTo(SandboxPolicy.DEFAULT_TIMEOUT_SECONDS);
        assertThat(p.extraDenyPatterns()).isEmpty();
        assertThat(p.workingDir()).isNull(); // blank → null (inherit)
    }

    @Test
    void withMethodsReturnNewInstanceAndDoNotMutateOriginal() {
        SandboxPolicy base = SandboxPolicy.defaults();

        SandboxPolicy changed = base.withMaxOutputBytes(42).withTimeoutSeconds(7)
                .withScrubEnv(false).withWorkingDir("/tmp/work")
                .withExtraDenyPatterns(List.of("foo"));

        assertThat(changed.maxOutputBytes()).isEqualTo(42);
        assertThat(changed.timeoutSeconds()).isEqualTo(7);
        assertThat(changed.scrubEnv()).isFalse();
        assertThat(changed.workingDir()).isEqualTo("/tmp/work");
        assertThat(changed.extraDenyPatterns()).containsExactly("foo");
        // original untouched
        assertThat(base.maxOutputBytes()).isEqualTo(SandboxPolicy.DEFAULT_MAX_OUTPUT_BYTES);
        assertThat(base.scrubEnv()).isTrue();
        assertThat(base.extraDenyPatterns()).isEmpty();
    }

    @Test
    void extraDenyPatternsAreImmutable() {
        List<String> mutable = new ArrayList<>();
        mutable.add("foo");
        SandboxPolicy p = SandboxPolicy.defaults().withExtraDenyPatterns(mutable);

        // defensive copy: mutating the source does not affect the policy
        mutable.add("bar");
        assertThat(p.extraDenyPatterns()).containsExactly("foo");
        // returned list is unmodifiable
        assertThatThrownBy(() -> p.extraDenyPatterns().add("baz"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void defaultsHaveEmptyWarnPatterns() {
        assertThat(SandboxPolicy.defaults().extraWarnPatterns()).isEmpty();
    }

    @Test
    void legacyFiveArgConstructorHasEmptyWarnPatterns() {
        // Backward-compatible 5-arg constructor (no warnlist) still compiles + defaults warn to empty.
        SandboxPolicy p = new SandboxPolicy(1000, 5, List.of("d"), true, null);
        assertThat(p.extraDenyPatterns()).containsExactly("d");
        assertThat(p.extraWarnPatterns()).isEmpty();
    }

    @Test
    void withExtraWarnPatternsIsImmutableAndPreservesOthers() {
        List<String> mutable = new ArrayList<>();
        mutable.add("w");
        SandboxPolicy p = SandboxPolicy.defaults().withExtraDenyPatterns(List.of("d"))
                .withExtraWarnPatterns(mutable);

        // defensive copy + preserves the deny list threaded through
        mutable.add("w2");
        assertThat(p.extraWarnPatterns()).containsExactly("w");
        assertThat(p.extraDenyPatterns()).containsExactly("d");
        assertThatThrownBy(() -> p.extraWarnPatterns().add("x"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
