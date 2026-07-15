package io.pigagent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SandboxConfigTest {

    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

    @Test
    void defaults_whenNoSandboxBlock() {
        PigAgentConfig.ExecSandboxConfig exec = new PigAgentConfig().getSandbox().getExec();
        assertThat(exec.getMaxOutputBytes()).isEqualTo(200_000L);
        assertThat(exec.getTimeoutSeconds()).isEqualTo(30);
        assertThat(exec.isScrubEnv()).isTrue();
        assertThat(exec.getDenylist()).isEmpty();
        assertThat(exec.getWorkingDir()).isNull();
    }

    @Test
    void missingSandboxBlock_yieldsDefaults() throws Exception {
        PigAgentConfig cfg = yaml.readValue("agent:\n  name: X\n", PigAgentConfig.class);
        assertThat(cfg.getSandbox().getExec().getMaxOutputBytes()).isEqualTo(200_000L);
        assertThat(cfg.getSandbox().getExec().isScrubEnv()).isTrue();
    }

    @Test
    void parsesConfiguredValues() throws Exception {
        String src = """
                sandbox:
                  exec:
                    max-output-bytes: 5000
                    timeout-seconds: 12
                    scrub-env: false
                    working-dir: /tmp/work
                    denylist:
                      - "danger-cmd"
                """;
        PigAgentConfig cfg = yaml.readValue(src, PigAgentConfig.class);
        PigAgentConfig.ExecSandboxConfig exec = cfg.getSandbox().getExec();
        assertThat(exec.getMaxOutputBytes()).isEqualTo(5000L);
        assertThat(exec.getTimeoutSeconds()).isEqualTo(12);
        assertThat(exec.isScrubEnv()).isFalse();
        assertThat(exec.getWorkingDir()).isEqualTo("/tmp/work");
        assertThat(exec.getDenylist()).containsExactly("danger-cmd");
    }

    @Test
    void nullDenylistSetterIsTolerated() {
        PigAgentConfig.ExecSandboxConfig exec = new PigAgentConfig.ExecSandboxConfig();
        exec.setDenylist(null);
        assertThat(exec.getDenylist()).isEmpty();
    }

    @Test
    void defaultWarnlistIsEmpty() {
        assertThat(new PigAgentConfig().getSandbox().getExec().getWarnlist()).isEmpty();
    }

    @Test
    void parsesWarnlist() throws Exception {
        String src = """
                sandbox:
                  exec:
                    warnlist:
                      - "risky-cmd"
                """;
        PigAgentConfig cfg = yaml.readValue(src, PigAgentConfig.class);
        assertThat(cfg.getSandbox().getExec().getWarnlist()).containsExactly("risky-cmd");
    }

    @Test
    void nullWarnlistSetterIsTolerated() {
        PigAgentConfig.ExecSandboxConfig exec = new PigAgentConfig.ExecSandboxConfig();
        exec.setWarnlist(null);
        assertThat(exec.getWarnlist()).isEmpty();
    }
}
