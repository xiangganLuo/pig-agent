package io.pigagent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** tasks.execute 配置门（task-executor-wiring）：默认关；YAML 覆盖为开；容错。 */
class TasksConfigTest {

    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

    @Test
    void defaults_executeIsFalse() {
        assertThat(new PigAgentConfig().getTasks().isExecute()).isFalse();
    }

    @Test
    void missingBlock_yieldsDefaultDisabled() throws Exception {
        PigAgentConfig cfg = yaml.readValue("agent:\n  name: X\n", PigAgentConfig.class);
        assertThat(cfg.getTasks().isExecute()).isFalse();
    }

    @Test
    void parsesExecuteTrue() throws Exception {
        PigAgentConfig cfg = yaml.readValue("tasks:\n  execute: true\n", PigAgentConfig.class);
        assertThat(cfg.getTasks().isExecute()).isTrue();
    }

    @Test
    void nullSetterTolerated() {
        PigAgentConfig cfg = new PigAgentConfig();
        cfg.setTasks(null);
        assertThat(cfg.getTasks()).isNotNull();
        assertThat(cfg.getTasks().isExecute()).isFalse();
    }
}
